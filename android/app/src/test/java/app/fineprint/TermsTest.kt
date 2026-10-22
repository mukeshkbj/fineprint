package app.fineprint

import app.fineprint.Category.AgreeByUsing
import app.fineprint.Category.Arbitration
import app.fineprint.Category.Jurisdiction
import app.fineprint.Category.LiabilityLimit
import app.fineprint.Category.Termination
import app.fineprint.Category.TermsChange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TermsTest {
    @Test
    fun normalizesCaseAndWhitespace() {
        assertEquals("you agree to arbitration.", normalize("  You AGREE\n\tto   Arbitration. "))
    }

    @Test
    fun splitsBlocksIntoSentencesAndDropsShortOnes() {
        val text = listOf(
            "1. Disputes",
            "Any dispute will be resolved by binding arbitration in Delaware. We may change these Terms at any time without notice.",
            "",
            "Short one. Too short.",
            "",
            "You agree that we may suspend or terminate   your account at our sole discretion.",
        ).joinToString("\n")
        assertEquals(
            listOf(
                "Any dispute will be resolved by binding arbitration in Delaware.",
                "We may change these Terms at any time without notice.",
                "You agree that we may suspend or terminate your account at our sole discretion.",
            ),
            splitClauses(text),
        )
        assertEquals(300, splitClauses("This sentence is long enough to count as one clause. ".repeat(500)).size)
    }

    @Test
    fun parsesRepliesAndRejectsMalformedOnes() {
        assertEquals(Verdict(listOf(Arbitration), "You cannot sue."), parseReply("""{"flags": ["arbitration"], "plain": "You cannot sue."}"""))
        assertEquals(
            Verdict(listOf(Arbitration, AgreeByUsing), Arbitration.fallback),
            parseReply("Sure! ```json\n{\"flags\": [\"contract_by_using\", \"arbitration\", \"arbitration\", \"made_up\", 3], \"plain\": \"\"}\n```"),
        )
        assertEquals(Verdict(emptyList(), ""), parseReply("""{"flags": [], "plain": "Nothing here."}"""))
        for (broken in listOf("", "no json here", """{"flags": ["arbitration"""", """{"plain": "x"}""", """{"flags": "arbitration"}""", "} {", "[1, 2]")) {
            assertNull(broken, parseReply(broken))
        }
    }

    @Test
    fun gradesByDistinctSeriousTerms() {
        assertEquals(Grade.A, gradeOf(emptyList()))
        assertEquals(Grade.A, gradeOf(listOf(AgreeByUsing)))
        assertEquals(Grade.B, gradeOf(listOf(Jurisdiction)))
        assertEquals(Grade.C, gradeOf(listOf(Arbitration, Arbitration)))
        assertEquals(Grade.D, gradeOf(listOf(Arbitration, TermsChange, Jurisdiction)))
        assertEquals(Grade.E, gradeOf(listOf(Arbitration, TermsChange, LiabilityLimit, Termination)))
    }
}
