package com.moatazvid.media

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

enum class PerformanceMetric {
    PROJECT_OPEN_MS,
    TIMELINE_FIRST_RENDER_MS,
    SEEK_MS,
    THUMBNAIL_DECODE_MS,
    DB_QUERY_MS,
    EXPORT_ELAPSED_MS,
    PROXY_ELAPSED_MS,
    DROPPED_PREVIEW_FRAMES,
    MEMORY_PEAK_BYTES,
}

data class MetricSnapshot(
    val metric: PerformanceMetric,
    val count: Long,
    val total: Long,
    val maximum: Long,
    val average: Double,
)

/** In-memory/debug diagnostics only. Nothing is transmitted off-device. */
class PerformanceMetricsCollector(private val enabled: Boolean) {
    private data class MutableMetric(val count: AtomicLong = AtomicLong(), val total: AtomicLong = AtomicLong(), val maximum: AtomicLong = AtomicLong())
    private val values = ConcurrentHashMap<PerformanceMetric, MutableMetric>()

    fun record(metric: PerformanceMetric, value: Long) {
        if (!enabled || value < 0) return
        val target = values.computeIfAbsent(metric) { MutableMetric() }
        target.count.incrementAndGet()
        target.total.addAndGet(value)
        target.maximum.accumulateAndGet(value, ::maxOf)
    }

    fun <T> measure(metric: PerformanceMetric, nanoTime: () -> Long = System::nanoTime, block: () -> T): T {
        if (!enabled) return block()
        val started = nanoTime()
        return try { block() } finally { record(metric, (nanoTime() - started) / 1_000_000) }
    }

    fun snapshot(): List<MetricSnapshot> = if (!enabled) emptyList() else values.map { (metric, value) ->
        val count = value.count.get()
        val total = value.total.get()
        MetricSnapshot(metric, count, total, value.maximum.get(), if (count == 0L) 0.0 else total.toDouble() / count)
    }.sortedBy { it.metric.name }

    fun clear() { if (enabled) values.clear() }
}
