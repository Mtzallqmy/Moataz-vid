package com.moatazvid.storage

import com.moatazvid.core.ProjectId
import java.nio.file.Path

class ProjectPaths private constructor(val root: Path) {
    val metadata: Path = root.resolve("project")
    val managedMedia: Path = root.resolve("media")
    val userAssets: Path = root.resolve("assets/user")
    val generatedAssets: Path = root.resolve("assets/generated")
    val proxies: Path = root.resolve("derived/proxies")
    val thumbnails: Path = root.resolve("derived/thumbnails")
    val waveforms: Path = root.resolve("derived/waveforms")
    val transcripts: Path = root.resolve("derived/transcripts")
    val analysis: Path = root.resolve("derived/analysis")
    val renderCache: Path = root.resolve("cache/render")
    val exportsStaging: Path = root.resolve("exports/staging")
    val historySnapshots: Path = root.resolve("history/snapshots")
    val temp: Path = root.resolve("temp")

    companion object {
        fun under(appProjectsRoot: Path, projectId: ProjectId): ProjectPaths {
            require(projectId.value.matches(Regex("prj_[0-9A-HJKMNP-TV-Z]{26}")))
            val root = appProjectsRoot.resolve(projectId.value).normalize()
            require(root.startsWith(appProjectsRoot.normalize()))
            return ProjectPaths(root)
        }
    }
}
