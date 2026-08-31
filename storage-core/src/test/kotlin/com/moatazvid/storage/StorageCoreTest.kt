package com.moatazvid.storage

import com.moatazvid.core.ProjectId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.time.Duration.Companion.milliseconds

class StorageCoreTest {
    @Test fun `project paths cannot escape root`() {
        val root = Files.createTempDirectory("moataz-projects")
        val paths = ProjectPaths.under(root, ProjectId("prj_01ARZ3NDEKTSV4RRFFQ69G5FAV"))
        assertTrue(paths.proxies.startsWith(root))
        assertTrue(paths.userAssets.toString().contains("assets"))
    }

    @Test fun `cache cleanup excludes pinned and uses oldest regeneratable first`() {
        val projectId = ProjectId("prj_01ARZ3NDEKTSV4RRFFQ69G5FAV")
        val entries = listOf(
            CacheEntry("new", projectId, CacheKind.PROXY, "new", 100, 20, "f", CacheImportance.REGENERATABLE),
            CacheEntry("old", projectId, CacheKind.THUMBNAIL, "old", 100, 10, "f", CacheImportance.REGENERATABLE),
            CacheEntry("pinned", projectId, CacheKind.RENDER, "pin", 999, 1, "f", CacheImportance.REGENERATABLE, true),
        )
        val plan = CachePolicy(warningFreeBytes = 500, criticalFreeBytes = 100).plan(entries, 0, 150)
        assertEquals(listOf("old", "new"), plan.entriesToDelete.map { it.id })
        assertEquals(200, plan.bytesReclaimed)
    }

    @Test fun `atomic writer replaces complete file`() {
        val root = Files.createTempDirectory("atomic-writer")
        val file = root.resolve("state.json")
        AtomicFileWriter().write(file) { it.write("first".toByteArray()) }
        AtomicFileWriter().write(file) { it.write("second".toByteArray()) }
        assertEquals("second", Files.readString(file))
        assertEquals(1, Files.list(root).use { it.count() })
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `autosave debounces and immediate flush persists latest`() = runTest {
        val values = mutableListOf<Int>()
        val coordinator = AutosaveCoordinator(this, 100.milliseconds) { values += it }
        coordinator.schedule(1)
        coordinator.schedule(2)
        advanceTimeBy(101)
        runCurrent()
        coordinator.flush(3)
        assertEquals(listOf(2, 3), values)
        coordinator.close()
    }
}

