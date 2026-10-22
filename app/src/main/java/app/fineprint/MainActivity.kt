package app.fineprint

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { FineprintTheme { Home() } }
    }
}

private class Sample(val title: String, val file: String)

private val samples = listOf(Sample("Spotify Terms of Use", "spotify.txt"), Sample("Tinder Terms of Use", "tinder.txt"))

private val gradeMeanings = mapOf(
    Grade.A to "A means none of these terms.",
    Grade.B to "B means only terms worth knowing, such as which court hears disputes.",
    Grade.C to "C means one serious term, such as giving up your right to sue.",
    Grade.D to "D means two serious terms.",
    Grade.E to "E means three or more serious terms.",
)

private fun serviceEnabled(context: Context): Boolean {
    val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
    val name = ComponentName(context, FineprintService::class.java)
    return enabled.split(':').any { ComponentName.unflattenFromString(it) == name }
}

private fun openServiceSettings(context: Context) {
    val name = ComponentName(context, FineprintService::class.java).flattenToString()
    val details = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS").putExtra(Intent.EXTRA_COMPONENT_NAME, name)
    runCatching { context.startActivity(details) }.onFailure { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
}

private fun sizeLabel(file: File) = "${file.length() / 1_000_000} MB"

@Composable
private fun Home() {
    val context = LocalContext.current
    val palette = LocalPalette.current
    val scope = rememberCoroutineScope()
    val model by Prefs.model.collectAsState()
    val auto by Prefs.auto.collectAsState()
    val last by Checker.current.collectAsState()
    var service by remember { mutableStateOf(serviceEnabled(context)) }
    var installed by remember { mutableIntStateOf(0) }
    var copying by remember { mutableStateOf<Pair<Model, Float>?>(null) }
    var importFor by remember { mutableStateOf(Model.Base) }
    var importError by remember { mutableStateOf<String?>(null) }
    var showing by remember { mutableStateOf<Pair<List<String>, String>?>(null) }
    var pasted by remember { mutableStateOf("") }
    var legend by remember { mutableStateOf(Grade.C) }

    LifecycleResumeEffect(Unit) {
        service = serviceEnabled(context)
        onPauseOrDispose {}
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val target = importFor
        scope.launch {
            importError = null
            copying = target to 0f
            importError = importModel(context, uri, target) { copying = target to it }
            copying = null
            installed++
        }
    }

    fun import(model: Model) {
        importFor = model
        picker.launch(arrayOf("*/*"))
    }

    fun choose(next: Model) {
        if (!Checker.installed(next)) import(next) else Prefs.setModel(next)
    }

    val baseFile = remember(installed) { Checker.file(Model.Base).takeIf(File::exists) }
    val tunedFile = remember(installed) { Checker.file(Model.Tuned).takeIf(File::exists) }
    val offline = remember { context.checkSelfPermission(Manifest.permission.INTERNET) == PackageManager.PERMISSION_DENIED }

    Box(Modifier.fillMaxSize().background(palette.paper)) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .imePadding()
                .padding(horizontal = 20.dp),
        ) {
            Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Box(Modifier.size(26.dp).background(palette.focus, RoundedCornerShape(7.dp)), contentAlignment = Alignment.Center) {
                    Txt("F", TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold), color = palette.onAccent)
                }
                Txt("Fineprint", TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.1).sp))
            }

            Txt("What are you agreeing to?", Type.title, Modifier.padding(top = 32.dp))
            Txt(
                "Fineprint reads the terms of service you open in any app and grades them from A to E. The model runs on this phone.",
                Type.body,
                Modifier.padding(top = 8.dp),
                palette.muted,
            )
            Box(Modifier.padding(top = 20.dp)) {
                GradeStrip(legend)
                Row(Modifier.fillMaxWidth().height(58.dp)) {
                    Grade.entries.forEach { grade ->
                        Box(Modifier.weight(1f).height(58.dp).pressable({ legend = grade }, 1f, Role.RadioButton))
                    }
                }
            }
            Txt(gradeMeanings.getValue(legend), Type.small, Modifier.padding(top = 8.dp), palette.muted)

            Section("Set up")
            Card {
                Step(
                    1,
                    done = baseFile != null,
                    title = "Add the model",
                    text = when {
                        copying?.first == Model.Base -> "Copying ${(copying!!.second * 100).toInt()}%"
                        baseFile != null -> "LFM2.5 1.2B, ${sizeLabel(baseFile)}, stored on this phone"
                        else -> "Pick the .gguf model file. Fineprint copies it into its own storage."
                    },
                    action = if (baseFile == null) "Add" else "Replace",
                    primary = baseFile == null,
                    busy = copying != null,
                ) { import(Model.Base) }
                Rule()
                Step(
                    2,
                    done = service,
                    title = "Check terms in any app",
                    text = if (service) "A grade appears on the edge of the screen when you open terms." else "Turn on Fineprint in Accessibility settings. It reads the screen only to spot terms.",
                    action = if (service) "Settings" else "Turn on",
                    primary = baseFile != null && !service,
                ) { openServiceSettings(context) }
            }
            importError?.let { Txt(it, Type.small, Modifier.padding(top = 8.dp), palette.warn) }

            Section("Try it")
            Card {
                samples.forEachIndexed { i, sample ->
                    if (i > 0) Rule()
                    val clauses = remember { splitClauses(context.assets.open(sample.file).bufferedReader().readText()) }
                    Row(
                        Modifier.fillMaxWidth().pressable({ showing = clauses to sample.title }, 0.985f).padding(horizontal = 14.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Txt(sample.title, Type.plain)
                            Txt("Excerpt, ${plural(clauses.size, "clause")}", Type.small, color = palette.muted)
                        }
                        Icon(Glyph.Next, palette.muted, 16)
                    }
                }
            }
            val pastedClauses = remember(pasted) { splitClauses(pasted) }
            Box(
                Modifier
                    .padding(top = 12.dp)
                    .fillMaxWidth()
                    .heightIn(min = 112.dp)
                    .background(palette.surface, RoundedCornerShape(12.dp))
                    .border(1.dp, palette.rule, RoundedCornerShape(12.dp))
                    .padding(14.dp),
            ) {
                BasicTextField(
                    pasted,
                    { pasted = it },
                    Modifier.fillMaxWidth().heightIn(min = 84.dp),
                    textStyle = Type.small.copy(color = palette.ink),
                    cursorBrush = SolidColor(palette.focus),
                )
                if (pasted.isEmpty()) Txt("Paste terms here", Type.small, color = palette.muted)
            }
            Button(
                if (pastedClauses.isEmpty()) "Check" else "Check ${plural(pastedClauses.size, "clause")}",
                { showing = pastedClauses to "Pasted text" },
                Modifier.padding(top = 10.dp).fillMaxWidth(),
                primary = true,
                enabled = pastedClauses.isNotEmpty(),
            )

            Section("Settings")
            Card {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Txt("Model", Type.plain)
                            Txt(
                                if (tunedFile == null) "Add the fine-tuned model to compare." else "Switch to compare the two models.",
                                Type.small,
                                color = palette.muted,
                            )
                        }
                        Segmented(model, ::choose, Modifier.width(176.dp))
                    }
                }
                Rule()
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Txt("Fine-tuned model", Type.plain)
                        Txt(
                            when {
                                copying?.first == Model.Tuned -> "Copying ${(copying!!.second * 100).toInt()}%"
                                tunedFile != null -> sizeLabel(tunedFile)
                                else -> "Not added"
                            },
                            Type.small.copy(fontFeatureSettings = Type.tabular),
                            color = palette.muted,
                        )
                    }
                    Button(if (tunedFile == null) "Add" else "Replace", { import(Model.Tuned) }, enabled = copying == null)
                }
                Rule()
                Row(Modifier.fillMaxWidth().pressable({ Prefs.setAuto(!auto) }, 0.99f, Role.Switch).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Txt("Check terms automatically", Type.plain)
                        Txt("Grade terms as soon as you open them.", Type.small, color = palette.muted)
                    }
                    Toggle(auto)
                }
            }
            last?.takeIf { it.done > 0 }?.let { check ->
                Card(Modifier.padding(top = 12.dp)) {
                    Column(Modifier.padding(14.dp)) {
                        Txt("Latest check", Type.plain)
                        Txt("${check.source}, ${check.model.label.lowercase()} model, grade ${check.grade.name}", Type.small, color = palette.muted)
                        Spacer(Modifier.height(10.dp))
                        LatestStats(check)
                    }
                }
            }

            Section("Privacy")
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.padding(top = 7.dp)) { Dot(if (offline) palette.focus else palette.warn) }
                Column {
                    Txt(if (offline) "No internet access" else "Internet access is on", Type.plain)
                    Txt(
                        "Fineprint does not ask for the internet permission, so Android blocks it from the network. The terms you check never leave this phone.",
                        Type.small,
                        color = palette.muted,
                    )
                }
            }
            Spacer(Modifier.height(48.dp))
        }

        showing?.let { (clauses, source) ->
            ResultSheet(clauses, source, onHidden = { showing = null }) {
                showing = null
                import(Model.entries.first { !Checker.installed(it) })
            }
        }
    }
}

@Composable
private fun LatestStats(check: Check) {
    val palette = LocalPalette.current
    val rows = buildList {
        add("Clauses" to "${check.done} of ${check.total}")
        add("Flagged clauses" to check.findings.size.toString())
        add("Invalid output" to check.invalid.toString())
        add("Time" to duration(check.millis))
        check.tokensPerSecond?.let { add("Speed" to "${it.toInt()} tok/s") }
    }
    rows.forEachIndexed { i, (label, value) ->
        if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(palette.rule))
        Row(Modifier.padding(vertical = 7.dp)) {
            Txt(label, Type.small, Modifier.weight(1f), palette.muted)
            Txt(value, Type.small.copy(fontFeatureSettings = Type.tabular, fontWeight = FontWeight.Medium), color = if (label == "Invalid output" && check.invalid > 0) palette.warn else palette.ink)
        }
    }
}

@Composable
private fun Section(title: String) {
    Txt(title, Type.label.copy(fontSize = 12.5.sp), Modifier.padding(top = 32.dp, bottom = 10.dp), LocalPalette.current.muted)
}

@Composable
private fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val palette = LocalPalette.current
    Column(
        modifier
            .fillMaxWidth()
            .shadow(1.dp, RoundedCornerShape(14.dp), ambientColor = palette.ink, spotColor = palette.ink)
            .background(palette.surface, RoundedCornerShape(14.dp))
            .border(1.dp, palette.rule, RoundedCornerShape(14.dp)),
    ) { content() }
}

@Composable
private fun Rule() {
    Box(Modifier.padding(start = 14.dp).fillMaxWidth().height(1.dp).background(LocalPalette.current.rule))
}

@Composable
private fun Step(number: Int, done: Boolean, title: String, text: String, action: String, primary: Boolean, busy: Boolean = false, onClick: () -> Unit) {
    val palette = LocalPalette.current
    val disc by animateColorAsState(if (done) palette.focus else palette.sunken, Motion.small(), label = "step")
    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(26.dp).background(disc, CircleShape), contentAlignment = Alignment.Center) {
            if (done) Icon(Glyph.Check, palette.onAccent, 14) else Txt(number.toString(), Type.label, color = palette.muted)
        }
        Column(Modifier.weight(1f).padding(start = 12.dp, end = 12.dp)) {
            Txt(title, Type.plain)
            Txt(text, Type.small.copy(fontFeatureSettings = Type.tabular), color = palette.muted)
        }
        Button(action, onClick, primary = primary, enabled = !busy)
    }
}

@Composable
private fun Toggle(on: Boolean) {
    val palette = LocalPalette.current
    val track by animateColorAsState(if (on) palette.accent else palette.rule, Motion.small(), label = "track")
    val x by animateDpAsState(if (on) 18.dp else 2.dp, Motion.large(), label = "knob")
    Box(Modifier.size(42.dp, 26.dp).background(track, CircleShape)) {
        Box(Modifier.offset(x = x, y = 2.dp).size(22.dp).shadow(1.dp, CircleShape).background(palette.surface, CircleShape))
    }
}

private suspend fun importModel(context: Context, uri: Uri, model: Model, progress: (Float) -> Unit): String? = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val size = resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { if (it.moveToFirst()) it.getLong(0) else 0L } ?: 0L
    val target = Checker.file(model)
    val partial = File(target.path + ".part")
    val copied = resolver.openInputStream(uri)?.use { input ->
        val head = ByteArray(4)
        if (input.read(head) != 4 || String(head, Charsets.US_ASCII) != "GGUF") return@withContext "This file is not a GGUF model."
        partial.outputStream().use { output ->
            output.write(head)
            val buffer = ByteArray(1 shl 20)
            var total = 4L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
                total += read
                if (size > 0) withContext(Dispatchers.Main) { progress(total.toFloat() / size) }
            }
        }
        true
    } ?: false
    if (!copied) return@withContext "Fineprint could not read this file."
    Checker.forget(model)
    partial.renameTo(target)
    null
}
