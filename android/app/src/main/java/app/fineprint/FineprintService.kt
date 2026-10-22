package app.fineprint

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.view.View
import android.view.accessibility.AccessibilityWindowInfo
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
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
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

private class Doc(val clauses: List<String>, val source: String, val windowId: Int, val app: CharSequence?)

class FineprintService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + AndroidUiDispatcher.Main)
    private var scanJob: Job? = null
    private var scanDue = 0L
    private var firstEvent = 0L
    private var retries = 0
    private var doc: Doc? = null
    private var docKey by mutableStateOf<String?>(null)
    private var dismissed: Pair<String, Int>? = null
    private var detectedAt = 0L

    private var pill: OverlayWindow? = null
    private var sheet: OverlayWindow? = null
    private var target: OverlayWindow? = null
    private var highlight: OverlayWindow? = null
    private var marked: AccessibilityNodeInfo? = null
    private var markTop = 0
    private var markLines: List<Rect> = emptyList()
    private val markShape = mutableStateOf<List<Rect>>(emptyList())

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
        if (event.packageName == packageName) {
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && event.className?.endsWith("Activity") == true) {
                scanJob?.cancel()
                doc = null
                hidePill()
            }
            return
        }
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (sheet != null && event.packageName != doc?.app && event.packageName != "com.android.systemui") changeMode(SheetMode.Hidden)
                retries = 0
                schedule(450)
            }
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                followMark()
                schedule(900)
            }
            else -> if (doc?.windowId != event.windowId || System.currentTimeMillis() - detectedAt < 10_000 || event.source?.viewIdResourceName?.endsWith("url_bar") == true) schedule(900)
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
        val result = withContext(Dispatchers.Default) {
            val root = rootInActiveWindow ?: return@withContext null
            if (root.packageName == packageName) return@withContext Unit
            val clauses = splitClauses(readBlocks(landmark(root, "main") ?: landmark(root, "article") ?: root).joinToString("\n"))
            if (looksLikeTerms(clauses)) Doc(clauses, sourceOf(root), root.windowId, root.packageName) else null
        }
        if (result == Unit) return
        val next = result as Doc?
        if (next == null) {
            doc = null
            hidePill()
            if (retries++ < 12) schedule(1500)
            return
        }
        retries = 0
        if (doc?.windowId != next.windowId || doc?.source != next.source) detectedAt = System.currentTimeMillis()
        doc = next
        val check = Checker.check(next.clauses, next.source)
        docKey = check.key
        if (dismissed != next.source to next.windowId) showPill()
    }

    private fun landmark(node: AccessibilityNodeInfo, role: String): AccessibilityNodeInfo? {
        if (node.extras.getString("AccessibilityNodeInfo.chromeRole") == role) return node
        for (i in 0 until node.childCount) node.getChild(i)?.let { landmark(it, role) }?.let { return it }
        return null
    }

    private fun inlineText(node: AccessibilityNodeInfo): String? {
        val role = node.extras.getString("AccessibilityNodeInfo.chromeRole")
        if (role == "listMarker") return ""
        val inline = node.extras.getString("AccessibilityNodeInfo.cssDisplay")?.startsWith("inline") == true || role == "staticText"
        if (!inline) return null
        if (node.childCount == 0) return leafText(node)
        return buildString {
            for (i in 0 until node.childCount) append(node.getChild(i)?.let(::inlineText) ?: return null)
        }
    }

    private fun leafText(node: AccessibilityNodeInfo) = node.text?.toString().orEmpty()

    private fun readBlocks(node: AccessibilityNodeInfo, out: MutableList<String> = mutableListOf()): List<String> {
        if (node.childCount == 0) {
            leafText(node).takeIf { it.isNotBlank() }?.let { out += it }
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
        if (node.childCount == 0) return leafText(node)
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
        if (target == null) addTarget()
        val params = overlayParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END)
        params.y = (screen.bounds.height() * 0.36f).toInt()
        val gone = mutableStateOf(false).also { leaving = it }
        pill = OverlayWindow(this, params) {
            val check by Checker.current.collectAsState()
            val current = check?.takeIf { it.key == docKey } ?: return@OverlayWindow
            var expanded by remember(current.key) { mutableStateOf(true) }
            val view = LocalView.current
            LaunchedEffect(current.key, current.running) {
                if (current.running) {
                    expanded = true
                } else {
                    if (current.problem == null) view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    delay(3500)
                    expanded = false
                }
            }
            Pill(current, expanded && !dragging, gone.value, Modifier.pointerInteropFilter { onPillTouch(it) })
        }
    }

    private fun hidePill() {
        val window = pill ?: return
        val dock = target
        pill = null
        target = null
        leaving.value = true
        scope.launch {
            delay(220)
            window.remove()
            dock?.remove()
        }
    }

    private var downX = 0f
    private var downY = 0f
    private var dragging by mutableStateOf(false)
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
                }
                if (dragging) {
                    var left = event.rawX - width / 2f
                    var top = event.rawY - height / 2f
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
                hot = false
                dragging = false
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

    private fun addTarget() {
        val size = (132 * dp).toInt()
        val params = overlayParams(size, size, Gravity.TOP or Gravity.LEFT, touchable = false)
        val (cx, cy) = targetCenter
        params.x = (cx - size / 2).toInt()
        params.y = (cy - size / 2).toInt()
        target = OverlayWindow(this, params) { DismissTarget(dragging, hot) }
    }

    private fun dismissPill() {
        dismissed = doc?.let { it.source to it.windowId }
        hidePill()
    }

    private fun openSheet() {
        if (sheet != null) return
        pill?.view?.visibility = View.INVISIBLE
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
                window.update()
            }
            SheetMode.Peek -> scope.launch {
                delay(320)
                if (mode != SheetMode.Peek) return@launch
                params.height = peekPx
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
        pill?.view?.visibility = View.VISIBLE
        schedule(200)
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
        val current = doc ?: Checker.current.value?.let { Doc(it.clauses, it.source, -1, null) } ?: return
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
            ?: windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.root?.packageName != packageName }?.root
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
        node.refresh()
        marked = node
        markTop = topOf(node)
        markLines = withContext(Dispatchers.Default) { linesOf(node, clause) }.ifEmpty { listOf(Rect().also(node::getBoundsInScreen)) }
        showMark(0)
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

    private fun topOf(node: AccessibilityNodeInfo) =
        node.extras.getInt("AccessibilityNodeInfo.unclippedTop", Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE } ?: Rect().also(node::getBoundsInScreen).top

    private fun leavesOf(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> =
        if (node.childCount == 0) listOf(node) else (0 until node.childCount).mapNotNull(node::getChild).flatMap(::leavesOf)

    private suspend fun linesOf(node: AccessibilityNodeInfo, clause: String): List<Rect> {
        val leaves = leavesOf(node)
        val raw = leaves.joinToString("") { it.text?.toString().orEmpty() }
        val range = rawRange(raw, clause) ?: return emptyList()
        repeat(2) {
            val boxes = mutableListOf<RectF>()
            var coarse = false
            var offset = 0
            for (leaf in leaves) {
                val length = leaf.text?.length ?: 0
                val start = maxOf(range.first, offset)
                val end = minOf(range.last + 1, offset + length)
                if (start < end) {
                    val args = Bundle().apply {
                        putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX, start - offset)
                        putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH, end - start)
                    }
                    leaf.refreshWithExtraData(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY, args)
                    val located = leaf.extras.getParcelableArray(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY, RectF::class.java)
                        ?.filterNotNull()?.filter { it.width() > 0 && it.height() > 0 }.orEmpty()
                    if (located.size > 1 && located.distinct().size == 1) coarse = true
                    boxes += located
                }
                offset += length
            }
            if (!coarse) return merge(boxes)
            delay(200)
        }
        return emptyList()
    }

    private fun merge(boxes: List<RectF>): List<Rect> {
        val typical = boxes.map { it.height() }.sorted().getOrElse(boxes.size / 2) { 0f }
        val lines = mutableListOf<RectF>()
        for (box in boxes.filter { it.height() <= typical * 1.6f }.sortedWith(compareBy({ it.centerY() }, { it.left }))) {
            val line = lines.lastOrNull()?.takeIf { abs(it.centerY() - box.centerY()) < box.height() / 2 }
            if (line != null) line.union(box) else lines += RectF(box)
        }
        return lines.map { Rect(it.left.toInt(), it.top.toInt(), it.right.toInt(), it.bottom.toInt()) }
    }

    private fun rawRange(raw: String, clause: String): IntRange? {
        val collapsed = StringBuilder()
        val index = ArrayList<Int>()
        var space = false
        raw.forEachIndexed { i, c ->
            if (c.isWhitespace() || c == '\uFEFF') {
                space = true
            } else {
                if (space && collapsed.isNotEmpty()) {
                    collapsed.append(' ')
                    index += i
                }
                space = false
                collapsed.append(c)
                index += i
            }
        }
        val at = collapsed.indexOf(clause).takeIf { it >= 0 } ?: return null
        return index[at]..index[at + clause.length - 1]
    }

    private fun showMark(shift: Int) {
        val pad = (3 * dp).toInt()
        val top = bars.top
        val limit = screen.bounds.height() - peekPx
        val lines = markLines.map { Rect(it).apply { offset(0, shift); inset(-pad, -pad / 2) } }
            .filter { it.bottom > top && it.top < limit }
            .map { Rect(it.left, maxOf(it.top, top), it.right, minOf(it.bottom, limit)) }
        if (lines.isEmpty()) {
            highlight?.remove()
            highlight = null
            return
        }
        val area = Rect(lines.first()).apply { lines.forEach(::union) }
        markShape.value = lines.map { Rect(it).apply { offset(-area.left, -area.top) } }
        val window = highlight
        if (window == null) {
            val params = overlayParams(area.width(), area.height(), Gravity.TOP or Gravity.LEFT, touchable = false)
            params.x = area.left
            params.y = area.top
            highlight = OverlayWindow(this, params) { Highlight(markShape.value) }
        } else {
            window.params.x = area.left
            window.params.y = area.top
            window.params.width = area.width()
            window.params.height = area.height()
            window.update()
        }
    }

    private fun followMark() {
        val node = marked ?: return
        node.refresh()
        showMark(topOf(node) - markTop)
    }

    private fun clearMark() {
        marked = null
        highlight?.remove()
        highlight = null
    }
}
