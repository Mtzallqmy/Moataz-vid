package com.moatazvid.storage.room

import androidx.room3.migration.Migration
import androidx.sqlite.execSQL

object DatabaseMigrations {
    val FROM_1_TO_2 = Migration(1, 2) { db ->
        db.execSQL("CREATE TABLE IF NOT EXISTS transcript_segments (segmentId TEXT NOT NULL PRIMARY KEY, transcriptId TEXT NOT NULL, sourceId TEXT NOT NULL, segmentIndex INTEGER NOT NULL, startUs INTEGER NOT NULL, endUs INTEGER NOT NULL, text TEXT NOT NULL, normalizedSearchText TEXT NOT NULL, speakerId TEXT, confidence REAL, FOREIGN KEY(transcriptId) REFERENCES transcripts(transcriptId) ON DELETE CASCADE)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_transcript_segments_transcriptId_segmentIndex ON transcript_segments(transcriptId, segmentIndex)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_transcript_segments_sourceId_startUs ON transcript_segments(sourceId, startUs)")
        db.execSQL("CREATE TABLE IF NOT EXISTS transcript_words (wordId TEXT NOT NULL PRIMARY KEY, transcriptId TEXT NOT NULL, segmentId TEXT NOT NULL, sourceId TEXT NOT NULL, wordIndex INTEGER NOT NULL, text TEXT NOT NULL, normalizedSearchText TEXT NOT NULL, startUs INTEGER NOT NULL, endUs INTEGER NOT NULL, confidence REAL, languageTag TEXT NOT NULL, speakerId TEXT, wordType TEXT NOT NULL, FOREIGN KEY(transcriptId) REFERENCES transcripts(transcriptId) ON DELETE CASCADE)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_transcript_words_transcriptId_wordIndex ON transcript_words(transcriptId, wordIndex)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_transcript_words_sourceId_startUs ON transcript_words(sourceId, startUs)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_transcript_words_normalizedSearchText ON transcript_words(normalizedSearchText)")
        db.execSQL("CREATE TABLE IF NOT EXISTS transcription_jobs (jobId TEXT NOT NULL PRIMARY KEY, sourceId TEXT NOT NULL, streamId TEXT, modelPackId TEXT NOT NULL, languageTag TEXT NOT NULL, sourceFingerprint TEXT NOT NULL, status TEXT NOT NULL, currentChunk INTEGER NOT NULL, totalChunks INTEGER, progressPermille INTEGER NOT NULL, createdAtEpochMs INTEGER NOT NULL, updatedAtEpochMs INTEGER NOT NULL, errorCode TEXT, FOREIGN KEY(sourceId) REFERENCES media_sources(sourceId) ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_transcription_jobs_sourceId ON transcription_jobs(sourceId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_transcription_jobs_status ON transcription_jobs(status)")
        db.execSQL("CREATE TABLE IF NOT EXISTS transcription_checkpoints (jobId TEXT NOT NULL PRIMARY KEY, sourceFingerprint TEXT NOT NULL, completedChunkExclusive INTEGER NOT NULL, committedWordCount INTEGER NOT NULL, committedSegmentCount INTEGER NOT NULL, lastCommittedSourceTimeUs INTEGER NOT NULL, updatedAtEpochMs INTEGER NOT NULL, FOREIGN KEY(jobId) REFERENCES transcription_jobs(jobId) ON DELETE CASCADE)")
        db.execSQL("CREATE TABLE IF NOT EXISTS speech_model_packs (modelPackId TEXT NOT NULL PRIMARY KEY, displayName TEXT NOT NULL, version TEXT NOT NULL, sizeBytes INTEGER NOT NULL, requiredRamBytes INTEGER NOT NULL, multilingual INTEGER NOT NULL, languagesJson TEXT NOT NULL, sha256 TEXT NOT NULL, relativePath TEXT NOT NULL, license TEXT NOT NULL, sourceUrl TEXT NOT NULL, status TEXT NOT NULL, activeLeaseCount INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_speech_model_packs_status ON speech_model_packs(status)")
    }

    val FROM_2_TO_3 = Migration(2, 3) { db ->
        db.execSQL("CREATE TABLE IF NOT EXISTS ai_provider_profiles (providerId TEXT NOT NULL PRIMARY KEY, displayName TEXT NOT NULL, providerType TEXT NOT NULL, baseUrl TEXT NOT NULL, apiKeyReference TEXT, defaultModel TEXT, modelsPath TEXT NOT NULL, chatPath TEXT NOT NULL, responsesPath TEXT NOT NULL, authMode TEXT NOT NULL, customAuthHeader TEXT, customHeadersJson TEXT NOT NULL, extraBodyJson TEXT NOT NULL, timeoutMs INTEGER NOT NULL, retries INTEGER NOT NULL, enabled INTEGER NOT NULL, priorityIndex INTEGER NOT NULL, updatedAtEpochMs INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_ai_provider_profiles_enabled_priorityIndex ON ai_provider_profiles(enabled, priorityIndex)")
        db.execSQL("CREATE TABLE IF NOT EXISTS ai_model_assignments (role TEXT NOT NULL PRIMARY KEY, providerId TEXT NOT NULL, modelId TEXT NOT NULL, FOREIGN KEY(providerId) REFERENCES ai_provider_profiles(providerId) ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_ai_model_assignments_providerId ON ai_model_assignments(providerId)")
        db.execSQL("CREATE TABLE IF NOT EXISTS ai_provider_preferences (`key` TEXT NOT NULL PRIMARY KEY, value TEXT)")
    }

    val ALL = arrayOf(FROM_1_TO_2, FROM_2_TO_3)
}
