package com.moatazvid.app

import androidx.room3.RoomDatabase
import androidx.room3.withWriteTransaction as roomWriteTransaction

/** Keeps Room3 transaction calls explicit and available to production repositories in this package. */
suspend fun <R> RoomDatabase.withWriteTransaction(block: suspend () -> R): R =
    roomWriteTransaction { block() }
