package app.fineprint

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.os.SystemClock
import androidx.compose.runtime.produceState
import java.text.NumberFormat
import kotlinx.coroutines.delay
import java.util.Locale

private val count = NumberFormat.getIntegerInstance(Locale.US)

fun plural(n: Int, word: String) = "${count.format(n)} $word${if (n == 1) "" else "s"}"

fun listSentence(items: List<String>) = when (items.size) {
    0 -> ""
    1 -> items[0]
    2 -> "${items[0]} and ${items[1]}"
    else -> items.dropLast(1).joinToString(", ") + ", and " + items.last()
}

fun duration(millis: Long): String {
    val seconds = millis / 1000
    return if (seconds < 60) "${seconds}s" else "${seconds / 60}:${"%02d".format(seconds % 60)}"
}

fun headline(check: Check): String {
    val flags = check.findings.flatMap { it.verdict.flags }.toSet()
    val high = flags.count { it.severity == Severity.High }
    val medium = flags.count { it.severity == Severity.Medium }
    val text = when {
        high > 0 -> plural(high, "serious term")
        medium > 0 -> "${plural(medium, "term")} worth knowing"
        else -> "nothing unfair found"
    }
    return when {
        check.running && check.done == 0 -> "Reading the terms"
        check.running -> "So far, $text"
        else -> text.replaceFirstChar(Char::uppercase)
    }
}

fun summary(check: Check): String {
    val labels = check.findings.flatMap { it.verdict.flags }.distinct()
        .filter { it.severity != Severity.Low }.sortedBy { it.severity }.map { it.label }
    return when {
        labels.isNotEmpty() -> listSentence(labels.mapIndexed { i, label -> if (i == 0) label else label.replaceFirstChar(Char::lowercase) }) + "."
        check.running -> "Checking ${check.source} on this phone."
        else -> "No serious or notable terms in ${plural(check.done, "clause")}."
    }
}

@Composable
fun Txt(text: String, style: TextStyle, modifier: Modifier = Modifier, color: Color = LocalPalette.current.ink, maxLines: Int = Int.MAX_VALUE) {
    BasicText(text, modifier, style.copy(color = color), overflow = TextOverflow.Ellipsis, maxLines = maxLines)
}

@Composable
fun Dot(color: Color, size: Int = 7) {
    Box(Modifier.size(size.dp).background(color, CircleShape))
}

@Composable
fun Modifier.pressable(onClick: () -> Unit, scale: Float = 0.97f, role: Role = Role.Button): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val reduce = LocalReduceMotion.current
    val s by animateFloatAsState(if (pressed && !reduce) scale else 1f, Motion.small(), label = "press")
    return this.graphicsLayer { scaleX = s; scaleY = s }.clickable(source, null, role = role, onClick = onClick)
}

@Composable
fun GradeStrip(grade: Grade?, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    val reduce = LocalReduceMotion.current
    BoxWithConstraints(modifier.fillMaxWidth().height(58.dp).semantics { contentDescription = grade?.let { "Grade ${it.name}" } ?: "No grade yet" }) {
        val gap = 3.dp
        val cell = (maxWidth - gap * 4) / 5
        val index = grade?.ordinal ?: 0
        val x by animateDpAsState((cell + gap) * index - 4.dp, if (reduce) spring(stiffness = 100_000f) else Motion.bouncy(), label = "thumb")
        val thumbColor by animateColorAsState(grade?.color ?: palette.sunken, Motion.small(), label = "thumbColor")
        val thumbAlpha by animateFloatAsState(if (grade == null) 0f else 1f, Motion.small(), label = "thumbAlpha")
        Box(
            Modifier
                .offset(x = x)
                .width(cell + 8.dp)
                .fillMaxHeight()
                .alpha(thumbAlpha)
                .shadow(10.dp, RoundedCornerShape(12.dp), ambientColor = thumbColor, spotColor = thumbColor)
                .background(thumbColor, RoundedCornerShape(12.dp))
                .border(2.dp, palette.paper, RoundedCornerShape(12.dp)),
        )
        Row(Modifier.fillMaxSize().padding(vertical = 7.dp), horizontalArrangement = Arrangement.spacedBy(gap)) {
            Grade.entries.forEach { letter ->
                val active = letter == grade
                val outer = 10.dp
                val inner = 3.dp
                val shape = RoundedCornerShape(
                    topStart = if (letter == Grade.A) outer else inner,
                    bottomStart = if (letter == Grade.A) outer else inner,
                    topEnd = if (letter == Grade.E) outer else inner,
                    bottomEnd = if (letter == Grade.E) outer else inner,
                )
                val idle = grade == null
                val tint = if (idle) palette.sunken else lerp(palette.paper, letter.color, 0.17f)
                val bg by animateColorAsState(if (active) Color.Transparent else tint, Motion.small(), label = "cell")
                val ink by animateColorAsState(
                    when {
                        active -> letter.onColor
                        idle -> lerp(palette.muted, letter.color, 0.45f)
                        else -> lerp(palette.ink, letter.color, 0.62f)
                    },
                    Motion.small(), label = "ink",
                )
                val scale by animateFloatAsState(if (active && !reduce) 1.45f else 1f, Motion.bouncy(), label = "scale")
                Box(Modifier.weight(1f).fillMaxHeight().background(bg, shape), contentAlignment = Alignment.Center) {
                    Txt(letter.name, TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold), Modifier.scale(scale), ink)
                }
            }
        }
    }
}

@Composable
fun Segmented(model: Model, onModel: (Model) -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    BoxWithConstraints(
        modifier
            .height(30.dp)
            .background(palette.sunken, RoundedCornerShape(9.dp))
            .border(1.dp, palette.rule, RoundedCornerShape(9.dp))
            .padding(2.dp),
    ) {
        val half = maxWidth / 2
        val x by animateDpAsState(half * model.ordinal, Motion.large(), label = "segment")
        Box(
            Modifier.offset(x = x).width(half).fillMaxHeight()
                .shadow(1.5.dp, RoundedCornerShape(7.dp))
                .background(palette.surface, RoundedCornerShape(7.dp)),
        )
        Row(Modifier.fillMaxSize()) {
            Model.entries.forEach { option ->
                val selected = option == model
                val ink by animateColorAsState(if (selected) palette.ink else palette.muted, Motion.small(), label = "segInk")
                Box(
                    Modifier.weight(1f).fillMaxHeight().clickable(remember { MutableInteractionSource() }, null, role = Role.Tab) { onModel(option) },
                    contentAlignment = Alignment.Center,
                ) {
                    Txt(option.label, TextStyle(fontSize = 12.5.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal), color = ink)
                }
            }
        }
    }
}

@Composable
fun elapsed(check: Check): Long {
    val now by produceState(SystemClock.elapsedRealtime(), check.running, check.startedAt) {
        while (check.running) {
            value = SystemClock.elapsedRealtime()
            delay(250)
        }
    }
    return if (check.running && check.startedAt > 0) maxOf(check.millis, now - check.startedAt) else check.millis
}

@Composable
private fun Stats(check: Check) {
    val palette = LocalPalette.current
    val cells = listOf(
        "Clauses" to count.format(check.done),
        "Time" to duration(elapsed(check)),
        "Speed" to (check.tokensPerSecond?.let { "${it.toInt()} tok/s" } ?: "Cached"),
        "Invalid output" to count.format(check.invalid),
    )
    Column {
        Box(Modifier.fillMaxWidth().height(1.dp).background(palette.rule))
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            cells.forEach { (label, value) ->
                Column {
                    Txt(value, Type.stat, color = if (label == "Invalid output" && check.invalid > 0) palette.warn else palette.ink)
                    Txt(label, TextStyle(fontSize = 11.5.sp, lineHeight = 15.sp), color = palette.muted)
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(palette.rule))
    }
}

@Composable
private fun Meter(check: Check) {
    val palette = LocalPalette.current
    val progress by animateFloatAsState(if (check.total == 0) 0f else check.done.toFloat() / check.total, Motion.small(), label = "meter")
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Txt("Clause ${count.format(check.done)} of ${count.format(check.total)}", Type.label.copy(fontFeatureSettings = Type.tabular, fontSize = 12.5.sp), Modifier.weight(1f))
            val detail = listOfNotNull(
                plural(check.invalid, "invalid output").takeIf { check.invalid > 0 },
                check.tokensPerSecond?.let { "${it.toInt()} tok/s" },
                duration(elapsed(check)),
            ).joinToString("  ")
            Txt(detail, TextStyle(fontSize = 12.5.sp, fontFeatureSettings = Type.tabular), color = palette.muted)
        }
        Box(Modifier.padding(top = 10.dp).fillMaxWidth().height(3.dp).background(palette.sunken, CircleShape)) {
            Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(palette.accent, CircleShape))
        }
    }
}

@Composable
private fun Compare(check: Check, previous: Check) {
    val palette = LocalPalette.current
    val byModel = listOf(check, previous).sortedBy { it.model }
    val rows = listOf<Pair<String, (Check) -> String>>(
        "Grade" to { it.grade.name },
        "Flagged clauses" to { count.format(it.findings.size) },
        "Invalid output" to { count.format(it.invalid) },
        "Time" to { duration(it.millis) },
    )
    Column {
        Txt("Base and fine-tuned on these terms", Type.label.copy(fontSize = 12.5.sp), Modifier.padding(bottom = 6.dp))
        Row(Modifier.padding(vertical = 5.dp)) {
            Spacer(Modifier.weight(1.4f))
            byModel.forEach { Txt(it.model.label, TextStyle(fontSize = 12.sp, textAlign = TextAlign.End), Modifier.weight(1f), palette.muted) }
        }
        rows.forEach { (label, value) ->
            Box(Modifier.fillMaxWidth().height(1.dp).background(palette.rule))
            Row(Modifier.padding(vertical = 6.dp)) {
                Txt(label, Type.small, Modifier.weight(1.4f), palette.muted)
                byModel.forEach {
                    val current = it.model == check.model
                    Txt(
                        value(it),
                        Type.small.copy(fontFeatureSettings = Type.tabular, fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal, textAlign = TextAlign.End),
                        Modifier.weight(1f),
                        if (current) palette.ink else palette.muted,
                    )
                }
            }
        }
    }
}

@Composable
fun FindingCard(finding: Finding, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    val bg by animateColorAsState(if (selected) palette.pressed else Color.Transparent, Motion.small(), label = "cardBg")
    val rule by animateColorAsState(if (selected) palette.mark else palette.rule, Motion.small(), label = "rule")
    Column(
        modifier
            .fillMaxWidth()
            .pressable(onClick, 0.985f)
            .background(bg, RoundedCornerShape(10.dp))
            .padding(start = 10.dp, end = 10.dp, top = 12.dp, bottom = 13.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            finding.verdict.flags.forEach { flag ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Dot(palette.severity(flag.severity), 6)
                    Txt(flag.label, Type.label)
                }
            }
        }
        Txt(finding.verdict.plain, Type.plain)
        Txt(
            finding.clause,
            Type.quote,
            Modifier
                .padding(top = 3.dp)
                .drawBehind { drawLine(rule, Offset(1.dp.toPx(), 0f), Offset(1.dp.toPx(), size.height), 2.dp.toPx()) }
                .padding(start = 10.dp),
            palette.muted,
            if (selected) Int.MAX_VALUE else 3,
        )
    }
}

private val groupTitles = mapOf(Severity.High to "Serious", Severity.Medium to "Worth knowing", Severity.Low to "Minor")

@Composable
fun ResultList(
    check: Check,
    selected: Int?,
    onSelect: (Finding) -> Unit,
    onModel: (Model) -> Unit,
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    connection: NestedScrollConnection? = null,
    contentPadding: PaddingValues = PaddingValues(),
    notice: @Composable () -> Unit = {},
) {
    val palette = LocalPalette.current
    val reduce = LocalReduceMotion.current
    val previous = remember(check.key, check.running) { Checker.previous(check) }
    val groups = check.findings.groupBy { it.verdict.severity!! }.toSortedMap()
    val seen = remember(check.key) { HashSet<Int>().apply { check.findings.forEach { add(it.index) } } }
    LazyColumn(
        modifier.then(if (connection != null) Modifier.nestedScroll(connection) else Modifier),
        state = state,
        contentPadding = contentPadding,
    ) {
        item("head") {
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Txt(check.source, TextStyle(fontSize = 12.5.sp, lineHeight = 16.sp), Modifier.weight(1f).padding(end = 12.dp), palette.muted, 1)
                    Segmented(check.model, onModel, Modifier.width(168.dp))
                }
                GradeStrip(if (check.done == 0) null else check.grade, Modifier.padding(top = 14.dp, bottom = 10.dp))
                Txt(headline(check), Type.headline, Modifier.padding(top = 6.dp))
                Txt(summary(check), Type.small, Modifier.padding(top = 4.dp), palette.muted)
                notice()
                if (check.running) {
                    Box(Modifier.padding(top = 18.dp)) { Meter(check) }
                }
                if (check.done > 0 && !check.running) {
                    Box(Modifier.padding(top = 18.dp)) { Stats(check) }
                }
                if (previous != null && !check.running) {
                    Box(Modifier.padding(top = 18.dp)) { Compare(check, previous) }
                }
                Spacer(Modifier.height(14.dp))
            }
        }
        groups.forEach { (severity, findings) ->
            item("group-$severity") {
                Row(
                    Modifier.animateItem(placementSpec = Motion.placement).padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Dot(palette.severity(severity))
                    Txt(groupTitles.getValue(severity), Type.label.copy(fontSize = 12.5.sp))
                    Txt(count.format(findings.size), Type.label.copy(fontSize = 12.5.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = Type.tabular), color = palette.muted)
                }
            }
            findings.forEachIndexed { i, finding ->
                item(finding.index) {
                    val fresh = remember { seen.add(finding.index) }
                    val rise = remember { Animatable(if (fresh && !reduce) 1f else 0f) }
                    LaunchedEffect(Unit) { rise.animateTo(0f, Motion.large()) }
                    val lit = selected == finding.index || selected == findings.getOrNull(i - 1)?.index
                    Column(
                        Modifier
                            .animateItem(fadeInSpec = Motion.large(), placementSpec = Motion.placement)
                            .graphicsLayer { translationY = rise.value * 8.dp.toPx() }
                            .padding(horizontal = 10.dp),
                    ) {
                        if (i > 0) Box(Modifier.padding(horizontal = 10.dp).fillMaxWidth().height(1.dp).alpha(if (lit) 0f else 1f).background(palette.rule))
                        FindingCard(finding, selected == finding.index, { onSelect(finding) })
                    }
                }
            }
        }
    }
}

@Composable
fun Button(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false, enabled: Boolean = true) {
    val palette = LocalPalette.current
    Box(
        modifier
            .height(if (primary) 44.dp else 36.dp)
            .alpha(if (enabled) 1f else 0.5f)
            .then(if (enabled) Modifier.pressable(onClick) else Modifier)
            .then(if (primary) Modifier else Modifier.shadow(1.5.dp, RoundedCornerShape(10.dp)))
            .background(if (primary) palette.accent else palette.surface, RoundedCornerShape(10.dp))
            .border(1.dp, if (primary) Color.Transparent else palette.rule, RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Txt(text, TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold), color = if (primary) palette.onAccent else palette.ink, maxLines = 1)
    }
}

@Composable
fun Notice(title: String, text: String, modifier: Modifier = Modifier, action: @Composable () -> Unit = {}) {
    val palette = LocalPalette.current
    Column(
        modifier.fillMaxWidth().background(palette.sunken, RoundedCornerShape(12.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Txt(title, Type.label.copy(fontSize = 14.sp, lineHeight = 19.sp))
        Txt(text, Type.small, color = palette.muted)
        Box(Modifier.padding(top = 8.dp)) { action() }
    }
}

@Composable
fun ProblemNotice(check: Check, fix: Pair<String, () -> Unit>?, onRetry: () -> Unit) {
    when (check.problem) {
        Problem.NoModel -> Notice(
            "Add the ${check.model.label.lowercase()} model",
            "Fineprint needs the ${check.model.label.lowercase()} model file before it can check these terms.",
            Modifier.padding(top = 16.dp),
        ) { if (fix != null) Button(fix.first, fix.second) }
        Problem.Failed -> Notice(
            "The model stopped",
            "Fineprint checked ${plural(check.done, "clause")} before the model stopped.",
            Modifier.padding(top = 16.dp),
        ) { Button("Try again", onRetry) }
        null -> Unit
    }
}
