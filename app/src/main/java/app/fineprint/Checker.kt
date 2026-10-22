package app.fineprint

import ai.liquid.leap.GenerationOptions
import ai.liquid.leap.LeapClient
import ai.liquid.leap.ModelRunner
import ai.liquid.leap.ModelLoadingOptions
import ai.liquid.leap.message.MessageResponse
import android.content.Context
import android.os.SystemClock
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

enum class Model(val label: String) {
    Base("Base"),
    Tuned("Fine-tuned");

    val other get() = entries[1 - ordinal]
}

enum class Problem { NoModel, Failed }

data class Finding(val index: Int, val clause: String, val verdict: Verdict)

data class Check(
    val key: String,
    val source: String,
    val model: Model,
    val clauses: List<String>,
    val done: Int = 0,
    val findings: List<Finding> = emptyList(),
    val invalid: Int = 0,
    val tokens: Long = 0,
    val decodeSeconds: Double = 0.0,
    val millis: Long = 0,
    val startedAt: Long = 0,
    val problem: Problem? = null,
) {
    val total get() = clauses.size
    val running get() = problem == null && done < total
    val grade get() = gradeOf(findings.flatMap { it.verdict.flags })
    val tokensPerSecond get() = if (decodeSeconds > 0) tokens / decodeSeconds else null
}

private class Answer(val verdict: Verdict?, val tokens: Long, val seconds: Double)

object Checker {
    private lateinit var files: File
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    private val checks = ConcurrentHashMap<String, Check>()
    private val verdicts = HashMap<Pair<Model, String>, Verdict?>()
    private val options = GenerationOptions(temperature = 0f, maxTokens = 96, functionCallParser = null)
    private var runner: Pair<Model, ModelRunner>? = null
    private var job: Job? = null
    private val state = MutableStateFlow<Check?>(null)
    val current: StateFlow<Check?> = state

    fun init(context: Context) {
        files = File(context.filesDir, "models").apply { mkdirs() }
    }

    fun file(model: Model) = File(files, "${model.name.lowercase()}.gguf")

    fun installed(model: Model) = file(model).exists()

    fun check(clauses: List<String>, source: String, model: Model = Prefs.model.value): Check {
        val key = keyOf(model, clauses)
        state.value?.takeIf { it.key == key && it.problem == null }?.let { return it }
        job?.cancel()
        val check = checks[key] ?: Check(key, source, model, clauses)
        state.value = check
        if (check.running) job = scope.launch { run(check) }
        return check
    }

    fun previous(check: Check): Check? = checks[keyOf(check.model.other, check.clauses)]?.takeIf { !it.running }

    fun clear() {
        job?.cancel()
        state.value = null
    }

    private suspend fun run(initial: Check) {
        val started = SystemClock.elapsedRealtime()
        var check = initial.copy(startedAt = started - initial.millis)
        state.value = check
        try {
            if (!installed(check.model)) {
                state.value = check.copy(problem = Problem.NoModel)
                return
            }
            for (index in check.done until check.total) {
                val clause = check.clauses[index]
                val cacheKey = check.model to normalize(clause)
                val answer = if (cacheKey in verdicts) Answer(verdicts[cacheKey], 0, 0.0) else ask(check.model, clause).also { verdicts[cacheKey] = it.verdict }
                val verdict = answer.verdict
                check = check.copy(
                    done = index + 1,
                    findings = if (verdict?.flags.isNullOrEmpty()) check.findings else check.findings + Finding(index, clause, verdict),
                    invalid = check.invalid + if (verdict == null) 1 else 0,
                    tokens = check.tokens + answer.tokens,
                    decodeSeconds = check.decodeSeconds + answer.seconds,
                    millis = initial.millis + SystemClock.elapsedRealtime() - started,
                )
                checks[check.key] = check
                state.value = check
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            state.value = check.copy(problem = Problem.Failed)
        }
    }

    private suspend fun ask(model: Model, clause: String): Answer {
        val reply = StringBuilder()
        var tokens = 0L
        var first = 0L
        var last = 0L
        runnerFor(model).createConversation(SYSTEM_PROMPT).generateResponse(normalize(clause), options).collect {
            when (it) {
                is MessageResponse.Chunk -> {
                    last = System.nanoTime()
                    if (first == 0L) first = last
                    reply.append(it.text)
                }
                is MessageResponse.Complete -> tokens = it.stats?.completionTokens ?: 0
                else -> Unit
            }
        }
        val seconds = (last - first) / 1e9
        return Answer(parseReply(reply.toString()), if (seconds > 0) tokens - 1 else 0, seconds)
    }

    private suspend fun runnerFor(model: Model): ModelRunner {
        runner?.let { (loaded, runner) -> if (loaded == model) return runner else runner.unload() }
        runner = null
        val options = ModelLoadingOptions().apply {
            if (cpuThreads <= 1) cpuThreads = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 3)
        }
        return LeapClient.loadModel(file(model).path, options).also { runner = model to it }
    }

    fun forget(model: Model) {
        clear()
        checks.values.removeAll { it.model == model }
        scope.launch {
            verdicts.keys.removeAll { it.first == model }
            runner?.takeIf { it.first == model }?.let { it.second.unload(); runner = null }
        }
    }

    private fun keyOf(model: Model, clauses: List<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(model.name.toByteArray())
        clauses.forEach { digest.update(it.toByteArray()); digest.update(0) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
