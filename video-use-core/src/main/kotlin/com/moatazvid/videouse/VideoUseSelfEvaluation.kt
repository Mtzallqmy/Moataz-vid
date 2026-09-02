package com.moatazvid.videouse

import com.moatazvid.core.TimeUs

enum class VideoUseEvaluationSeverity { WARNING, ERROR }

data class VideoUseEvaluationIssue(
    val code: String,
    val severity: VideoUseEvaluationSeverity,
    val message: String,
    val timelineTime: TimeUs? = null,
)

data class VideoUseSelfEvaluationReport(
    val pass: Int,
    val representativeFramesChecked: Int,
    val cutBoundariesChecked: Int,
    val issues: List<VideoUseEvaluationIssue>,
) {
    val passed: Boolean get() = issues.none { it.severity == VideoUseEvaluationSeverity.ERROR }
    val warnings: List<VideoUseEvaluationIssue> get() = issues.filter { it.severity == VideoUseEvaluationSeverity.WARNING }
}
