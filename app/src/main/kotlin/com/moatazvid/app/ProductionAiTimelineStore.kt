package com.moatazvid.app

import com.moatazvid.ai.editor.*
import com.moatazvid.core.*
import com.moatazvid.media.*
import com.moatazvid.storage.StorageError
import com.moatazvid.storage.room.EditTransactionEntity
import com.moatazvid.storage.room.HistoryCursorEntity
import java.security.MessageDigest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/**
 * Production edit store. The transaction table keeps a compact before/after editor snapshot so
 * manual and AI edits can be undone/redone after process death instead of only in memory.
 */
class ProductionAiTimelineStore(
    private val repository: ProductionProjectRepository,
) : AiTimelineStore {
    private val mutex = Mutex()
    private val transactions get() = repository.database.transactionDao()
    private val timelines get() = repository.database.timelineDao()

    override suspend fun load(projectId: ProjectId): AiEditableProject? = repository.loadEditableProject(projectId)

    override suspend fun commitAtomic(
        expectedRevision: Long,
        newProject: AiEditableProject,
        transaction: AppliedEditTransaction,
    ): CommitResult = mutex.withLock {
        val current = repository.loadEditableProject(transaction.projectId)
            ?: return@withLock CommitResult.Failure(StorageError.NotFound("project", transaction.projectId.value))
        if (current.revision != expectedRevision) {
            return@withLock CommitResult.Failure(StorageError.Conflict(expectedRevision, current.revision))
        }
        val committed = newProject.withRevision(transaction.resultRevision)
        val forward = SnapshotCodec.encode(committed, transaction.diff)
        val inverse = SnapshotCodec.encode(current, null)
        val cursor = transactions.cursor(transaction.sequenceId.value)
        val branch = cursor?.activeBranchId ?: MAIN_BRANCH

        // A new edit after undo intentionally replaces the abandoned redo tail on this branch.
        transactions.deleteAfterRevision(transaction.sequenceId.value, expectedRevision)
        if (!repository.persistEditableProject(expectedRevision, committed)) {
            val actual = repository.loadEditableProject(transaction.projectId)?.revision ?: -1L
            return@withLock CommitResult.Failure(StorageError.Conflict(expectedRevision, actual))
        }
        val row = EditTransactionEntity(
            transactionId = transaction.id.value,
            projectId = transaction.projectId.value,
            sequenceId = transaction.sequenceId.value,
            parentTransactionId = cursor?.currentTransactionId,
            branchId = branch,
            baseRevision = expectedRevision,
            resultRevision = transaction.resultRevision,
            origin = transaction.origin.name,
            title = transaction.title,
            editPlanId = transaction.planId?.value,
            forwardOperationsJson = forward,
            inverseOperationsJson = inverse,
            beforeTimelineHash = sha256(inverse),
            afterTimelineHash = sha256(forward),
            status = "APPLIED",
            createdAtEpochMs = transaction.createdAtEpochMs,
        )
        runCatching {
            transactions.insert(row)
            transactions.setCursor(
                HistoryCursorEntity(
                    sequenceId = transaction.sequenceId.value,
                    currentTransactionId = transaction.id.value,
                    activeBranchId = branch,
                    updatedAtEpochMs = System.currentTimeMillis(),
                )
            )
        }.getOrElse { failure ->
            // Restore the previous timeline if history persistence itself failed.
            repository.persistEditableProject(transaction.resultRevision, current.withRevision(expectedRevision))
            return@withLock CommitResult.Failure(StorageError.IoFailure("persist edit history", failure.message ?: "unknown"))
        }
        CommitResult.Success(committed, transaction)
    }

    override suspend fun undo(
        sequenceId: SequenceId,
        aiOnly: Boolean,
        transactionId: TransactionId?,
    ): CommitResult = mutex.withLock {
        val sequence = timelines.sequence(sequenceId.value)
            ?: return@withLock CommitResult.Failure(StorageError.NotFound("sequence", sequenceId.value))
        val current = repository.loadEditableProject(ProjectId(sequence.projectId))
            ?: return@withLock CommitResult.Failure(StorageError.NotFound("project", sequence.projectId))
        val cursor = transactions.cursor(sequenceId.value)
            ?: return@withLock CommitResult.Failure(StorageError.NotFound("undo", sequenceId.value))
        val currentId = cursor.currentTransactionId
            ?: return@withLock CommitResult.Failure(StorageError.NotFound("undo", sequenceId.value))
        val row = transactions.get(currentId)
            ?: return@withLock CommitResult.Failure(StorageError.NotFound("transaction", currentId))
        if ((aiOnly && row.origin != EditOrigin.AI.name) || (transactionId != null && row.transactionId != transactionId.value)) {
            return@withLock CommitResult.Failure(StorageError.Conflict(current.revision, current.revision))
        }
        val previous = runCatching { SnapshotCodec.decode(row.inverseOperationsJson, current).withRevision(row.baseRevision) }
            .getOrElse { return@withLock CommitResult.Failure(StorageError.CorruptState(it.message ?: "Invalid undo snapshot")) }
        if (!repository.persistEditableProject(current.revision, previous)) {
            val actual = repository.loadEditableProject(ProjectId(sequence.projectId))?.revision ?: -1L
            return@withLock CommitResult.Failure(StorageError.Conflict(current.revision, actual))
        }
        transactions.setCursor(
            HistoryCursorEntity(sequenceId.value, row.parentTransactionId, row.branchId, System.currentTimeMillis())
        )
        CommitResult.Success(previous, row.toApplied())
    }

    override suspend fun redo(sequenceId: SequenceId): CommitResult = mutex.withLock {
        val sequence = timelines.sequence(sequenceId.value)
            ?: return@withLock CommitResult.Failure(StorageError.NotFound("sequence", sequenceId.value))
        val current = repository.loadEditableProject(ProjectId(sequence.projectId))
            ?: return@withLock CommitResult.Failure(StorageError.NotFound("project", sequence.projectId))
        val cursor = transactions.cursor(sequenceId.value)
        val branch = cursor?.activeBranchId ?: MAIN_BRANCH
        val row = transactions.child(cursor?.currentTransactionId, branch)
            ?: return@withLock CommitResult.Failure(StorageError.NotFound("redo", sequenceId.value))
        if (row.baseRevision != current.revision) {
            return@withLock CommitResult.Failure(StorageError.Conflict(row.baseRevision, current.revision))
        }
        val next = runCatching { SnapshotCodec.decode(row.forwardOperationsJson, current).withRevision(row.resultRevision) }
            .getOrElse { return@withLock CommitResult.Failure(StorageError.CorruptState(it.message ?: "Invalid redo snapshot")) }
        if (!repository.persistEditableProject(current.revision, next)) {
            val actual = repository.loadEditableProject(ProjectId(sequence.projectId))?.revision ?: -1L
            return@withLock CommitResult.Failure(StorageError.Conflict(current.revision, actual))
        }
        transactions.setCursor(
            HistoryCursorEntity(sequenceId.value, row.transactionId, branch, System.currentTimeMillis())
        )
        CommitResult.Success(next, row.toApplied())
    }

    override suspend fun history(sequenceId: SequenceId): List<AppliedEditTransaction> = mutex.withLock {
        transactions.history(sequenceId.value).mapNotNull { row -> runCatching { row.toApplied() }.getOrNull() }
    }

    private fun EditTransactionEntity.toApplied(): AppliedEditTransaction {
        val forward = JSONObject(forwardOperationsJson)
        val diff = SnapshotCodec.decodeDiff(forward.optJSONObject("diff"), baseRevision, resultRevision, title)
        return AppliedEditTransaction(
            TransactionId(transactionId), ProjectId(projectId), SequenceId(sequenceId), title,
            runCatching { EditOrigin.valueOf(origin) }.getOrDefault(EditOrigin.MANUAL),
            baseRevision, resultRevision, editPlanId?.let(::EditPlanId), diff, createdAtEpochMs,
        )
    }

    private fun AiEditableProject.withRevision(value: Long): AiEditableProject = copy(
        snapshot = snapshot.copy(
            project = snapshot.project.copy(timelineRevision = value, updatedAtEpochMs = System.currentTimeMillis()),
            sequence = snapshot.sequence.copy(revision = value),
        )
    )

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private object SnapshotCodec {
        fun encode(project: AiEditableProject, diff: EditDiff?): String = JSONObject()
            .put("schema", "editor-snapshot-1")
            .put("revision", project.revision)
            .put("canvasWidth", project.snapshot.sequence.canvasWidth)
            .put("canvasHeight", project.snapshot.sequence.canvasHeight)
            .put("items", JSONArray(project.snapshot.items.map(::itemJson)))
            .put("properties", JSONObject(project.clipProperties.mapKeys { it.key.value }.mapValues { propertyJson(it.value) }))
            .put("elements", JSONArray(project.creativeElements.map(::elementJson)))
            .put("effects", JSONObject(project.creativeEffects.mapKeys { it.key.value }.mapValues { (_, value) -> JSONArray(value.map(::effectJson)) }))
            .put("transitions", JSONArray(project.creativeTransitions.map(::transitionJson)))
            .putOpt("diff", diff?.let(::diffJson))
            .toString()

        fun decode(payload: String, base: AiEditableProject): AiEditableProject {
            val root = JSONObject(payload)
            require(root.optString("schema") == "editor-snapshot-1")
            val revision = root.getLong("revision")
            val items = root.getJSONArray("items").objects().map { itemFromJson(it, base) }
            val propertiesObject = root.optJSONObject("properties") ?: JSONObject()
            val properties = propertiesObject.keys().asSequence().associate { key ->
                ClipId(key) to propertyFromJson(propertiesObject.getJSONObject(key))
            }
            val elements = (root.optJSONArray("elements") ?: JSONArray()).objects().map(::elementFromJson)
            val effectsObject = root.optJSONObject("effects") ?: JSONObject()
            val effects = effectsObject.keys().asSequence().associate { key ->
                ClipId(key) to effectsObject.getJSONArray(key).objects().map(::effectFromJson)
            }
            val transitions = (root.optJSONArray("transitions") ?: JSONArray()).objects().map(::transitionFromJson)
            return base.copy(
                snapshot = base.snapshot.copy(
                    project = base.snapshot.project.copy(timelineRevision = revision, updatedAtEpochMs = System.currentTimeMillis()),
                    sequence = base.snapshot.sequence.copy(
                        canvasWidth = root.optInt("canvasWidth", base.snapshot.sequence.canvasWidth),
                        canvasHeight = root.optInt("canvasHeight", base.snapshot.sequence.canvasHeight),
                        revision = revision,
                    ),
                    items = items,
                ),
                clipProperties = properties,
                creativeElements = elements,
                creativeEffects = effects,
                creativeTransitions = transitions,
            )
        }

        fun decodeDiff(obj: JSONObject?, baseRevision: Long, resultRevision: Long, title: String): EditDiff {
            if (obj == null) return EditDiff(
                DurationUs(0), DurationUs(0), (resultRevision - baseRevision).toInt().coerceAtLeast(1),
                emptyList(), emptyList(), 0, 0, 0, title,
            )
            return EditDiff(
                beforeDuration = DurationUs(obj.optLong("beforeDurationUs", 0)),
                afterDuration = DurationUs(obj.optLong("afterDurationUs", 0)),
                operationCount = obj.optInt("operationCount", 1),
                removedRanges = (obj.optJSONArray("removedRanges") ?: JSONArray()).objects().map { item ->
                    RemovedRangeDiff(ClipId(item.getString("clipId")), rangeFromJson(item.getJSONObject("range")), item.optString("reason"))
                },
                movedClips = (obj.optJSONArray("movedClips") ?: JSONArray()).objects().map { item ->
                    MovedClipDiff(ClipId(item.getString("clipId")), TimeUs(item.getLong("fromUs")), TimeUs(item.getLong("toUs")))
                },
                addedCaptions = obj.optInt("addedCaptions"),
                addedItems = obj.optInt("addedItems"),
                removedItems = obj.optInt("removedItems"),
                userSummary = obj.optString("userSummary", title),
            )
        }

        private fun itemJson(item: TimelineItem) = JSONObject()
            .put("id", item.id.value).put("trackId", item.trackId.value).put("type", item.type.name)
            .put("timelineStartUs", item.timelineStart.value).put("timelineDurationUs", item.timelineDuration.value)
            .putOpt("sourceId", item.sourceId?.value).putOpt("sourceRange", item.sourceRange?.let(::rangeJson))
            .put("enabled", item.enabled).put("locked", item.locked).putOpt("linkGroupId", item.linkGroupId)

        private fun itemFromJson(obj: JSONObject, base: AiEditableProject): TimelineItem {
            val sourceRange = obj.optJSONObject("sourceRange")?.let(::rangeFromJson)
            return TimelineItem(
                id = ClipId(obj.getString("id")),
                projectId = base.snapshot.project.id,
                sequenceId = base.snapshot.sequence.id,
                trackId = TrackId(obj.getString("trackId")),
                type = TimelineItemType.valueOf(obj.getString("type")),
                timelineStart = TimeUs(obj.getLong("timelineStartUs")),
                timelineDuration = DurationUs(obj.getLong("timelineDurationUs")),
                sourceId = obj.optString("sourceId").takeIf(String::isNotBlank)?.let(::SourceId),
                sourceRange = sourceRange,
                enabled = obj.optBoolean("enabled", true),
                locked = obj.optBoolean("locked", false),
                linkGroupId = obj.optString("linkGroupId").takeIf(String::isNotBlank),
            )
        }

        private fun propertyJson(value: ClipEditProperties) = JSONObject()
            .put("speed", value.speed).put("preservePitch", value.preservePitch).put("gainDb", value.gainDb)
            .put("transform", transformJson(value.transform)).putOpt("cropAspectRatio", value.cropAspectRatio)
            .put("fades", JSONArray(value.fades.map { (type, duration) -> JSONObject().put("type", type.name).put("durationUs", duration.value) }))

        private fun propertyFromJson(obj: JSONObject) = ClipEditProperties(
            speed = obj.optDouble("speed", 1.0).coerceIn(.25, 4.0),
            preservePitch = obj.optBoolean("preservePitch", true),
            gainDb = obj.optDouble("gainDb", 0.0).toFloat().coerceIn(-60f, 24f),
            transform = obj.optJSONObject("transform")?.let(::transformFromJson) ?: TransformNode(),
            cropAspectRatio = obj.optString("cropAspectRatio").takeIf(String::isNotBlank),
            fades = (obj.optJSONArray("fades") ?: JSONArray()).objects().map {
                FadeType.valueOf(it.getString("type")) to DurationUs(it.getLong("durationUs"))
            },
        )

        private fun elementJson(value: CreativeElement): JSONObject = when (value) {
            is CaptionCreativeElement -> baseElementJson("caption", value).put("text", value.text).put("styleId", value.styleId)
                .put("wordIds", JSONArray(value.wordIds)).put("animation", value.animation.name).put("rightToLeft", value.rightToLeft)
            is TextElement -> baseElementJson("text", value).put("text", value.text).put("styleId", value.styleId)
                .put("animationIn", value.animationIn.name).put("animationOut", value.animationOut.name).put("anchor", value.anchor.name)
            is ImageOverlayElement -> baseElementJson("image", value).put("assetId", value.assetId.value).put("fitMode", value.fitMode.name)
                .put("cornerRadius", value.cornerRadius).put("shadowRadius", value.shadowRadius)
            is ShapeElement -> baseElementJson("shape", value).put("primitive", value.primitive.name).put("fillArgb", value.fillArgb)
                .putOpt("strokeArgb", value.strokeArgb).put("strokeWidth", value.strokeWidth)
            is VideoOverlayElement -> baseElementJson("video", value).put("sourceId", value.sourceId.value)
                .put("sourceRange", rangeJson(value.sourceRange)).put("crop", transformJson(value.crop))
        }

        private fun baseElementJson(kind: String, value: CreativeElement) = JSONObject()
            .put("kind", kind).put("id", value.id.value).put("trackId", value.trackId.value).put("range", rangeJson(value.range))
            .put("transform", creativeTransformJson(value.transform)).put("zIndex", value.zIndex).put("enabled", value.enabled)

        private fun elementFromJson(obj: JSONObject): CreativeElement {
            val id = CreativeElementId(obj.getString("id"))
            val track = TrackId(obj.getString("trackId"))
            val range = rangeFromJson(obj.getJSONObject("range"))
            val transform = creativeTransformFromJson(obj.getJSONObject("transform"))
            val z = obj.optInt("zIndex")
            val enabled = obj.optBoolean("enabled", true)
            return when (obj.getString("kind")) {
                "caption" -> CaptionCreativeElement(
                    id, track, range, obj.getString("text"), obj.getString("styleId"),
                    (obj.optJSONArray("wordIds") ?: JSONArray()).strings(),
                    CreativeAnimation.valueOf(obj.optString("animation", CreativeAnimation.NONE.name)),
                    obj.optBoolean("rightToLeft"), transform, z, enabled,
                )
                "text" -> TextElement(
                    id, track, range, obj.getString("text"), obj.getString("styleId"),
                    CreativeAnimation.valueOf(obj.optString("animationIn", CreativeAnimation.NONE.name)),
                    CreativeAnimation.valueOf(obj.optString("animationOut", CreativeAnimation.NONE.name)),
                    TextAnchor.valueOf(obj.optString("anchor", TextAnchor.CENTER.name)), transform, z, enabled,
                )
                "image" -> ImageOverlayElement(
                    id, track, range, AssetId(obj.getString("assetId")),
                    OverlayFitMode.valueOf(obj.optString("fitMode", OverlayFitMode.FIT.name)),
                    obj.optDouble("cornerRadius").toFloat(), obj.optDouble("shadowRadius").toFloat(), transform, z, enabled,
                )
                "shape" -> ShapeElement(
                    id, track, range, ShapePrimitive.valueOf(obj.getString("primitive")), obj.getLong("fillArgb"),
                    if (obj.has("strokeArgb") && !obj.isNull("strokeArgb")) obj.getLong("strokeArgb") else null,
                    obj.optDouble("strokeWidth").toFloat(), transform, z, enabled,
                )
                "video" -> VideoOverlayElement(
                    id, track, range, SourceId(obj.getString("sourceId")), rangeFromJson(obj.getJSONObject("sourceRange")),
                    obj.optJSONObject("crop")?.let(::transformFromJson) ?: TransformNode(), transform, z, enabled,
                )
                else -> error("Unknown creative element ${obj.getString("kind")}")
            }
        }

        private fun effectJson(value: EffectInstance) = JSONObject()
            .put("id", value.id.value).put("type", value.type.name)
            .put("parameters", JSONArray(value.parameters.map { JSONObject().put("name", it.name).put("value", it.value).put("minimum", it.minimum).put("maximum", it.maximum) }))
            .putOpt("range", value.range?.let(::rangeJson)).put("enabled", value.enabled).put("orderIndex", value.orderIndex)

        private fun effectFromJson(obj: JSONObject) = EffectInstance(
            EffectId(obj.getString("id")), EffectType.valueOf(obj.getString("type")),
            (obj.optJSONArray("parameters") ?: JSONArray()).objects().map {
                EffectParameter(it.getString("name"), it.getDouble("value"), it.getDouble("minimum"), it.getDouble("maximum"))
            },
            obj.optJSONObject("range")?.let(::rangeFromJson), obj.optBoolean("enabled", true), obj.optInt("orderIndex"),
        )

        private fun transitionJson(value: CreativeTransition) = JSONObject()
            .put("id", value.id.value).put("type", value.type.name).put("durationMs", value.durationMs)
            .put("fromClipId", value.fromClipId.value).put("toClipId", value.toClipId.value).put("parameters", JSONObject(value.parameters))

        private fun transitionFromJson(obj: JSONObject): CreativeTransition {
            val parameters = obj.optJSONObject("parameters") ?: JSONObject()
            return CreativeTransition(
                TransitionId(obj.getString("id")), CreativeTransitionType.valueOf(obj.getString("type")), obj.getLong("durationMs"),
                ClipId(obj.getString("fromClipId")), ClipId(obj.getString("toClipId")),
                parameters.keys().asSequence().associateWith { parameters.getDouble(it) },
            )
        }

        private fun diffJson(value: EditDiff) = JSONObject()
            .put("beforeDurationUs", value.beforeDuration.value).put("afterDurationUs", value.afterDuration.value)
            .put("operationCount", value.operationCount).put("addedCaptions", value.addedCaptions)
            .put("addedItems", value.addedItems).put("removedItems", value.removedItems).put("userSummary", value.userSummary)
            .put("removedRanges", JSONArray(value.removedRanges.map {
                JSONObject().put("clipId", it.clipId.value).put("range", rangeJson(it.sourceRange)).put("reason", it.reason)
            }))
            .put("movedClips", JSONArray(value.movedClips.map {
                JSONObject().put("clipId", it.clipId.value).put("fromUs", it.from.value).put("toUs", it.to.value)
            }))

        private fun rangeJson(value: TimeRangeUs) = JSONObject().put("startUs", value.start.value).put("endUs", value.endExclusive.value)
        private fun rangeFromJson(value: JSONObject) = TimeRangeUs(TimeUs(value.getLong("startUs")), TimeUs(value.getLong("endUs")))
        private fun transformJson(value: TransformNode) = JSONObject()
            .put("positionX", value.positionX).put("positionY", value.positionY).put("scaleX", value.scaleX).put("scaleY", value.scaleY)
            .put("rotationDegrees", value.rotationDegrees).put("cropLeft", value.cropLeft).put("cropTop", value.cropTop)
            .put("cropRight", value.cropRight).put("cropBottom", value.cropBottom)
        private fun transformFromJson(value: JSONObject) = TransformNode(
            value.optDouble("positionX", .5).toFloat(), value.optDouble("positionY", .5).toFloat(),
            value.optDouble("scaleX", 1.0).toFloat(), value.optDouble("scaleY", 1.0).toFloat(),
            value.optDouble("rotationDegrees").toFloat(), value.optDouble("cropLeft").toFloat(), value.optDouble("cropTop").toFloat(),
            value.optDouble("cropRight", 1.0).toFloat(), value.optDouble("cropBottom", 1.0).toFloat(),
        )
        private fun creativeTransformJson(value: CreativeTransform) = JSONObject()
            .put("positionX", value.positionX).put("positionY", value.positionY).put("scaleX", value.scaleX).put("scaleY", value.scaleY)
            .put("rotationDegrees", value.rotationDegrees).put("anchorX", value.anchorX).put("anchorY", value.anchorY).put("opacity", value.opacity)
        private fun creativeTransformFromJson(value: JSONObject) = CreativeTransform(
            value.optDouble("positionX", .5).toFloat(), value.optDouble("positionY", .5).toFloat(),
            value.optDouble("scaleX", 1.0).toFloat(), value.optDouble("scaleY", 1.0).toFloat(),
            value.optDouble("rotationDegrees").toFloat(), value.optDouble("anchorX", .5).toFloat(), value.optDouble("anchorY", .5).toFloat(),
            value.optDouble("opacity", 1.0).toFloat(),
        )

        private fun JSONArray.objects(): List<JSONObject> = List(length()) { getJSONObject(it) }
        private fun JSONArray.strings(): List<String> = List(length()) { getString(it) }
    }

    companion object { private const val MAIN_BRANCH = "main" }
}
