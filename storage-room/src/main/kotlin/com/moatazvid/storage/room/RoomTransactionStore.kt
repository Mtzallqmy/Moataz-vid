package com.moatazvid.storage.room

import androidx.room3.withWriteTransaction

/** The only write gateway allowed to mutate Timeline and history together. */
class RoomTransactionStore(private val database: MoatazVidDatabase) {
    suspend fun commit(
        sequenceId: String,
        expectedRevision: Long,
        transaction: EditTransactionEntity,
        mutate: suspend TimelineDao.() -> Unit,
    ) {
        database.withWriteTransaction {
            val timeline = database.timelineDao()
            timeline.mutate()
            check(
                timeline.compareAndSetRevision(
                    sequenceId = sequenceId,
                    expectedRevision = expectedRevision,
                    resultRevision = transaction.resultRevision,
                    updatedAt = transaction.createdAtEpochMs,
                ) == 1
            ) { "Timeline revision conflict" }
            database.transactionDao().insert(transaction)
            database.transactionDao().setCursor(
                HistoryCursorEntity(
                    sequenceId = sequenceId,
                    currentTransactionId = transaction.transactionId,
                    activeBranchId = transaction.branchId,
                    updatedAtEpochMs = transaction.createdAtEpochMs,
                )
            )
        }
    }
}
