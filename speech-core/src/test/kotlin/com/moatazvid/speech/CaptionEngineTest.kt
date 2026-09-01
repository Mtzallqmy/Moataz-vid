package com.moatazvid.speech

import com.moatazvid.core.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CaptionEngineTest {
    @Test fun `two word policy keeps word references and Arabic RTL`() {
        val words = listOf(word(0, "هذا"), word(1, "اختبار"), word(2, "واضح"), word(3, "جدا"))
        val cues = CaptionEngine().generate(words, TrackId("captions"), "social", CaptionPolicy(mode = CaptionGroupingMode.TWO_WORDS, language = LanguageCode.ARABIC))
        assertEquals(2, cues.size)
        assertEquals("هذا اختبار", cues.first().text)
        assertEquals(listOf(words[0].id, words[1].id), cues.first().wordRefs)
        assertTrue(cues.first().rightToLeft)
        assertTrue(cues.zipWithNext().all { (a, b) -> a.endMs <= b.startMs })
    }

    @Test fun `mixed Arabic English preserves model names and punctuation`() {
        val words = listOf(
            word(0, "استخدم"), word(1, "GPT-5"), punctuation(2, "،"), word(3, "ثم"), word(4, "صدّر"), punctuation(5, "؟")
        )
        val cue = CaptionEngine().generate(words, TrackId("captions"), "clean", CaptionPolicy(mode = CaptionGroupingMode.SENTENCE, maxWords = 8, language = LanguageCode.ARABIC)).single()
        assertEquals("استخدم GPT-5، ثم صدّر؟", cue.text)
        assertTrue(cue.rightToLeft)
    }

    @Test fun `pause policy breaks at silence and respects readable duration`() {
        val words = listOf(word(0, "أول", 0), word(1, "جملة", 300_000), word(2, "ثانية", 1_500_000))
        val cues = CaptionEngine().generate(words, TrackId("c"), "s", CaptionPolicy(mode = CaptionGroupingMode.PAUSE_BASED, breakOnSilenceMs = 500, minDurationMs = 350))
        assertEquals(2, cues.size)
        assertTrue(cues.all { it.endMs - it.startMs >= 350 || it.endMs > it.startMs })
    }

    @Test fun `caption mapper follows reordered trimmed clip timing`() {
        val words = listOf(word(0, "hello", 1_000_000), word(1, "world", 1_500_000))
        val cue = CaptionEngine().generate(words, TrackId("c"), "s", CaptionPolicy(mode = CaptionGroupingMode.TWO_WORDS, language = LanguageCode.ENGLISH)).single()
        val item = TimelineItem(ClipId("clip"), ProjectId("p"), SequenceId("seq"), TrackId("v"), TimelineItemType.VIDEO,
            TimeUs(5_000_000), DurationUs(2_000_000), SourceId("src"), TimeRangeUs(TimeUs(1_000_000), TimeUs(3_000_000)))
        val mapped = CaptionMapper().mapToTimeline(cue, words, listOf(item)).single()
        assertEquals(5_000, mapped.startMs)
        assertTrue(mapped.endMs > mapped.startMs)
    }

    private fun word(index: Int, text: String, start: Long = index * 500_000L) = TranscriptWord(
        TranscriptWordId("w$index"), TranscriptId("tr"), TranscriptSegmentId("seg"), SourceId("src"), index, text,
        text.lowercase(), TimeRangeUs(TimeUs(start), TimeUs(start + 250_000)), 0.95f,
        LanguageCode.ARABIC, null, TranscriptWordType.WORD,
    )

    private fun punctuation(index: Int, text: String) = TranscriptWord(
        TranscriptWordId("w$index"), TranscriptId("tr"), TranscriptSegmentId("seg"), SourceId("src"), index, text,
        text, TimeRangeUs(TimeUs(index * 500_000L), TimeUs(index * 500_000L + 50_000)), 0.99f,
        LanguageCode.ARABIC, null, TranscriptWordType.PUNCTUATION,
    )
}
