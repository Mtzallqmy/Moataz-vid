package com.moatazvid.app

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.room3.Room
import com.moatazvid.ai.editor.*
import com.moatazvid.core.*
import com.moatazvid.media.*
import com.moatazvid.storage.ProjectSnapshot
import com.moatazvid.storage.room.*
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Production persistence facade used by Home/Import/Editor. All media IO runs off the main thread. */
class ProductionProjectRepository private constructor(
    private val context: Context,
    val database: MoatazVidDatabase,
    private val ids: IdGenerator = UlidIdGenerator(),
) {
    data class ProjectSummary(val id: ProjectId, val title: String, val updatedAtEpochMs: Long)

    fun observeProjects(): Flow<List<ProjectSummary>> = database.projectDao().observeAll().map { rows ->
        rows.map { ProjectSummary(ProjectId(it.projectId), it.title, it.updatedAtEpochMs) }
    }

    suspend fun createProject(title: String, width: Int = 1920, height: Int = 1080): ProjectId = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val projectId = ProjectId(ids.newId(IdKind.PROJECT))
        val sequenceId = SequenceId(ids.newId(IdKind.SEQUENCE))
        val tracks = listOf(
            newTrack(sequenceId, TrackType.VIDEO, 0, CollisionPolicy.NO_OVERLAP, "Video"),
            newTrack(sequenceId, TrackType.AUDIO, 0, CollisionPolicy.ALLOW_OVERLAP, "Audio"),
            newTrack(sequenceId, TrackType.MUSIC, 0, CollisionPolicy.ALLOW_OVERLAP, "Music"),
            newTrack(sequenceId, TrackType.CAPTION, 0, CollisionPolicy.ALLOW_OVERLAP, "Captions"),
            newTrack(sequenceId, TrackType.OVERLAY, 0, CollisionPolicy.STACK, "Overlays"),
        )
        database.withWriteTransaction {
            database.projectDao().insert(
                ProjectEntity(
                    projectId.value,
                    title.trim().ifBlank { "Untitled project" }.take(200),
                    sequenceId.value,
                    "1.0.0",
                    0,
                    ProjectStatus.ACTIVE.name,
                    PrivacyLevel.LOCAL_ONLY.name,
                    now,
                    now,
                    0,
                )
            )
            database.timelineDao().insertSequence(
                SequenceEntity(
                    sequenceId.value, projectId.value, "Main", width, height,
                    Rational.FPS_30.numerator, Rational.FPS_30.denominator,
                    ProjectColorMode.SDR.name, 0, now, now,
                )
            )
            database.timelineDao().insertTracks(tracks)
        }
        projectId
    }

    suspend fun renameProject(id: ProjectId, title: String) = withContext(Dispatchers.IO) {
        val clean = title.trim().take(200)
        require(clean.isNotBlank())
        check(database.projectDao().rename(id.value, clean, System.currentTimeMillis()) == 1)
    }

    suspend fun deleteProject(id: ProjectId) = withContext(Dispatchers.IO) {
        database.projectDao().delete(id.value) == 1
    }

    suspend fun importMedia(projectId: ProjectId, uri: Uri): SourceId = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val project = requireNotNull(database.projectDao().get(projectId.value)) { "Project not found" }
        val sequenceId = requireNotNull(project.activeSequenceId) { "Project has no active sequence" }
        val sequence = requireNotNull(database.timelineDao().sequence(sequenceId))
        val metadata = inspectMedia(resolver, uri)
        val sourceId = SourceId(ids.newId(IdKind.SOURCE))
        val fileRefId = "fil_${ids.newId(IdKind.ASSET).substringAfter('_')}"
        val clipId = ClipId(ids.newId(IdKind.CLIP))
        val now = System.currentTimeMillis()
        val persisted = runCatching {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            true
        }.getOrDefault(false)
        val fileRef = FileReferenceEntity(
            fileRefId = fileRefId,
            storageKind = "SAF_URI",
            uriString = uri.toString(),
            managedRelativePath = null,
            persistedPermission = persisted,
            mimeType = metadata.mimeType,
            sizeBytes = metadata.sizeBytes,
            modifiedAtEpochMs = null,
            sha256 = null,
            availability = "AVAILABLE",
            lastVerifiedAtEpochMs = now,
        )
        val source = MediaSourceEntity(
            sourceId = sourceId.value,
            projectId = projectId.value,
            fileRefId = fileRefId,
            kind = metadata.kind.name,
            displayName = metadata.displayName,
            importMode = ImportMode.LINKED_SAF.name,
            mimeType = metadata.mimeType,
            durationUs = metadata.durationUs,
            codedWidth = metadata.width,
            codedHeight = metadata.height,
            rotationDegrees = metadata.rotation,
            fpsNumerator = metadata.fps?.numerator,
            fpsDenominator = metadata.fps?.denominator,
            colorSpace = if (metadata.hdr) "HDR" else "SDR",
            quickFingerprint = metadata.fingerprint,
            fullSha256 = null,
            fingerprintVersion = 1,
            availability = SourceAvailability.AVAILABLE.name,
            metadataVersion = 1,
            createdAtEpochMs = now,
        )
        val tracks = database.timelineDao().tracks(sequenceId)
        val targetType = if (metadata.kind == MediaKind.AUDIO) TrackType.AUDIO else TrackType.VIDEO
        val track = requireNotNull(tracks.firstOrNull { it.type == targetType.name }) { "Missing $targetType track" }
        val existing = database.timelineDao().clips(sequenceId).filter { it.trackId == track.trackId }
        val timelineStart = existing.maxOfOrNull { it.timelineStartUs + it.timelineDurationUs } ?: 0L
        val duration = requireNotNull(metadata.durationUs) { "Media duration unavailable" }.coerceAtLeast(50_000L)
        val clip = ClipEntity(
            clipId = clipId.value,
            projectId = projectId.value,
            sequenceId = sequenceId,
            trackId = track.trackId,
            sourceId = sourceId.value,
            itemType = if (metadata.kind == MediaKind.AUDIO) TimelineItemType.AUDIO.name else TimelineItemType.VIDEO.name,
            timelineStartUs = timelineStart,
            timelineDurationUs = duration,
            sourceInUs = 0,
            sourceOutUs = duration,
            zIndex = 0,
            enabled = true,
            locked = false,
            lockReason = null,
            groupId = null,
            linkGroupId = null,
            rowRevision = 0,
        )
        val properties = ClipPropertiesEntity(
            clipId.value, null, 1f, 0f, 0f, false, true,
            0, 0, "1.0", null,
            if (metadata.kind == MediaKind.AUDIO) "DIALOGUE" else null,
            null, 1,
        )
        database.withWriteTransaction {
            database.mediaDao().insertFileRef(fileRef)
            database.mediaDao().insertSource(source)
            database.timelineDao().insertClips(listOf(clip))
            database.timelineDao().upsertClipProperties(properties)
            database.timelineDao().updateSequence(sequence.copy(revision = sequence.revision + 1, updatedAtEpochMs = now))
            database.projectDao().update(project.copy(timelineRevision = project.timelineRevision + 1, updatedAtEpochMs = now, rowRevision = project.rowRevision + 1))
        }
        sourceId
    }

    suspend fun loadEditableProject(projectId: ProjectId): AiEditableProject? = withContext(Dispatchers.IO) {
        val projectRow = database.projectDao().get(projectId.value) ?: return@withContext null
        val sequenceId = projectRow.activeSequenceId ?: return@withContext null
        val sequenceRow = database.timelineDao().sequence(sequenceId) ?: return@withContext null
        val trackRows = database.timelineDao().tracks(sequenceId)
        val clipRows = database.timelineDao().clips(sequenceId)
        val sourceRows = database.mediaDao().sources(projectId.value)
        val clipIds = clipRows.map { it.clipId }
        val trackIds = trackRows.map { it.trackId }
        val propertyRows = if (clipIds.isEmpty()) emptyList() else database.timelineDao().clipProperties(clipIds)
        val overlayRows = if (clipIds.isEmpty()) emptyList() else database.timelineDao().overlays(clipIds)
        val effectRows = if (clipIds.isEmpty()) emptyList() else database.timelineDao().effects(clipIds)
        val captionRows = database.timelineDao().captions(sequenceId)
        val transitionRows = if (trackIds.isEmpty()) emptyList() else database.timelineDao().transitions(trackIds)

        val project = Project(
            ProjectId(projectRow.projectId), projectRow.title, projectRow.activeSequenceId?.let(::SequenceId),
            projectRow.timelineRevision, projectRow.schemaVersion, ProjectStatus.valueOf(projectRow.status),
            PrivacyLevel.valueOf(projectRow.privacyLevel), projectRow.createdAtEpochMs, projectRow.updatedAtEpochMs,
        )
        val sequence = Sequence(
            SequenceId(sequenceRow.sequenceId), ProjectId(sequenceRow.projectId), sequenceRow.name,
            sequenceRow.canvasWidth, sequenceRow.canvasHeight,
            Rational(sequenceRow.fpsNumerator, sequenceRow.fpsDenominator), ProjectColorMode.valueOf(sequenceRow.colorMode), sequenceRow.revision,
        )
        val tracks = trackRows.map { row ->
            Track(TrackId(row.trackId), SequenceId(row.sequenceId), TrackType.valueOf(row.type), row.orderIndex, CollisionPolicy.valueOf(row.collisionPolicy), row.locked, row.muted, row.hidden)
        }
        val items = clipRows.map { row ->
            val sourceInUs = row.sourceInUs
            val sourceOutUs = row.sourceOutUs
            TimelineItem(
                ClipId(row.clipId), ProjectId(row.projectId), SequenceId(row.sequenceId), TrackId(row.trackId), TimelineItemType.valueOf(row.itemType),
                TimeUs(row.timelineStartUs), DurationUs(row.timelineDurationUs), row.sourceId?.let(::SourceId),
                if (sourceInUs != null && sourceOutUs != null) TimeRangeUs(TimeUs(sourceInUs), TimeUs(sourceOutUs)) else null,
                row.enabled, row.locked, row.linkGroupId,
            )
        }
        val itemById = items.associateBy { it.id }
        val sources = sourceRows.map { row ->
            val fpsNumerator = row.fpsNumerator
            val fpsDenominator = row.fpsDenominator
            MediaSource(
                SourceId(row.sourceId), ProjectId(row.projectId), MediaKind.valueOf(row.kind), row.displayName, row.fileRefId,
                ImportMode.valueOf(row.importMode), row.mimeType, row.durationUs?.let(::DurationUs), row.codedWidth, row.codedHeight,
                row.rotationDegrees, if (fpsNumerator != null && fpsDenominator != null) Rational(fpsNumerator, fpsDenominator) else null,
                row.quickFingerprint, SourceAvailability.valueOf(row.availability),
            )
        }
        val properties = propertyRows.associate { row ->
            ClipId(row.clipId) to ClipEditProperties(
                speed = row.speedMapJson.toDoubleOrNull()?.coerceIn(0.25, 4.0) ?: 1.0,
                preservePitch = row.preservePitch,
                gainDb = row.gainDb,
                transform = row.transformJson?.let(::decodeTransformNode) ?: TransformNode(),
                cropAspectRatio = row.extraJson?.let { runCatching { JSONObject(it).optString("cropAspectRatio").takeIf(String::isNotBlank) }.getOrNull() },
                fades = buildList {
                    if (row.fadeInUs > 0) add(com.moatazvid.ai.editor.FadeType.AUDIO_IN to DurationUs(row.fadeInUs))
                    if (row.fadeOutUs > 0) add(com.moatazvid.ai.editor.FadeType.AUDIO_OUT to DurationUs(row.fadeOutUs))
                },
            )
        }
        val creativeElements = buildList<CreativeElement> {
            overlayRows.forEach { row ->
                val item = itemById[ClipId(row.clipId)] ?: return@forEach
                val style = parseJson(row.styleJson)
                val transform = decodeCreativeTransform(row.transformJson)
                when (row.overlayType) {
                    "TEXT" -> row.text?.takeIf(String::isNotBlank)?.let { text ->
                        add(TextElement(
                            CreativeElementId(row.clipId), item.trackId, item.timelineRange(), text,
                            style.optString("styleId", "default"),
                            animationIn = enumOrDefault(style.optString("animationIn"), CreativeAnimation.NONE),
                            animationOut = enumOrDefault(style.optString("animationOut"), CreativeAnimation.NONE),
                            anchor = enumOrDefault(style.optString("anchor"), TextAnchor.CENTER),
                            transform = transform,
                            zIndex = style.optInt("zIndex", 80),
                            enabled = item.enabled,
                        ))
                    }
                    "IMAGE" -> row.assetId?.let { asset ->
                        add(ImageOverlayElement(
                            CreativeElementId(row.clipId), item.trackId, item.timelineRange(), AssetId(asset),
                            fitMode = enumOrDefault(style.optString("fitMode"), OverlayFitMode.FIT),
                            cornerRadius = style.optDouble("cornerRadius", 0.0).toFloat(),
                            shadowRadius = style.optDouble("shadowRadius", 0.0).toFloat(),
                            transform = transform,
                            zIndex = style.optInt("zIndex", 60),
                            enabled = item.enabled,
                        ))
                    }
                }
            }
            captionRows.forEach { row ->
                add(CaptionCreativeElement(
                    CreativeElementId(row.captionId), TrackId(row.trackId),
                    TimeRangeUs(TimeUs(row.startUs), TimeUs(row.endUs)), row.text, row.styleId,
                    jsonStringList(row.linkedWordIdsJson),
                    transform = CreativeTransform(positionY = 0.84f),
                    enabled = true,
                    rightToLeft = detectRtl(row.text),
                ))
            }
        }
        val creativeEffects = effectRows.groupBy { ClipId(it.clipId) }.mapValues { (_, rows) ->
            rows.map { row ->
                val descriptor = DefaultCreativeDescriptors.effects[enumOrDefault(row.effectType, EffectType.BRIGHTNESS)]
                val parameters = parseJson(row.parametersJson)
                EffectInstance(
                    EffectId(row.effectId), enumOrDefault(row.effectType, EffectType.BRIGHTNESS),
                    parameters.keys().asSequence().mapNotNull { name ->
                        val value = parameters.optDouble(name, Double.NaN)
                        if (!value.isFinite()) null else {
                            val range = descriptor?.parameterRanges?.get(name)
                            EffectParameter(name, value, range?.start ?: value, range?.endInclusive ?: value)
                        }
                    }.toList(),
                    range = if (row.endOffsetUs > row.startOffsetUs) TimeRangeUs(TimeUs(row.startOffsetUs), TimeUs(row.endOffsetUs)) else null,
                    enabled = row.enabled,
                    orderIndex = row.orderIndex,
                )
            }
        }
        val creativeTransitions = transitionRows.map { row ->
            CreativeTransition(
                TransitionId(row.transitionId), enumOrDefault(row.type, CreativeTransitionType.CUT), row.durationUs / 1_000,
                ClipId(row.outgoingClipId), ClipId(row.incomingClipId), jsonNumberMap(row.parametersJson),
            )
        }
        AiEditableProject(
            ProjectSnapshot(project, sequence, tracks, items, 0), sources, properties,
            creativeElements = creativeElements,
            creativeEffects = creativeEffects,
            creativeTransitions = creativeTransitions,
        )
    }

    /** Replace the editable state atomically after the caller has validated/simulated it. */
    suspend fun persistEditableProject(expectedRevision: Long, state: AiEditableProject): Boolean = withContext(Dispatchers.IO) {
        val sequenceId = state.snapshot.sequence.id.value
        val currentSequence = database.timelineDao().sequence(sequenceId) ?: return@withContext false
        if (currentSequence.revision != expectedRevision) return@withContext false
        val projectRow = database.projectDao().get(state.snapshot.project.id.value) ?: return@withContext false
        val now = System.currentTimeMillis()
        val elementsById = state.creativeElements.associateBy { it.id.value }
        val clips = state.snapshot.items.map { item ->
            val source = item.sourceRange
            val zIndex = elementsById[item.id.value]?.zIndex ?: 0
            ClipEntity(
                item.id.value, item.projectId.value, item.sequenceId.value, item.trackId.value, item.sourceId?.value,
                item.type.name, item.timelineStart.value, item.timelineDuration.value,
                source?.start?.value, source?.endExclusive?.value, zIndex, item.enabled, item.locked, null, null, item.linkGroupId, 0,
            )
        }
        val clipProperties = state.snapshot.items.mapNotNull { item ->
            val prop = state.clipProperties[item.id] ?: return@mapNotNull null
            val fadeIn = prop.fades.filter { it.first in setOf(com.moatazvid.ai.editor.FadeType.AUDIO_IN, com.moatazvid.ai.editor.FadeType.VIDEO_IN) }.maxOfOrNull { it.second.value } ?: 0L
            val fadeOut = prop.fades.filter { it.first in setOf(com.moatazvid.ai.editor.FadeType.AUDIO_OUT, com.moatazvid.ai.editor.FadeType.VIDEO_OUT) }.maxOfOrNull { it.second.value } ?: 0L
            ClipPropertiesEntity(
                item.id.value, null, 1f, prop.gainDb, 0f, false, prop.preservePitch,
                fadeIn, fadeOut, prop.speed.toString(), encodeTransformNode(prop.transform),
                if (item.type == TimelineItemType.MUSIC) "MUSIC" else if (item.type == TimelineItemType.AUDIO) "DIALOGUE" else null,
                JSONObject().putOpt("cropAspectRatio", prop.cropAspectRatio).toString(), 1,
            )
        }
        val overlays = state.creativeElements.mapNotNull { element ->
            when (element) {
                is TextElement -> OverlayEntity(
                    element.id.value, "TEXT", element.text, null,
                    JSONObject().put("styleId", element.styleId).put("animationIn", element.animationIn.name).put("animationOut", element.animationOut.name)
                        .put("anchor", element.anchor.name).put("zIndex", element.zIndex).toString(),
                    encodeCreativeTransform(element.transform), 1,
                )
                is ImageOverlayElement -> OverlayEntity(
                    element.id.value, "IMAGE", null, element.assetId.value,
                    JSONObject().put("fitMode", element.fitMode.name).put("cornerRadius", element.cornerRadius).put("shadowRadius", element.shadowRadius)
                        .put("zIndex", element.zIndex).toString(),
                    encodeCreativeTransform(element.transform), 1,
                )
                else -> null
            }
        }
        val captions = state.creativeElements.filterIsInstance<CaptionCreativeElement>().map { element ->
            CaptionEntity(
                element.id.value, sequenceId, element.trackId.value, element.range.start.value, element.range.endExclusive.value,
                element.text, element.styleId, "GENERATED", null, JSONArray(element.wordIds).toString(), true, "ALIGNED", 0,
            )
        }
        val effects = state.creativeEffects.flatMap { (clipId, instances) ->
            instances.map { effect ->
                val range = effect.range
                EffectEntity(
                    effect.id.value, clipId.value, effect.type.name, range?.start?.value ?: 0,
                    range?.endExclusive?.value ?: 0, effect.orderIndex, effect.enabled,
                    JSONObject(effect.parameters.associate { it.name to it.value }).toString(), 1,
                )
            }
        }
        val itemTrack = state.snapshot.items.associate { it.id to it.trackId }
        val transitions = state.creativeTransitions.map { transition ->
            TransitionEntity(
                transition.id.value,
                itemTrack[transition.fromClipId]?.value ?: state.snapshot.tracks.first { it.type == TrackType.VIDEO }.id.value,
                transition.fromClipId.value, transition.toClipId.value, transition.type.name, transition.durationMs * 1_000,
                "CENTER", JSONObject(transition.parameters).toString(),
            )
        }
        database.withWriteTransaction {
            database.timelineDao().deleteCaptionsBySequence(sequenceId)
            database.timelineDao().deleteTransitionsBySequence(sequenceId)
            database.timelineDao().deleteClipsBySequence(sequenceId)
            if (clips.isNotEmpty()) database.timelineDao().insertClips(clips)
            clipProperties.forEach { database.timelineDao().upsertClipProperties(it) }
            if (overlays.isNotEmpty()) database.timelineDao().upsertOverlays(overlays)
            if (effects.isNotEmpty()) database.timelineDao().upsertEffects(effects)
            if (captions.isNotEmpty()) database.timelineDao().upsertCaptions(captions)
            if (transitions.isNotEmpty()) database.timelineDao().upsertTransitions(transitions)
            database.timelineDao().updateSequence(
                currentSequence.copy(
                    canvasWidth = state.snapshot.sequence.canvasWidth,
                    canvasHeight = state.snapshot.sequence.canvasHeight,
                    fpsNumerator = state.snapshot.sequence.frameRate.numerator,
                    fpsDenominator = state.snapshot.sequence.frameRate.denominator,
                    colorMode = state.snapshot.sequence.colorMode.name,
                    revision = state.revision,
                    updatedAtEpochMs = now,
                )
            )
            database.projectDao().update(
                projectRow.copy(
                    title = state.snapshot.project.title,
                    timelineRevision = state.revision,
                    updatedAtEpochMs = now,
                    rowRevision = projectRow.rowRevision + 1,
                )
            )
        }
        true
    }

    suspend fun sourceUri(sourceId: SourceId): Uri? = withContext(Dispatchers.IO) {
        val source = database.mediaDao().source(sourceId.value) ?: return@withContext null
        database.mediaDao().fileRef(source.fileRefId)?.uriString?.let(Uri::parse)
    }

    suspend fun assetUri(assetId: AssetId): Uri? = withContext(Dispatchers.IO) {
        val asset = database.mediaDao().asset(assetId.value) ?: return@withContext null
        database.mediaDao().fileRef(asset.fileRefId ?: return@withContext null)?.uriString?.let(Uri::parse)
    }

    private fun TimelineItem.timelineRange() = TimeRangeUs(timelineStart, TimeUs(timelineStart.value + timelineDuration.value))

    private fun newTrack(sequenceId: SequenceId, type: TrackType, index: Int, collision: CollisionPolicy, name: String) = TrackEntity(
        ids.newId(IdKind.TRACK), sequenceId.value, type.name, name, index, collision.name,
        muted = false, hidden = false, locked = false, volumeDb = 0f, blendMode = null,
    )

    private data class InspectedMedia(
        val kind: MediaKind,
        val displayName: String,
        val mimeType: String,
        val sizeBytes: Long,
        val durationUs: Long?,
        val width: Int?,
        val height: Int?,
        val rotation: Int,
        val fps: Rational?,
        val hdr: Boolean,
        val fingerprint: String,
    )

    private fun inspectMedia(resolver: ContentResolver, uri: Uri): InspectedMedia {
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        val kind = when {
            mime.startsWith("video/") -> MediaKind.VIDEO
            mime.startsWith("audio/") -> MediaKind.AUDIO
            mime.startsWith("image/") -> MediaKind.IMAGE
            else -> error("Unsupported media type: $mime")
        }
        require(kind != MediaKind.IMAGE) { "Image import into the primary timeline is not supported yet" }
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull()?.let { ((it % 360) + 360) % 360 } ?: 0
            val fpsValue = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toDoubleOrNull()
            val fps = fpsValue?.takeIf { it > 0 }?.let(::toRationalFps)
            val size = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length.coerceAtLeast(0L) } ?: 0L
            val displayName = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "Media"
            val fingerprint = quickFingerprint(resolver, uri, mime, size, durationMs ?: 0)
            InspectedMedia(kind, displayName, mime, size, durationMs?.times(1_000), width, height, rotation, fps, false, fingerprint)
        } finally {
            retriever.release()
        }
    }

    private fun quickFingerprint(resolver: ContentResolver, uri: Uri, mime: String, size: Long, durationMs: Long): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("v1|$mime|$size|$durationMs|".toByteArray())
        runCatching {
            resolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(64 * 1024)
                val read = input.read(buffer)
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun toRationalFps(value: Double): Rational {
        val known = listOf(Rational.FPS_23_976, Rational.FPS_24, Rational.FPS_25, Rational.FPS_29_97, Rational.FPS_30, Rational.FPS_50, Rational.FPS_59_94, Rational.FPS_60)
        return known.minByOrNull { kotlin.math.abs(it.asDouble() - value) }?.takeIf { kotlin.math.abs(it.asDouble() - value) < 0.05 }
            ?: Rational(kotlin.math.round(value).toInt().coerceAtLeast(1), 1)
    }

    private fun parseJson(value: String?): JSONObject = runCatching { JSONObject(value ?: "{}") }.getOrElse { JSONObject() }
    private fun jsonStringList(value: String): List<String> = runCatching {
        val array = JSONArray(value)
        List(array.length()) { array.optString(it) }.filter(String::isNotBlank)
    }.getOrDefault(emptyList())
    private fun jsonNumberMap(value: String): Map<String, Double> {
        val obj = parseJson(value)
        return obj.keys().asSequence().mapNotNull { key -> obj.optDouble(key, Double.NaN).takeIf(Double::isFinite)?.let { key to it } }.toMap()
    }
    private inline fun <reified T : Enum<T>> enumOrDefault(value: String?, default: T): T = runCatching { enumValueOf<T>(value.orEmpty()) }.getOrDefault(default)
    private fun detectRtl(text: String) = text.count { it in '\u0590'..'\u08FF' || it in '\uFB1D'..'\uFDFF' || it in '\uFE70'..'\uFEFF' } > text.count { it in 'A'..'Z' || it in 'a'..'z' }

    private fun encodeTransformNode(value: TransformNode) = JSONObject()
        .put("positionX", value.positionX).put("positionY", value.positionY)
        .put("scaleX", value.scaleX).put("scaleY", value.scaleY).put("rotationDegrees", value.rotationDegrees)
        .put("cropLeft", value.cropLeft).put("cropTop", value.cropTop).put("cropRight", value.cropRight).put("cropBottom", value.cropBottom).toString()
    private fun decodeTransformNode(value: String): TransformNode = parseJson(value).let { obj -> TransformNode(
        obj.optDouble("positionX", .5).toFloat(), obj.optDouble("positionY", .5).toFloat(),
        obj.optDouble("scaleX", 1.0).toFloat().coerceAtLeast(.01f), obj.optDouble("scaleY", 1.0).toFloat().coerceAtLeast(.01f),
        obj.optDouble("rotationDegrees", 0.0).toFloat(), obj.optDouble("cropLeft", 0.0).toFloat().coerceIn(0f, .99f),
        obj.optDouble("cropTop", 0.0).toFloat().coerceIn(0f, .99f), obj.optDouble("cropRight", 1.0).toFloat().coerceIn(.01f, 1f),
        obj.optDouble("cropBottom", 1.0).toFloat().coerceIn(.01f, 1f),
    ) }
    private fun encodeCreativeTransform(value: CreativeTransform) = JSONObject()
        .put("positionX", value.positionX).put("positionY", value.positionY).put("scaleX", value.scaleX).put("scaleY", value.scaleY)
        .put("rotationDegrees", value.rotationDegrees).put("anchorX", value.anchorX).put("anchorY", value.anchorY).put("opacity", value.opacity).toString()
    private fun decodeCreativeTransform(value: String): CreativeTransform = parseJson(value).let { obj -> CreativeTransform(
        obj.optDouble("positionX", .5).toFloat(), obj.optDouble("positionY", .5).toFloat(),
        obj.optDouble("scaleX", 1.0).toFloat().coerceAtLeast(.01f), obj.optDouble("scaleY", 1.0).toFloat().coerceAtLeast(.01f),
        obj.optDouble("rotationDegrees", 0.0).toFloat(), obj.optDouble("anchorX", .5).toFloat().coerceIn(0f, 1f),
        obj.optDouble("anchorY", .5).toFloat().coerceIn(0f, 1f), obj.optDouble("opacity", 1.0).toFloat().coerceIn(0f, 1f),
    ) }

    companion object {
        fun create(context: Context): ProductionProjectRepository {
            val app = context.applicationContext
            val db = Room.databaseBuilder(app, MoatazVidDatabase::class.java, MoatazVidDatabase.FILE_NAME)
                .addMigrations(*DatabaseMigrations.ALL)
                .build()
            return ProductionProjectRepository(app, db)
        }
    }
}
