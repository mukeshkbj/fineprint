package app.fineprint

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class CheckActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT) ?: intent.getCharSequenceExtra(Intent.EXTRA_TEXT) ?: ""
        val source = if (intent.action == Intent.ACTION_SEND) "Shared text" else "Selected text"
        val clauses = splitClauses(text.toString())
        setContent {
            FineprintTheme {
                ResultSheet(clauses, source, onHidden = ::finish) {
                    startActivity(Intent(this, MainActivity::class.java))
                    finish()
                }
            }
        }
    }
}

@Composable
fun ResultSheet(clauses: List<String>, source: String, onHidden: () -> Unit, addModel: () -> Unit) {
    var mode by remember { mutableStateOf(SheetMode.Full) }
    var selected by remember { mutableStateOf<Int?>(null) }
    val current by Checker.current.collectAsState()
    remember(clauses) { if (clauses.isNotEmpty()) Checker.check(clauses, source) }
    BackHandler(mode != SheetMode.Hidden) { mode = SheetMode.Hidden }
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    BoxWithConstraints {
        Sheet(mode, { mode = it }, height = maxHeight - top - 16.dp, peekHeight = 0.dp, onHidden = onHidden) {
            val check = current?.takeIf { it.clauses == clauses }
            if (check == null) {
                Notice(
                    "Nothing to check",
                    "Fineprint checks whole sentences. Select a longer passage of the terms and try again.",
                    Modifier.padding(horizontal = 20.dp),
                )
                return@Sheet
            }
            ResultList(
                check,
                selected,
                onSelect = { selected = it.index.takeIf { index -> index != selected } },
                onModel = {
                    Prefs.setModel(it)
                    Checker.check(clauses, source, it)
                },
                connection = connection,
                contentPadding = PaddingValues(bottom = bottom + 24.dp),
                notice = { ProblemNotice(check, "Open Fineprint" to addModel) { Checker.check(clauses, source, check.model) } },
            )
        }
    }
}
