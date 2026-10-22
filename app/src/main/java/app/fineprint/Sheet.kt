package app.fineprint

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

enum class SheetMode { Hidden, Full, Peek }

class SheetScope(val connection: NestedScrollConnection, val peeking: Float)

@Composable
fun Sheet(
    mode: SheetMode,
    onMode: (SheetMode) -> Unit,
    height: Dp,
    peekHeight: Dp,
    onHidden: () -> Unit = {},
    peek: @Composable () -> Unit = {},
    content: @Composable SheetScope.() -> Unit,
) {
    val palette = LocalPalette.current
    val density = LocalDensity.current
    val full = with(density) { height.toPx() }
    val peekTop = full - with(density) { peekHeight.toPx() }
    var offset by remember { mutableFloatStateOf(full) }
    val scope = rememberCoroutineScope()
    val job = remember { arrayOfNulls<Job>(1) }
    val settled = remember { arrayOf(SheetMode.Hidden) }
    val currentMode by rememberUpdatedState(mode)
    val currentOnMode by rememberUpdatedState(onMode)
    val currentOnHidden by rememberUpdatedState(onHidden)

    fun targetOf(mode: SheetMode) = when (mode) {
        SheetMode.Hidden -> full
        SheetMode.Full -> 0f
        SheetMode.Peek -> peekTop
    }

    fun settle(to: SheetMode, velocity: Float = 0f) {
        job[0]?.cancel()
        settled[0] = to
        val spec: FiniteAnimationSpec<Float> = if (to == SheetMode.Hidden) Motion.exit() else Motion.large()
        job[0] = scope.launch {
            animate(offset, targetOf(to), velocity, spec) { value, _ -> offset = value }
            if (to == SheetMode.Hidden) currentOnHidden()
        }
        if (to != currentMode) currentOnMode(to)
    }

    fun release(velocity: Float) {
        val to = when (currentMode) {
            SheetMode.Peek -> when {
                velocity < -800f || offset < peekTop - 60f -> SheetMode.Full
                velocity > 800f || offset > peekTop + 60f -> SheetMode.Hidden
                else -> SheetMode.Peek
            }
            else -> if (velocity > 1200f || (offset > full * 0.28f && velocity > -300f)) SheetMode.Hidden else SheetMode.Full
        }
        settle(to, velocity)
    }

    fun drag(delta: Float) {
        job[0]?.cancel()
        offset = (offset + delta).coerceIn(0f, full)
    }

    LaunchedEffect(mode) { if (settled[0] != mode) settle(mode) }

    val connection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y < 0 && offset > 0f && source == NestedScrollSource.UserInput) {
                    val before = offset
                    drag(available.y)
                    return Offset(0f, offset - before)
                }
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (available.y > 0 && source == NestedScrollSource.UserInput) {
                    drag(available.y)
                    return available
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (offset > 0f) {
                    release(available.y)
                    return available
                }
                return Velocity.Zero
            }
        }
    }

    val peeking by animateFloatAsState(if (mode == SheetMode.Peek) 1f else 0f, Motion.small(), label = "peek")
    val scrim = ((peekTop - offset) / peekTop).coerceIn(0f, 1f)

    Box(Modifier.fillMaxSize()) {
        if (scrim > 0f) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.34f * scrim))
                    .clickable(remember { MutableInteractionSource() }, null) { settle(SheetMode.Hidden) },
            )
        }
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .wrapContentHeight(Alignment.Bottom, unbounded = true)
                .height(height)
                .offset { IntOffset(0, offset.roundToInt()) }
                .shadow(16.dp, RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp), ambientColor = Color.Black, spotColor = Color.Black)
                .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                .background(palette.paper),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .draggable(rememberDraggableState(::drag), Orientation.Vertical, onDragStopped = { release(it) })
                    .padding(top = 8.dp, bottom = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(36.dp, 4.dp).background(palette.rule, CircleShape))
            }
            Box(Modifier.weight(1f)) {
                SheetScope(connection, peeking).content()
                if (peeking > 0f) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .alpha(peeking)
                            .background(palette.paper)
                            .draggable(rememberDraggableState(::drag), Orientation.Vertical, onDragStopped = { release(it) })
                            .clickable(remember { MutableInteractionSource() }, null) { settle(SheetMode.Full) },
                    ) { peek() }
                }
            }
        }
    }
}
