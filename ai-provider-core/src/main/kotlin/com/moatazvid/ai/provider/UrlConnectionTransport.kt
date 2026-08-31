package com.moatazvid.ai.provider

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream

/** Dependency-free HTTP implementation; app composition may replace it with OkHttp without changing providers. */
class UrlConnectionHttpTransport : HttpTransport {
    private val active = ConcurrentHashMap<RequestId, HttpURLConnection>()

    override suspend fun execute(request: HttpRequest): TransportResult<HttpResponse> = withContext(Dispatchers.IO) {
        val connection = runCatching { open(request) }.getOrElse { return@withContext TransportResult.Failure(it.message ?: "Invalid URL") }
        active[request.requestId] = connection
        try {
            writeBody(connection, request.body)
            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            val body = stream?.let { input ->
                val decoded = if (connection.contentEncoding.equals("gzip", true)) GZIPInputStream(input) else input
                decoded.bufferedReader(Charsets.UTF_8).use { it.readText() }
            }.orEmpty()
            TransportResult.Success(HttpResponse(status, connection.headerFields.filterKeys { it != null }.mapValues { it.value.joinToString(",") }, body))
        } catch (_: SocketTimeoutException) { TransportResult.Failure("Request timed out", timeout = true) }
        catch (failure: Throwable) { TransportResult.Failure(failure.message ?: "Network failure") }
        finally { active.remove(request.requestId); connection.disconnect() }
    }

    override fun stream(request: HttpRequest): Flow<TransportStreamEvent> = flow {
        val connection = try { open(request) } catch (failure: Throwable) { emit(TransportStreamEvent.Failure(failure.message ?: "Invalid URL")); return@flow }
        active[request.requestId] = connection
        try {
            withContext(Dispatchers.IO) {
                writeBody(connection, request.body)
                if (connection.responseCode !in 200..299) {
                    emit(TransportStreamEvent.Failure("Streaming HTTP ${connection.responseCode}")); return@withContext
                }
                connection.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines -> lines.forEach { emit(TransportStreamEvent.Data(it)) } }
            }
            emit(TransportStreamEvent.Closed)
        } catch (failure: Throwable) { emit(TransportStreamEvent.Failure(failure.message ?: "Streaming failed")) }
        finally { active.remove(request.requestId); connection.disconnect() }
    }

    override suspend fun cancel(requestId: RequestId): Boolean = active.remove(requestId)?.let { it.disconnect(); true } ?: false

    private fun open(request: HttpRequest): HttpURLConnection = (URL(request.url).openConnection() as HttpURLConnection).apply {
        requestMethod = request.method; connectTimeout = request.timeoutMs.toInt(); readTimeout = request.timeoutMs.toInt()
        useCaches = false; setRequestProperty("Accept-Encoding", "gzip")
        request.headers.forEach(::setRequestProperty)
        doOutput = request.body != null
    }
    private fun writeBody(connection: HttpURLConnection, body: String?) { if (body != null) connection.outputStream.use { it.write(body.encodeToByteArray()) } }
}
