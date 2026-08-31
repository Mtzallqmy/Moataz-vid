package com.moatazvid.storage.room

import androidx.room3.Database
import androidx.room3.RoomDatabase

@Database(
    entities = [
        ProjectEntity::class, SequenceEntity::class, FileReferenceEntity::class,
        MediaSourceEntity::class, TrackEntity::class, ClipEntity::class,
        ClipPropertiesEntity::class, CaptionEntity::class, OverlayEntity::class,
        EffectEntity::class, TransitionEntity::class, AssetEntity::class,
        ProjectConstraintEntity::class, ProtectedRangeEntity::class,
        TranscriptEntity::class, AnalysisEntity::class, ProxyEntity::class,
        EditTransactionEntity::class, HistoryCursorEntity::class, ExportRecordEntity::class,
        TranscriptSegmentEntity::class, TranscriptWordEntity::class,
        TranscriptionJobEntity::class, TranscriptionCheckpointEntity::class,
        SpeechModelPackEntity::class,
        AiProviderProfileEntity::class, AiModelAssignmentEntity::class, AiProviderPreferenceEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class MoatazVidDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao
    abstract fun timelineDao(): TimelineDao
    abstract fun mediaDao(): MediaDao
    abstract fun transactionDao(): TransactionDao
    abstract fun cacheDao(): CacheDao
    abstract fun exportDao(): ExportDao
    abstract fun speechDao(): SpeechDao
    abstract fun aiProviderDao(): AiProviderDao

    companion object {
        const val FILE_NAME = "moataz_vid.db"
        const val SCHEMA_VERSION = 3
    }
}
