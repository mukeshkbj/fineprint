package app.fineprint

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

private class Doc(val clauses: List<String>, val source: String, val windowId: Int)

class FineprintService : AccessibilityService() {
    private val scope = MainScope()
    private var scanJob: Job? = null
    private var scanDue = 0L
    private var firstEvent = 0L
    private var doc: Doc? = null
    private var docKey by mutableStateOf<String?>(null)
    private var dismissed: String? = null

    private var pill: OverlayWindow? = null
    private var sheet: OverlayWindow? = null
    private var target: OverlayWindow? = null
    private var highlight: OverlayWindow? = null
    private var marked: AccessibilityNodeInfo? = null

    private var leaving = mutableStateOf(false)
    private var hot by mutableStateOf(false)
    private var mode by mutableStateOf(SheetMode.Hidden)
    private var selected by mutableStateOf<Int?>(null)
    private var found by mutableStateOf<Boolean?>(null)

    private val dp get() = resources.displayMetrics.density
    private val screen get() = getSystemService(WindowManager::class.java).currentWindowMetrics
    private val bars get() = screen.windowInsets.getInsets(WindowInsets.Type.systemBars())
    private val peekPx get() = (136 * dp).toInt() + bars.bottom

    override fun onServiceConnected() {
        scope.launch { Prefs.auto.collect { if (!it) hidePill() else schedule(0) } }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.packageName == packageName) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> schedule(450)
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                followMark()
                schedule(900)
            }
            else -> if (doc?.windowId != event.windowId || event.source?.viewIdResourceName?.endsWith("url_bar") == true) schedule(900)
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        scope.cancel()
        listOfNotNull(pill, sheet, target, highlight).forEach(OverlayWindow::remove)
        super.onDestroy()
    }

    private fun schedule(wait: Long) {
        val now = System.currentTimeMillis()
        if (scanJob?.isActive != true) firstEvent = now
        scanDue = minOf(now + wait, firstEvent + 3000)
        scanJob?.cancel()
        scanJob = scope.launch {
            delay(scanDue - System.currentTimeMillis())
            scan()
        }
    }

    private suspend fun scan() {
        if (sheet != null || !Prefs.auto.value) return
        val found = withContext(Dispatchers.Default) {
            val root = rootInActiveWindow ?: return@withContext null
            if (root.packageName == packageName) return@withContext Unit
            val clauses = splitClauses(readBlocks(landmark(root, "main") ?: landmark(root, "article") ?: root).joinToString("\n"))
            if (looksLikeTerms(clauses)) Doc(clauses, sourceOf(root), root.windowId) else null
        }
        if (found == Unit) return
        val next = found as Doc?
        if (next == null) {
            doc = null
            hidePill()
            return
        }
        doc = next
        val check = Checker.check(next.clauses, next.source)
        docKey = check.key
        if (dismissed != check.key) showPill()
    }

    private fun landmark(node: AccessibilityNodeInfo, role: String): AccessibilityNodeInfo? {
        if (node.extras.getString("AccessibilityNodeInfo.chromeRole") == role) return node
        for (i in 0 until node.childCount) node.getChild(i)?.let { landmark(it, role) }?.let { return it }
        return null
    }

    private fun inlineText(node: AccessibilityNodeInfo): String? {
        val display = node.extras.getString("AccessibilityNodeInfo.cssDisplay")
        val inline = display?.startsWith("inline") == true || node.extras.getString("AccessibilityNodeInfo.chromeRole") == "staticText"
        if (!inline) return null
        if (node.childCount == 0) return node.text?.toString().orEmpty()
        return buildString {
            for (i in 0 until node.childCount) append(node.getChild(i)?.let(::inlineText) ?: return null)
        }
    }

    private fun readBlocks(node: AccessibilityNodeInfo, out: MutableList<String> = mutableListOf()): List<String> {
        if (node.childCount == 0) {
            node.text?.takeIf { it.isNotBlank() }?.let { out += it.toString() }
            return out
        }
        val run = StringBuilder()
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val inline = inlineText(child)
            if (inline != null) {
                run.append(inline)
            } else {
                if (run.isNotBlank()) out += run.toString()
                run.clear()
                readBlocks(child, out)
            }
        }
        if (run.isNotBlank()) out += run.toString()
        return out
    }

    private fun ownText(node: AccessibilityNodeInfo): String? {
        if (node.childCount == 0) return node.text?.toString()
        return buildString {
            for (i in 0 until node.childCount) append(node.getChild(i)?.let(::inlineText) ?: return null)
        }.ifBlank { null }
    }

    private fun sourceOf(root: AccessibilityNodeInfo): String {
        val url = root.findAccessibilityNodeInfosByViewId("com.android.chrome:id/url_bar").firstOrNull()?.text?.toString()
        if (!url.isNullOrBlank()) return url.substringAfter("://").substringBefore('/')
        val app = root.packageName?.toString() ?: return "this app"
        return runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(app, 0)).toString() }.getOrDefault(app)
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun showPill() {
        if (pill != null) return
        val params = overlayParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END)
        params.y = (screen.bounds.height() * 0.36f).toInt()
        val gone = mutableStateOf(false).also { leaving = it }
        pill = OverlayWindow(this, params) {
            val check by Checker.current.collectAsState()
            val current = check?.takeIf { it.key == docKey } ?: return@OverlayWindow
            var expanded by androidx.compose.runtime.remember(current.key) { mutableStateOf(true) }
            val view = androidx.compose.ui.platform.LocalView.current
            androidx.compose.runtime.LaunchedEffect(current.key, current.running) {
                if (current.running) {
                    expanded = true
                } else {
                    if (current.problem == null) view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    delay(3500)
                    expanded = false
                }
            }
            Pill(current, expanded, gone.value, Modifier.pointerInteropFilter { onPillTouch(it) })
        }
    }

    private fun hidePill() {
        val window = pill ?: return
        pill = null
        leaving.value = true
        scope.launch {
            delay(220)
            window.remove()
        }
    }

    private var downX = 0f
    private var downY = 0f
    private var startLeft = 0
    private var startTop = 0
    private var dragging = false
    private var tracker: VelocityTracker? = null
    private var snapJob: Job? = null

    private fun leftOf(params: WindowManager.LayoutParams, width: Int) =
        if (params.gravity and Gravity.HORIZONTAL_GRAVITY_MASK == Gravity.LEFT) params.x else screen.bounds.width() - params.x - width

    private val targetCenter get() = screen.bounds.width() / 2f to screen.bounds.height() - bars.bottom - 92 * dp

    private fun onPillTouch(event: MotionEvent): Boolean {
        val window = pill ?: return false
        val params = window.params
        val width = window.view.width
        val height = window.view.height
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                snapJob?.cancel()
                downX = event.rawX
                downY = event.rawY
                startLeft = leftOf(params, width)
                startTop = params.y
                dragging = false
                tracker = VelocityTracker.obtain().apply { addMovement(event) }
            }
            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(event)
                val dx = event.rawX - downX
                val dy = event.rawY - downY
                if (!dragging && hypot(dx, dy) > ViewConfiguration.get(this).scaledTouchSlop) {
                    dragging = true
                    params.gravity = Gravity.TOP or Gravity.LEFT
                    showTarget()
                }
                if (dragging) {
                    var left = startLeft + dx
                    var top = startTop + dy
                    val (cx, cy) = targetCenter
                    val near = hypot(left + width / 2f - cx, top + height / 2f - cy) < 76 * dp
                    if (near != hot) {
                        hot = near
                        if (near) window.view.performHapticFeedback(HapticFeedbackConstants.SEGMENT_FREQUENT_TICK)
                    }
                    if (near) {
                        left = cx - width / 2f
                        top = cy - height / 2f
                    }
                    params.x = left.toInt()
                    params.y = top.toInt()
                    window.update()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                tracker?.apply { addMovement(event); computeCurrentVelocity(1000) }
                val vx = tracker?.xVelocity ?: 0f
                val vy = tracker?.yVelocity ?: 0f
                tracker?.recycle()
                tracker = null
                when {
                    !dragging && event.actionMasked == MotionEvent.ACTION_UP -> openSheet()
                    hot -> dismissPill()
                    dragging -> snap(window, vx, vy)
                }
                hideTarget()
            }
        }
        return true
    }

    private fun snap(window: OverlayWindow, vx: Float, vy: Float) {
        val params = window.params
        val width = window.view.width
        val height = window.view.height
        val bounds = screen.bounds
        val left = leftOf(params, width)
        val right = left + width / 2f + vx * 0.12f > bounds.width() / 2f
        params.gravity = Gravity.TOP or if (right) Gravity.RIGHT else Gravity.LEFT
        params.x = if (right) bounds.width() - left - width else left
        val top = (params.y + vy * 0.08f).coerceIn(bars.top + 8 * dp, bounds.height() - bars.bottom - height - 8 * dp)
        val spec = spring<Float>(0.72f, 420f)
        if (reduceMotion(this)) {
            params.x = 0
            params.y = top.toInt()
            window.update()
            return
        }
        snapJob = scope.launch {
            launch { animate(params.x.toFloat(), 0f, if (right) -vx else vx, spec) { v, _ -> params.x = v.toInt(); window.update() } }
            launch { animate(params.y.toFloat(), top, vy, spec) { v, _ -> params.y = v.toInt(); window.update() } }
        }
    }

    private fun showTarget() {
        if (target != null) return
        val size = (132 * dp).toInt()
        val params = overlayParams(size, size, Gravity.TOP or Gravity.LEFT, touchable = false)
        val (cx, cy) = targetCenter
        params.x = (cx - size / 2).toInt()
        params.y = (cy - size / 2).toInt()
        target = OverlayWindow(this, params) { DismissTarget(hot) }
    }

    private fun hideTarget() {
        hot = false
        target?.remove()
        target = null
    }

    private fun dismissPill() {
        dismissed = docKey
        hidePill()
    }

    private fun openSheet() {
        if (sheet != null) return
        pill?.view?.visibility = android.view.View.INVISIBLE
        selected = null
        mode = SheetMode.Full
        val params = overlayParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, Gravity.BOTTOM, focusable = true)
        sheet = OverlayWindow(this, params, onBack = { changeMode(SheetMode.Hidden) }) {
            val check by Checker.current.collectAsState()
            val current = check ?: return@OverlayWindow
            val density = LocalDensity.current
            val ordered = current.findings.sortedWith(compareBy({ it.verdict.severity }, { it.index }))
            val position = ordered.indexOfFirst { it.index == selected }
            Sheet(
                mode = mode,
                onMode = ::changeMode,
                height = with(density) { (screen.bounds.height() - bars.top - 24 * dp).toDp() },
                peekHeight = with(density) { peekPx.toDp() },
                onHidden = ::closeSheet,
                peek = {
                    ordered.getOrNull(position)?.let { finding ->
                        PeekBar(finding, position, ordered.size, found) { step -> ordered.getOrNull(position + step)?.let(::select) }
                    }
                },
            ) {
                ResultList(
                    current,
                    selected,
                    onSelect = ::select,
                    onModel = ::switchModel,
                    connection = connection,
                    contentPadding = PaddingValues(bottom = with(density) { bars.bottom.toDp() } + 24.dp),
                    notice = { ProblemNotice(current, "Open Fineprint" to ::openApp, ::retry) },
                )
            }
        }
    }

    private fun changeMode(next: SheetMode) {
        mode = next
        val window = sheet ?: return
        val params = window.params
        when (next) {
            SheetMode.Full -> {
                clearMark()
                params.height = WindowManager.LayoutParams.MATCH_PARENT
                params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
                window.update()
            }
            SheetMode.Peek -> scope.launch {
                delay(320)
                if (mode != SheetMode.Peek) return@launch
                params.height = peekPx
                params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                window.update()
            }
            SheetMode.Hidden -> clearMark()
        }
    }

    private fun closeSheet() {
        sheet?.remove()
        sheet = null
        mode = SheetMode.Hidden
        clearMark()
        pill?.view?.visibility = android.view.View.VISIBLE
    }

    private fun select(finding: Finding) {
        selected = finding.index
        found = null
        changeMode(SheetMode.Peek)
        scope.launch {
            delay(260)
            found = reveal(finding.clause)
        }
    }

    private fun switchModel(model: Model) {
        Prefs.setModel(model)
        val current = doc ?: Checker.current.value?.let { Doc(it.clauses, it.source, -1) } ?: return
        docKey = Checker.check(current.clauses, current.source, model).key
    }

    private fun retry() {
        Checker.current.value?.let { docKey = Checker.check(it.clauses, it.source, it.model).key }
    }

    private fun openApp() {
        changeMode(SheetMode.Hidden)
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun targetRoot(): AccessibilityNodeInfo? {
        val id = doc?.windowId
        return windows.firstOrNull { it.id == id }?.root
            ?: windows.firstOrNull { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION && it.root?.packageName != packageName }?.root
    }

    private suspend fun reveal(clause: String): Boolean {
        clearMark()
        val node = withContext(Dispatchers.Default) { targetRoot()?.let { findNode(it, clause) } } ?: return false
        node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
        delay(350)
        val viewport = viewportOf(node)
        val limit = screen.bounds.height() - peekPx
        repeat(2) {
            node.refresh()
            val r = Rect().also(node::getBoundsInScreen)
            val room = limit - viewport.top
            val wanted = viewport.top + ((room - min(r.height(), room)) / 3).coerceAtLeast((16 * dp).toInt())
            if (abs(r.top - wanted) < 24 * dp) return@repeat
            scrollBy((r.top - wanted).toFloat(), viewport, limit)
            delay(200)
        }
        if (mode != SheetMode.Peek) return true
        marked = node
        showMark()
        return true
    }

    private fun viewportOf(node: AccessibilityNodeInfo): Rect {
        var current: AccessibilityNodeInfo? = node.parent
        while (current != null) {
            if (current.isScrollable) return Rect().also(current::getBoundsInScreen)
            current = current.parent
        }
        return Rect(0, bars.top, screen.bounds.width(), screen.bounds.height())
    }

    private suspend fun scrollBy(distance: Float, viewport: Rect, limit: Int) {
        var left = distance
        val x = viewport.exactCenterX()
        val low = viewport.top + 40 * dp
        val high = limit - 40 * dp
        while (abs(left) > 4 * dp) {
            val step = left.coerceIn(-(high - low), high - low)
            val from = if (step > 0) high else low
            swipe(x, from, from - step)
            left -= step
        }
    }

    private suspend fun swipe(x: Float, from: Float, to: Float) {
        val move = GestureDescription.StrokeDescription(Path().apply { moveTo(x, from); lineTo(x, to) }, 0, 260, true)
        val hold = move.continueStroke(Path().apply { moveTo(x, to); lineTo(x, to + 1) }, 0, 140, false)
        gesture(move)
        gesture(hold)
    }

    private suspend fun gesture(stroke: GestureDescription.StrokeDescription) = suspendCancellableCoroutine { done ->
        val sent = dispatchGesture(
            GestureDescription.Builder().addStroke(stroke).build(),
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) = done.resume(Unit)
                override fun onCancelled(gestureDescription: GestureDescription?) = done.resume(Unit)
            },
            null,
        )
        if (!sent) done.resume(Unit)
    }

    private fun findNode(root: AccessibilityNodeInfo, clause: String): AccessibilityNodeInfo? {
        for (probe in listOf(clause, clause.take(60), clause.take(32)).distinct()) {
            search(root, probe)?.let { return it }
        }
        return null
    }

    private fun search(node: AccessibilityNodeInfo, probe: String): AccessibilityNodeInfo? {
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { search(it, probe) }?.let { return it }
        }
        val own = ownText(node)
        return node.takeIf { own != null && collapse(own).contains(probe) }
    }

    private fun showMark() {
        val node = marked ?: return
        node.refresh()
        val r = Rect().also(node::getBoundsInScreen)
        val pad = (4 * dp).toInt()
        val limit = screen.bounds.height() - peekPx
        r.inset(-pad, -pad)
        r.bottom = min(r.bottom, limit)
        if (r.height() <= pad * 2) {
            highlight?.remove()
            highlight = null
            return
        }
        val window = highlight
        if (window == null) {
            val params = overlayParams(r.width(), r.height(), Gravity.TOP or Gravity.LEFT, touchable = false)
            params.x = r.left
            params.y = r.top
            highlight = OverlayWindow(this, params) { Highlight() }
        } else {
            window.params.x = r.left
            window.params.y = r.top
            window.params.width = r.width()
            window.params.height = r.height()
            window.update()
        }
    }

    private fun followMark() {
        if (marked != null) showMark()
    }

    private fun clearMark() {
        marked = null
        highlight?.remove()
        highlight = null
    }
}
