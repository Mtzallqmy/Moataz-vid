package com.moatazvid.editor

import com.moatazvid.core.*

object TimelineVirtualizer {
    fun visibleItems(items: List<TimelineItem>, viewport: TimelineViewportState, overscanScreens: Double = 0.5): List<TimelineItem> {
        val visible = viewport.visibleRange
        val padding = (visible.duration.value * overscanScreens).toLong()
        val start = (visible.start.value - padding).coerceAtLeast(0)
        val end = visible.endExclusive.value + padding
        return items.filter { item -> item.timelineStart.value < end && item.timelineStart.value + item.timelineDuration.value > start }
    }
}

data class PreviewDeviceState(val lowMemory: Boolean, val thermalSevere: Boolean, val proxyAvailable: Boolean, val renderComplexityScore: Int)
object PreviewQualitySelector {
    fun resolve(requested: PreviewQuality, device: PreviewDeviceState): PreviewQuality {
        if (requested != PreviewQuality.AUTO) return requested
        return when {
            device.lowMemory || device.thermalSevere -> PreviewQuality.LOW
            device.renderComplexityScore > 70 && device.proxyAvailable -> PreviewQuality.MEDIUM
            else -> PreviewQuality.HIGH
        }
    }
}
