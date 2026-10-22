package app.fineprint

import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

class OverlayWindow(
    context: Context,
    val params: WindowManager.LayoutParams,
    onBack: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) : LifecycleOwner, SavedStateRegistryOwner {
    private val windows = context.getSystemService(WindowManager::class.java)
    private val registry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)
    private val back = onBack?.let { OnBackInvokedCallback(it) }
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry get() = savedState.savedStateRegistry
    val view = ComposeView(context)

    init {
        savedState.performRestore(null)
        registry.currentState = Lifecycle.State.RESUMED
        view.setViewTreeLifecycleOwner(this)
        view.setViewTreeSavedStateRegistryOwner(this)
        view.setContent { FineprintTheme(content) }
        windows.addView(view, params)
        if (back != null) view.post { view.findOnBackInvokedDispatcher()?.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, back) }
    }

    fun update() {
        if (view.isAttachedToWindow) windows.updateViewLayout(view, params)
    }

    fun remove() {
        registry.currentState = Lifecycle.State.DESTROYED
        windows.removeView(view)
    }
}

fun overlayParams(width: Int, height: Int, gravity: Int, touchable: Boolean = true, focusable: Boolean = false) =
    WindowManager.LayoutParams(
        width,
        height,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            (if (focusable) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) or
            (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
        PixelFormat.TRANSLUCENT,
    ).apply {
        this.gravity = gravity
        fitInsetsTypes = 0
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    }

fun pillText(check: Check): String {
    val high = check.findings.count { it.verdict.severity == Severity.High }
    val medium = check.findings.count { it.verdict.severity == Severity.Medium }
    return when {
        check.problem == Problem.NoModel -> "Add the model"
        check.problem != null -> "Stopped"
        check.running -> "${check.done} of ${check.total}"
        high > 0 -> "$high serious"
        medium > 0 -> "$medium worth knowing"
        else -> "Nothing unfair"
    }
}

@Composable
fun Pill(check: Check, expanded: Boolean, leaving: Boolean, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    val reduce = LocalReduceMotion.current
    val grade = check.grade.takeIf { check.done > 0 && check.problem == null }
    val enter = remember(check.key) { Animatable(0f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, Motion.large()) }
    val exit by animateFloatAsState(if (leaving) 0f else 1f, Motion.exit(), label = "exit")
    val presence = enter.value * exit
    val disc by animateColorAsState(if (check.running || grade == null) palette.sunken else grade.color, Motion.small(), label = "disc")
    val ink by animateColorAsState(if (check.running || grade == null) grade?.color ?: palette.muted else grade.onColor, Motion.small(), label = "discInk")
    val progress by animateFloatAsState(if (check.total == 0) 0f else check.done.toFloat() / check.total, Motion.small(), label = "ring")
    Row(
        modifier
            .padding(10.dp)
            .graphicsLayer {
                alpha = presence.coerceIn(0f, 1f)
                val s = if (reduce) 1f else 0.9f + 0.1f * presence
                scaleX = s
                scaleY = s
            }
            .shadow(10.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.5f), spotColor = Color.Black.copy(alpha = 0.5f))
            .background(palette.surface, CircleShape)
            .border(1.dp, palette.rule, CircleShape)
            .padding(5.dp)
            .semantics { contentDescription = "Fineprint, ${grade?.let { "grade ${it.name}, " } ?: ""}${pillText(check)}" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.fillMaxSize().background(disc, CircleShape))
            if (check.running) {
                val arc = grade?.color ?: palette.accent
                val track = palette.rule
                Canvas(Modifier.fillMaxSize().padding(2.dp)) {
                    val stroke = 2.5.dp.toPx()
                    val inset = stroke / 2
                    val box = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke)
                    drawArc(track, 0f, 360f, false, Offset(inset, inset), box, style = Stroke(stroke))
                    drawArc(arc, -90f, 360f * progress, false, Offset(inset, inset), box, style = Stroke(stroke, cap = StrokeCap.Round))
                }
            }
            Txt(grade?.name ?: "F", TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold), color = if (grade == null) palette.muted else ink)
        }
        AnimatedVisibility(
            expanded,
            enter = fadeIn(Motion.small()) + expandHorizontally(Motion.large(), Alignment.Start),
            exit = fadeOut(Motion.exit()) + shrinkHorizontally(Motion.exit(), Alignment.Start),
        ) {
            Txt(
                pillText(check),
                TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = Type.tabular),
                Modifier.padding(start = 9.dp, end = 10.dp),
            )
        }
    }
}

@Composable
fun DismissTarget(hot: Boolean) {
    val palette = LocalPalette.current
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, Motion.small()) }
    val scale by animateFloatAsState(if (hot) 1.18f else 1f, Motion.bouncy(), label = "hot")
    val bg by animateColorAsState(if (hot) palette.ink else palette.ink.copy(alpha = 0.78f), Motion.small(), label = "hotBg")
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .graphicsLayer {
                    alpha = enter.value
                    translationY = (1f - enter.value) * 16.dp.toPx()
                }
                .scale(scale)
                .size(56.dp)
                .shadow(8.dp, CircleShape)
                .background(bg, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Glyph.Close, palette.paper, 18)
        }
    }
}

@Composable
fun Highlight() {
    val palette = LocalPalette.current
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, Motion.large()) }
    Box(
        Modifier
            .fillMaxSize()
            .alpha(enter.value)
            .background(palette.mark.copy(alpha = if (palette.dark) 0.28f else 0.34f), RoundedCornerShape(4.dp)),
    )
}

enum class Glyph { Close, Previous, Next, Check }

@Composable
fun Icon(glyph: Glyph, color: Color, size: Int = 16, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size.dp)) {
        val w = this.size.width
        val stroke = Stroke(1.75.dp.toPx(), cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
        val path = androidx.compose.ui.graphics.Path()
        when (glyph) {
            Glyph.Close -> {
                path.moveTo(w * 0.2f, w * 0.2f); path.lineTo(w * 0.8f, w * 0.8f)
                path.moveTo(w * 0.8f, w * 0.2f); path.lineTo(w * 0.2f, w * 0.8f)
            }
            Glyph.Previous -> {
                path.moveTo(w * 0.62f, w * 0.2f); path.lineTo(w * 0.32f, w * 0.5f); path.lineTo(w * 0.62f, w * 0.8f)
            }
            Glyph.Next -> {
                path.moveTo(w * 0.38f, w * 0.2f); path.lineTo(w * 0.68f, w * 0.5f); path.lineTo(w * 0.38f, w * 0.8f)
            }
            Glyph.Check -> {
                path.moveTo(w * 0.18f, w * 0.52f); path.lineTo(w * 0.4f, w * 0.74f); path.lineTo(w * 0.82f, w * 0.28f)
            }
        }
        drawPath(path, color, style = stroke)
    }
}

@Composable
fun PeekBar(
    finding: Finding,
    position: Int,
    total: Int,
    found: Boolean?,
    onStep: (Int) -> Unit,
) {
    val palette = LocalPalette.current
    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val flag = finding.verdict.flags.first()
            val more = finding.verdict.flags.size - 1
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Dot(palette.severity(flag.severity), 6)
                Txt(flag.label, Type.label, maxLines = 1)
                if (more > 0) Txt("+$more", Type.label.copy(fontWeight = FontWeight.Medium, fontFeatureSettings = Type.tabular), color = palette.muted)
            }
            Txt("${position + 1} of $total", TextStyle(fontSize = 12.5.sp, fontFeatureSettings = Type.tabular), Modifier.padding(horizontal = 10.dp), palette.muted)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StepButton(Glyph.Previous, position > 0) { onStep(-1) }
                StepButton(Glyph.Next, position < total - 1) { onStep(1) }
            }
        }
        Txt(finding.verdict.plain, Type.plain, maxLines = 2)
        Txt(
            when (found) {
                null -> "Finding it on the page"
                false -> "Not found on the page. It may have changed since the check."
                true -> "Highlighted on the page"
            },
            TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
            color = if (found == false) palette.warn else palette.muted,
            maxLines = 1,
        )
    }
}

@Composable
private fun StepButton(glyph: Glyph, enabled: Boolean, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Box(
        Modifier
            .size(34.dp)
            .then(if (enabled) Modifier.pressable(onClick, 0.92f) else Modifier)
            .background(palette.sunken, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(glyph, if (enabled) palette.ink else palette.rule, 16)
    }
}
