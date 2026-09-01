package com.moatazvid.editor

import com.moatazvid.core.SourceId
import com.moatazvid.core.TimeRangeUs
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private data class VisualCacheKey(
    val sourceId: SourceId,
    val startBucketUs: Long,
    val endBucketUs: Long,
    val widthBucket: Int,
)

private class BoundedLru<K, V>(private val maximumEntries: Int) {
    private val values = object : LinkedHashMap<K, V>(maximumEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > maximumEntries
    }
    fun get(key: K): V? = values[key]
    fun put(key: K, value: V) { values[key] = value }
    fun clear() = values.clear()
}

/**
 * Reduces repeated thumbnail generation while scrolling/zooming. The cache stores opaque thumbnail
 * references only; bitmap memory stays owned by the Android image loader.
 */
class CachingThumbnailRepository(
    private val delegate: ThumbnailRepository,
    maximumEntries: Int = 96,
    private val timeBucketUs: Long = 250_000,
    private val widthBucketPx: Int = 160,
) : ThumbnailRepository {
    private val mutex = Mutex()
    private val cache = BoundedLru<VisualCacheKey, List<String>>(maximumEntries)

    override suspend fun visibleThumbnails(sourceId: SourceId, sourceRange: TimeRangeUs, pixelWidth: Int): List<String> {
        val key = key(sourceId, sourceRange, pixelWidth)
        mutex.withLock { cache.get(key) }?.let { return it }
        val generated = delegate.visibleThumbnails(sourceId, sourceRange, pixelWidth)
        mutex.withLock { cache.put(key, generated) }
        return generated
    }

    suspend fun clear() = mutex.withLock { cache.clear() }

    private fun key(sourceId: SourceId, range: TimeRangeUs, width: Int) = VisualCacheKey(
        sourceId,
        range.start.value / timeBucketUs,
        range.endExclusive.value / timeBucketUs,
        (width.coerceAtLeast(1) + widthBucketPx - 1) / widthBucketPx,
    )
}

/** Stores downsampled visible waveform envelopes, never raw PCM. */
class CachingWaveformRepository(
    private val delegate: WaveformRepository,
    maximumEntries: Int = 128,
    private val timeBucketUs: Long = 250_000,
    private val widthBucketPx: Int = 160,
) : WaveformRepository {
    private val mutex = Mutex()
    private val cache = BoundedLru<VisualCacheKey, FloatArray>(maximumEntries)

    override suspend fun visibleWaveform(sourceId: SourceId, sourceRange: TimeRangeUs, pixelWidth: Int): FloatArray {
        val key = VisualCacheKey(
            sourceId,
            sourceRange.start.value / timeBucketUs,
            sourceRange.endExclusive.value / timeBucketUs,
            (pixelWidth.coerceAtLeast(1) + widthBucketPx - 1) / widthBucketPx,
        )
        mutex.withLock { cache.get(key)?.copyOf() }?.let { return it }
        val generated = delegate.visibleWaveform(sourceId, sourceRange, pixelWidth)
        mutex.withLock { cache.put(key, generated.copyOf()) }
        return generated
    }

    suspend fun clear() = mutex.withLock { cache.clear() }
}
