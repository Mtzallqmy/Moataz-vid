package com.moatazvid.storage

enum class StoragePressure { NORMAL, WARNING, CRITICAL }

data class CleanupPlan(
    val entriesToDelete: List<CacheEntry>,
    val bytesReclaimed: Long,
    val pressureAfterEstimate: StoragePressure,
)

class CachePolicy(
    private val warningFreeBytes: Long = 2L * 1024 * 1024 * 1024,
    private val criticalFreeBytes: Long = 512L * 1024 * 1024,
) {
    fun pressure(freeBytes: Long): StoragePressure = when {
        freeBytes < criticalFreeBytes -> StoragePressure.CRITICAL
        freeBytes < warningFreeBytes -> StoragePressure.WARNING
        else -> StoragePressure.NORMAL
    }

    fun plan(entries: List<CacheEntry>, freeBytes: Long, targetFreeBytes: Long): CleanupPlan {
        if (freeBytes >= targetFreeBytes) return CleanupPlan(emptyList(), 0, pressure(freeBytes))
        val candidates = entries.asSequence()
            .filterNot { it.pinned }
            .sortedWith(compareBy<CacheEntry> { it.importance }.thenBy { it.lastAccessEpochMs })
        val selected = mutableListOf<CacheEntry>()
        var reclaimed = 0L
        for (entry in candidates) {
            selected += entry
            reclaimed += entry.sizeBytes
            if (freeBytes + reclaimed >= targetFreeBytes) break
        }
        return CleanupPlan(selected, reclaimed, pressure(freeBytes + reclaimed))
    }

    fun requiredExportHeadroom(estimatedOutputBytes: Long): Long =
        Math.addExact(Math.multiplyExact(estimatedOutputBytes, 2), 256L * 1024 * 1024)
}

