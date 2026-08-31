package com.moatazvid.speech

import com.moatazvid.core.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TranscriptAnalysisTest {
    @Test fun `Arabic normalization supports search variants without changing original text`() {
        assertEquals("التطبيق يعمل 123", ArabicTextNormalizer.normalize("اَلتَّطْبِيقُ يَعمل ١٢٣"))
    }

    @Test fun `silence detector ignores short pause and returns long pause`() {
        val samples = FloatArray(16_000) { if (it in 2_000..11_999) 0f else 0.4f }
        val result = SilenceDetector(SilencePolicy(-40.0, DurationUs(500_000), 160)).detect(SourceId("src"), samples)
        assertEquals(1, result.size)
        assertTrue(result.single().sourceRange.duration.value >= 600_000)
    }

    @Test fun `caption chunks preserve source word links`() {
        val words = (0..3).map { index -> word(index, listOf("هذا", "تطبيق", "يعمل", "محليا")[index]) }
        val captions = CaptionDraftGenerator().generate(words, CaptionDraftPolicy(wordsPerChunk = 2))
        assertEquals(2, captions.size)
        assertEquals(listOf(words[0].id, words[1].id), captions.first().wordIds)
    }

    @Test fun `timeline mapping accounts for source in and constant speed`() {
        val item = TimelineItem(ClipId("clip"), ProjectId("p"), SequenceId("s"), TrackId("t"), TimelineItemType.VIDEO,
            TimeUs(5_000_000), DurationUs(2_000_000), SourceId("src"), TimeRangeUs(TimeUs(10_000_000), TimeUs(14_000_000)))
        val mapped = TimelineTranscriptMapper().map(item, listOf(word(0, "hello", 11_000_000)), constantSpeed = 2.0).single()
        assertEquals(5_500_000, mapped.timelineRange.start.value)
    }

    private fun word(index: Int, text: String, start: Long = index * 500_000L) = TranscriptWord(
        TranscriptWordId("w$index"), TranscriptId("tr"), TranscriptSegmentId("seg"), SourceId("src"), index, text,
        ArabicTextNormalizer.normalize(text), TimeRangeUs(TimeUs(start), TimeUs(start + 400_000)), 0.9f,
        LanguageCode.ARABIC, null, TranscriptWordType.WORD,
    )
}
