package com.moatazvid.speech

import com.moatazvid.core.*
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

object ArabicTextNormalizer {
    private val marks = Regex("[\\u0610-\\u061A\\u064B-\\u065F\\u0670\\u06D6-\\u06ED]")
    private val punctuation = Regex("[^\\p{L}\\p{N}]+")
    fun normalize(value: String): String = value
        .replace("ـ", "")
        .replace(marks, "")
        .map { c ->
            when (c) {
                'أ', 'إ', 'آ', 'ٱ' -> 'ا'
                'ى' -> 'ي'
                'ؤ' -> 'و'
                'ئ' -> 'ي'
                in '٠'..'٩' -> ('0'.code + (c.code - '٠'.code)).toChar()
                in '۰'..'۹' -> ('0'.code + (c.code - '۰'.code)).toChar()
                else -> c.lowercaseChar()
            }
        }.joinToString("").replace(punctuation, " ").trim().replace(Regex("\\s+"), " ")
}

data class TranscriptSearchQuery(
    val text: String,
    val sourceId: SourceId? = null,
    val sourceRange: TimeRangeUs? = null,
    val fuzzy: Boolean = true,
    val limit: Int = 50,
)

data class TranscriptSearchHit(
    val sourceId: SourceId,
    val sourceRange: TimeRangeUs,
    val text: String,
    val score: Double,
    val firstWordIndex: Int,
    val lastWordIndex: Int,
)

class TranscriptSearchEngine {
    fun search(words: List<TranscriptWord>, query: TranscriptSearchQuery): List<TranscriptSearchHit> {
        require(query.limit in 1..500)
        val tokens = ArabicTextNormalizer.normalize(query.text).split(' ').filter(String::isNotBlank)
        if (tokens.isEmpty()) return emptyList()
        val candidates = words.filter { word ->
            word.type == TranscriptWordType.WORD &&
                (query.sourceId == null || word.sourceId == query.sourceId) &&
                (query.sourceRange == null || word.sourceRange.overlaps(query.sourceRange))
        }
        val width = tokens.size
        return candidates.windowed(width, partialWindows = false).mapNotNull { window ->
            if (window.zipWithNext().any { it.first.sourceId != it.second.sourceId || it.second.index != it.first.index + 1 }) return@mapNotNull null
            val actual = window.map { it.normalizedSearchText.ifBlank { ArabicTextNormalizer.normalize(it.text) } }
            val exact = actual == tokens
            val score = if (exact) 1.0 else if (query.fuzzy) tokenSimilarity(tokens, actual) else 0.0
            if (score < if (query.fuzzy) 0.62 else 1.0) null else TranscriptSearchHit(
                window.first().sourceId,
                TimeRangeUs(window.first().sourceRange.start, window.last().sourceRange.endExclusive),
                window.joinToString(" ") { it.text }, score, window.first().index, window.last().index,
            )
        }.sortedWith(compareByDescending<TranscriptSearchHit> { it.score }.thenBy { it.sourceRange.start.value }).take(query.limit)
    }

    private fun tokenSimilarity(a: List<String>, b: List<String>): Double =
        a.zip(b).map { (left, right) -> normalizedEditSimilarity(left, right) }.average()

    private fun normalizedEditSimilarity(a: String, b: String): Double {
        if (a == b) return 1.0
        val row = IntArray(b.length + 1) { it }
        a.forEachIndexed { i, ac ->
            var diagonal = row[0]
            row[0] = i + 1
            b.forEachIndexed { j, bc ->
                val old = row[j + 1]
                row[j + 1] = minOf(row[j + 1] + 1, row[j] + 1, diagonal + if (ac == bc) 0 else 1)
                diagonal = old
            }
        }
        return 1.0 - row.last().toDouble() / max(a.length, b.length).coerceAtLeast(1)
    }
}

data class PackedTranscriptLine(val sourceId: SourceId, val range: TimeRangeUs, val text: String, val wordIds: List<TranscriptWordId>)

class PackedTranscriptBuilder(
    private val maxWords: Int = 18,
    private val maxDuration: DurationUs = DurationUs(12_000_000),
    private val pauseBreak: DurationUs = DurationUs(700_000),
) {
    fun build(words: List<TranscriptWord>): List<PackedTranscriptLine> {
        val result = mutableListOf<PackedTranscriptLine>()
        var bucket = mutableListOf<TranscriptWord>()
        fun flush() {
            if (bucket.isEmpty()) return
            result += PackedTranscriptLine(bucket.first().sourceId, TimeRangeUs(bucket.first().sourceRange.start, bucket.last().sourceRange.endExclusive), renderWords(bucket), bucket.map { it.id })
            bucket = mutableListOf()
        }
        words.sortedWith(compareBy<TranscriptWord>({ it.sourceId.value }, { it.sourceRange.start.value })).forEach { word ->
            val boundary = bucket.isNotEmpty() && (word.sourceId != bucket.first().sourceId || word.speakerId != bucket.last().speakerId ||
                word.sourceRange.start.value - bucket.last().sourceRange.endExclusive.value >= pauseBreak.value ||
                word.sourceRange.endExclusive.value - bucket.first().sourceRange.start.value > maxDuration.value ||
                bucket.count { it.type == TranscriptWordType.WORD } >= maxWords)
            if (boundary) flush()
            bucket += word
            if (word.type == TranscriptWordType.PUNCTUATION && word.text.any { it in ".!?؟" }) flush()
        }
        flush()
        return result
    }

    fun render(lines: List<PackedTranscriptLine>): String = lines.joinToString("\n") {
        "SOURCE ${it.sourceId.value} [${"%.2f".format(it.range.start.value / 1_000_000.0)}–${"%.2f".format(it.range.endExclusive.value / 1_000_000.0)}] ${it.text}"
    }

    private fun renderWords(words: List<TranscriptWord>): String = buildString {
        words.forEach { word ->
            if (isNotEmpty() && word.type != TranscriptWordType.PUNCTUATION) append(' ')
            append(word.text)
        }
    }
}

data class SilencePolicy(val thresholdDbfs: Double = -42.0, val minimum: DurationUs = DurationUs(500_000), val frameSamples: Int = 320)
data class SilenceRange(val sourceId: SourceId, val sourceRange: TimeRangeUs, val meanDbfs: Double)

class SilenceDetector(private val policy: SilencePolicy = SilencePolicy()) {
    fun detect(sourceId: SourceId, samples: FloatArray, sampleRate: Int = 16_000, sourceStart: TimeUs = TimeUs(0)): List<SilenceRange> {
        require(sampleRate > 0 && policy.frameSamples > 0)
        data class Frame(val start: Int, val end: Int, val db: Double)
        val frames = samples.asList().chunked(policy.frameSamples).mapIndexed { index, frame ->
            val rms = sqrt(frame.sumOf { it.toDouble() * it } / frame.size.coerceAtLeast(1))
            Frame(index * policy.frameSamples, minOf(samples.size, (index + 1) * policy.frameSamples), 20 * ln(max(rms, 1e-9)) / ln(10.0))
        }
        val result = mutableListOf<SilenceRange>()
        var run = mutableListOf<Frame>()
        fun flush() {
            if (run.isNotEmpty()) {
                val start = sourceStart.value + run.first().start * 1_000_000L / sampleRate
                val end = sourceStart.value + run.last().end * 1_000_000L / sampleRate
                if (end - start >= policy.minimum.value) result += SilenceRange(sourceId, TimeRangeUs(TimeUs(start), TimeUs(end)), run.map { it.db }.average())
            }
            run = mutableListOf()
        }
        frames.forEach { if (it.db <= policy.thresholdDbfs) run += it else flush() }
        flush()
        return result
    }
}

data class AudioAnalysis(
    val duration: DurationUs, val peakDbfs: Double, val rmsDbfs: Double, val clippingSampleRatio: Double,
    val silenceRatio: Double, val estimatedNoiseFloorDbfs: Double, val speechDensity: Double,
)

class AudioAnalyzer(private val silenceDetector: SilenceDetector = SilenceDetector()) {
    fun analyze(sourceId: SourceId, samples: FloatArray, sampleRate: Int = 16_000): AudioAnalysis {
        require(samples.isNotEmpty())
        fun db(value: Double) = 20 * ln(max(value, 1e-9)) / ln(10.0)
        val durationUs = samples.size * 1_000_000L / sampleRate
        val silence = silenceDetector.detect(sourceId, samples, sampleRate).sumOf { it.sourceRange.duration.value }
        val abs = samples.map { kotlin.math.abs(it.toDouble()) }
        val rms = sqrt(samples.sumOf { it.toDouble() * it } / samples.size)
        val sorted = abs.sorted()
        val floor = sorted[(sorted.size * 0.20).toInt().coerceAtMost(sorted.lastIndex)]
        return AudioAnalysis(DurationUs(durationUs), db(abs.maxOrNull() ?: 0.0), db(rms), abs.count { it >= 0.999 }.toDouble() / samples.size,
            silence.toDouble() / durationUs.coerceAtLeast(1), db(floor), 1.0 - silence.toDouble() / durationUs.coerceAtLeast(1))
    }
}

data class DuplicateCandidate(val first: TranscriptSegmentId, val second: TranscriptSegmentId, val similarity: Double, val preferred: TranscriptSegmentId?)
class DuplicateDetector(private val threshold: Double = 0.78) {
    fun detect(segments: List<TranscriptSegment>): List<DuplicateCandidate> = segments.flatMapIndexed { i, a ->
        segments.drop(i + 1).mapNotNull { b ->
            if (a.sourceId != b.sourceId) return@mapNotNull null
            val left = a.normalizedSearchText.split(' ').filter(String::isNotBlank).toSet()
            val right = b.normalizedSearchText.split(' ').filter(String::isNotBlank).toSet()
            val score = if ((left + right).isEmpty()) 0.0 else left.intersect(right).size.toDouble() / left.union(right).size
            if (score < threshold) null else DuplicateCandidate(a.id, b.id, score, if ((a.confidence ?: 0f) >= (b.confidence ?: 0f)) a.id else b.id)
        }
    }
}

data class FillerOccurrence(val wordId: TranscriptWordId, val sourceRange: TimeRangeUs, val normalizedToken: String)
class FillerDetector(private val lexicon: Set<String> = setOf("امم", "اه", "يعني", "طيب", "اوكي", "uh", "um", "erm")) {
    fun detect(words: List<TranscriptWord>): List<FillerOccurrence> = words.mapNotNull {
        val token = ArabicTextNormalizer.normalize(it.text)
        if (it.type == TranscriptWordType.WORD && token in lexicon) FillerOccurrence(it.id, it.sourceRange, token) else null
    }
}
