package app.fineprint

import java.text.BreakIterator
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

const val SYSTEM_PROMPT = """You read one clause from a Terms of Service. Reply with JSON only: {"flags": [...], "plain": "..."}. "flags" lists every category that applies, chosen from: limitation_of_liability, unilateral_termination, unilateral_change, content_removal, contract_by_using, choice_of_law, jurisdiction, arbitration. "plain" is one short sentence that tells the user what they agree to. If the clause is harmless, reply {"flags": [], "plain": ""}."""

enum class Severity { High, Medium, Low }

enum class Category(val key: String, val label: String, val severity: Severity, val fallback: String) {
    Arbitration("arbitration", "Arbitration", Severity.High, "You give up your right to sue in court."),
    LiabilityLimit("limitation_of_liability", "Liability limit", Severity.High, "They limit what they owe you if something goes wrong."),
    Termination("unilateral_termination", "Can close your account", Severity.High, "They can end your account whenever they choose."),
    TermsChange("unilateral_change", "Can change the terms", Severity.High, "They can change these terms without asking you."),
    ContentRemoval("content_removal", "Can remove your content", Severity.Medium, "They can delete what you post."),
    Jurisdiction("jurisdiction", "Court location", Severity.Medium, "Disputes go to a court they choose."),
    ChoiceOfLaw("choice_of_law", "Governing law", Severity.Medium, "A law they choose applies to disputes."),
    AgreeByUsing("contract_by_using", "Agree by using", Severity.Low, "Using the service means you accept these terms."),
}

data class Verdict(val flags: List<Category>, val plain: String) {
    val severity: Severity? get() = flags.firstOrNull()?.severity
}

enum class Grade(val on: Long, val ink: Long) {
    A(0xFF1E8A4C, 0xFFFFFFFF),
    B(0xFF7DB342, 0xFF1C1A17),
    C(0xFFF2B705, 0xFF1C1A17),
    D(0xFFEF7D00, 0xFF1C1A17),
    E(0xFFE0451F, 0xFFFFFFFF),
}

private const val MAX_CLAUSES = 300
private const val MIN_CLAUSE_LENGTH = 40
private val whitespace = Regex("[\\s\\p{Z}\\uFEFF]+")
private val lineBreak = Regex("[\\n\\r\\u0085\\u2028\\u2029]")
private val categories = Category.entries.associateBy { it.key }

fun collapse(text: String) = text.replace(whitespace, " ").trim()

fun normalize(clause: String) = collapse(clause.lowercase(Locale.ROOT))

fun splitClauses(text: String): List<String> {
    val clauses = mutableListOf<String>()
    val sentences = BreakIterator.getSentenceInstance(Locale.US)
    for (line in text.split(lineBreak)) {
        sentences.setText(line)
        var start = sentences.first()
        var end = sentences.next()
        while (end != BreakIterator.DONE) {
            val clause = collapse(line.substring(start, end))
            if (clause.length >= MIN_CLAUSE_LENGTH) {
                clauses += clause
                if (clauses.size == MAX_CLAUSES) return clauses
            }
            start = end
            end = sentences.next()
        }
    }
    return clauses
}

fun parseReply(content: String): Verdict? {
    val start = content.indexOf('{')
    val end = content.lastIndexOf('}')
    if (start == -1 || end < start) return null
    val reply = try {
        Json.parseToJsonElement(content.substring(start, end + 1))
    } catch (_: IllegalArgumentException) {
        return null
    }
    val flags = ((reply as? JsonObject)?.get("flags") as? JsonArray ?: return null)
        .mapNotNull { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content?.let(categories::get) }
        .distinct()
        .sortedBy { it.severity }
    val plain = (reply["plain"] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content?.trim().orEmpty()
    return Verdict(flags, if (flags.isEmpty()) "" else plain.ifEmpty { flags.first().fallback })
}

private val legalWords = Regex(
    "\\b(terms|agree|agreement|liab|arbitrat|indemn|warrant|governing|jurisdiction|terminat|dispute|licen[cs]e|breach|waive|hereby|herein|pursuant|discretion|court|law)",
    RegexOption.IGNORE_CASE,
)

fun looksLikeTerms(clauses: List<String>): Boolean {
    val legal = clauses.count { legalWords.containsMatchIn(it) }
    return clauses.size >= 12 && legal >= 8 && legal * 3 >= clauses.size
}

fun gradeOf(flags: Collection<Category>): Grade {
    val distinct = flags.toSet()
    val high = distinct.count { it.severity == Severity.High }
    if (high == 0) return if (distinct.none { it.severity == Severity.Medium }) Grade.A else Grade.B
    return Grade.entries[minOf(high, 3) + 1]
}
