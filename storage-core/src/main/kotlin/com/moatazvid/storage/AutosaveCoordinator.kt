package com.moatazvid.storage

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Coalesces high-frequency UI edits, while critical edits can force an immediate atomic flush.
 * The writer itself must use a database transaction and revision compare-and-swap.
 */
class AutosaveCoordinator<T>(
    scope: CoroutineScope,
    private val debounce: Duration = 250.milliseconds,
    private val writer: suspend (T) -> Unit,
) : AutoCloseable {
    private val channel = Channel<SaveRequest<T>>(Channel.UNLIMITED)
    // Respect the caller's dispatcher: production supplies an IO scope, while tests use virtual time.
    private val job = scope.launch {
        var pending: T? = null
        while (isActive) {
            val request = withTimeoutOrNull(debounce) { channel.receive() }
            when (request) {
                is SaveRequest.Debounced -> pending = request.value
                is SaveRequest.Immediate -> {
                    pending = null
                    writer(request.value)
                    request.ack.complete(Unit)
                }
                null -> pending?.let { writer(it) }.also { pending = null }
            }
        }
    }

    suspend fun schedule(value: T) = channel.send(SaveRequest.Debounced(value))

    suspend fun flush(value: T) {
        val ack = CompletableDeferred<Unit>()
        channel.send(SaveRequest.Immediate(value, ack))
        ack.await()
    }

    override fun close() {
        channel.close()
        job.cancel()
    }

    private sealed interface SaveRequest<T> {
        data class Debounced<T>(val value: T) : SaveRequest<T>
        data class Immediate<T>(val value: T, val ack: CompletableDeferred<Unit>) : SaveRequest<T>
    }
}
