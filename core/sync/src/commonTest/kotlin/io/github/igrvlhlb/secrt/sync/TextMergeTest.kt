package io.github.igrvlhlb.secrt.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TextMergeTest {

    private val base = """
        Monday
        Went for a run.

        Tuesday
        Rained all day.

        Wednesday
        Read a book.
    """.trimIndent()

    @Test
    fun oneSidedChangesWin() {
        val edited = base.replace("Read a book.", "Read two books.")
        assertEquals(edited, TextMerge.merge(base, edited, base))
        assertEquals(edited, TextMerge.merge(base, base, edited))
    }

    @Test
    fun nonOverlappingEditsAreCombined() {
        val ours = base.replace("Went for a run.", "Went for a long run.")
        val theirs = base.replace("Read a book.", "Read a book and slept early.")

        val merged = TextMerge.merge(base, ours, theirs)

        assertEquals(base.replace("Went for a run.", "Went for a long run.").replace("Read a book.", "Read a book and slept early."), merged)
    }

    @Test
    fun appendsAtDifferentPlacesAreCombined() {
        val ours = "Intro\n$base"
        val theirs = "$base\nThursday\nCalm."

        assertEquals("Intro\n$base\nThursday\nCalm.", TextMerge.merge(base, ours, theirs))
    }

    @Test
    fun identicalEditsAreTakenOnce() {
        val edited = base.replace("Rained", "Snowed")
        assertEquals(edited, TextMerge.merge(base, edited, edited))
    }

    @Test
    fun overlappingEditsConflict() {
        val ours = base.replace("Rained all day.", "Sunny!")
        val theirs = base.replace("Rained all day.", "Cloudy.")

        assertNull(TextMerge.merge(base, ours, theirs))
    }

    @Test
    fun differentInsertionsAtTheSameSpotConflict() {
        assertNull(TextMerge.merge("a\nc", "a\nb1\nc", "a\nb2\nc"))
    }

    @Test
    fun editVersusDeletionOfTheSameLineConflicts() {
        assertNull(TextMerge.merge("a\nb\nc", "a\nB\nc", "a\nc"))
    }

    @Test
    fun deletionsOnBothSidesCombine() {
        assertEquals("b\nd", TextMerge.merge("a\nb\nc\nd", "b\nc\nd", "a\nb\nd"))
    }
}
