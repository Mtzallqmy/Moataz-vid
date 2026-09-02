package com.moatazvid.videouse

import com.moatazvid.core.*
import com.moatazvid.speech.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class VideoUseCoreTest {
    private val transcript = TranscriptId("tr")
    private val segment = TranscriptSegmentId("seg")
    private val source = SourceId("source")

    @Test fun `packed transcript breaks on 500ms silence`() {
        val words = listOf(word(0, 0, 200_000, "hello"), word(1, 300_000, 500_000, "world"), word(2, 1_000_000, 1_200_000, "again"))
        val phrases = VideoUsePackedTranscriptBuilder().build(words)
        assertEquals(2, phrases.size)
        assertEquals("hello world", phrases[0].text)
        assertEquals("again", phrases[1].text)
    }

    @Test fun `cut snapping never leaves an edge inside a word`() {
        val words = listOf(word(0, 1_000_000, 1_400_000, "one"), word(1, 1_700_000, 2_000_000, "two"))
        val rules = VideoUseCutRules()
        val snapped = rules.snapOutward(source, TimeRangeUs(TimeUs(1_100_000), TimeUs(1_900_000)), words, DurationUs(3_000_000))
        assertTrue(rules.validate(source, snapped, words).valid)
        assertEquals(950_000L, snapped.start.value)
        assertEquals(2_080_000L, snapped.endExclusive.value)
    }

    @Test fun `output subtitle times follow EDL order`() {
        val secondSource = SourceId("second")
        val words = listOf(word(0, 1_000_000, 1_200_000, "a"), word(1, 2_000_000, 2_300_000, "b", secondSource))
        val segments = listOf(
            VideoUseEdlSegment(secondSource, TimeRangeUs(TimeUs(1_900_000), TimeUs(2_500_000))),
            VideoUseEdlSegment(source, TimeRangeUs(TimeUs(900_000), TimeUs(1_500_000))),
        )
        val mapped = VideoUseOutputTimelineMapper.mapWords(segments, words)
        assertEquals(100_000L, mapped[0].outputRange.start.value)
        assertEquals(700_000L, mapped[1].outputRange.start.value)
    }

    private fun word(index: Int, start: Long, end: Long, text: String, sourceId: SourceId = source) = TranscriptWord(
        TranscriptWordId("w$index-${sourceId.value}"), transcript, segment, sourceId, index, text, text,
        TimeRangeUs(TimeUs(start), TimeUs(end)), .99f, LanguageCode.ENGLISH, "speaker_0", TranscriptWordType.WORD,
    )
}
