package com.moatazvid.media

data class PlannedMediaOperation(
    val graph: RenderGraph,
    val decision: BackendDecision,
    val previewWysiwyg: WysiwygLevel,
)

class MediaOperationPlanner(private val resolver: CapabilityResolver = CapabilityResolver()) {
    fun plan(graph: RenderGraph, capabilities: EngineCapabilities): MediaResult<PlannedMediaOperation> {
        val decision = resolver.resolve(graph, capabilities)
        if (decision.unsupported.isNotEmpty()) {
            return MediaResult.Failure(
                MediaEngineError.InvalidTimeline(
                    decision.unsupported.map { "Unsupported render feature: ${it.name}" }
                )
            )
        }
        val preview = if (decision.backend == BackendKind.MEDIA3) WysiwygLevel.EXACT else WysiwygLevel.APPROXIMATE
        return MediaResult.Success(PlannedMediaOperation(graph, decision, preview))
    }
}

