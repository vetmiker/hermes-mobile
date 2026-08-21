package com.m57.hermescontrol.glasses.service

import com.m57.hermescontrol.glasses.TranscriptAcceptance
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Narrow active-session-only HTTP surface. It never starts microphone capture. */
class MyvuControlServer(
    private val token: String,
    private val health: () -> JSONObject,
    private val display: (String) -> Unit,
    private val transcript: (Long, String, String, String) -> TranscriptAcceptance,
    private val transcriptEnded: () -> Unit,
    private val control: (String) -> Boolean,
    private val listenPort: Int = PORT,
    private val readTimeoutMillis: Int = READ_TIMEOUT_MILLIS,
) : AutoCloseable {
    private val acceptExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val executor: ThreadPoolExecutor =
        ThreadPoolExecutor(
            MAX_WORKERS,
            MAX_WORKERS,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(MAX_QUEUED_CLIENTS),
        )
    private var server: ServerSocket? = null
    val boundPort: Int
        get() = checkNotNull(server).localPort

    @Synchronized
    fun start() {
        check(server == null) { "control server already started" }
        server = ServerSocket(listenPort, MAX_PENDING_CLIENTS, InetAddress.getByName("0.0.0.0"))
        acceptExecutor.execute {
            while (!Thread.currentThread().isInterrupted) {
                val socket =
                    try {
                        server?.accept()
                    } catch (_: Exception) {
                        null
                    } ?: break
                try {
                    executor.execute { socket.use(::handle) }
                } catch (_: RejectedExecutionException) {
                    socket.use { rejected ->
                        runCatching {
                            BufferedOutputStream(rejected.getOutputStream()).respond(503)
                        }
                    }
                }
            }
        }
    }

    private fun handle(socket: Socket) {
        socket.soTimeout = readTimeoutMillis
        val input = BufferedInputStream(socket.getInputStream())
        val output = BufferedOutputStream(socket.getOutputStream())
        try {
            val requestLine = input.readLine(MAX_HEADER_LINE_BYTES) ?: return
            val headers = mutableMapOf<String, String>()
            var headerCount = 0
            while (true) {
                if (headerCount == MAX_HEADER_COUNT) return output.respond(400)
                val line = input.readLine(MAX_HEADER_LINE_BYTES) ?: return
                if (line.isEmpty()) break
                headerCount += 1
                val separator = line.indexOf(':')
                if (separator > 0) {
                    headers[line.substring(0, separator).lowercase()] = line.substring(separator + 1).trim()
                }
            }
            if (headers["authorization"] != "Bearer $token") return output.respond(401)
            val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
            if (contentLength < 0) return output.respond(400)
            if (contentLength > MAX_BODY_BYTES) return output.respond(413)
            val bodyBytes = input.readExactly(contentLength) ?: return output.respond(400)
            val body = bodyBytes.toString(Charsets.UTF_8)
            val parts = requestLine.split(' ')
            if (parts.size < 2) return output.respond(400)
            when (parts[0] to parts[1]) {
                "GET" to "/health" -> output.respond(200, health().toString())
                "POST" to "/display" -> {
                    display(body)
                    output.respond(204)
                }
                "POST" to "/transcript" -> {
                    val value =
                        runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
                            ?: return output.respond(400)
                    val acceptance =
                        transcript(
                            value["generation"]?.jsonPrimitive?.longOrNull ?: Long.MIN_VALUE,
                            value["streamId"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                            value["utteranceId"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                            value["text"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        )
                    output.respond(if (acceptance.accepted || acceptance.ended) 202 else 409)
                    if (acceptance.ended) transcriptEnded()
                }
                "POST" to "/control" -> {
                    val action =
                        runCatching {
                            Json.parseToJsonElement(body).jsonObject["action"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        }.getOrDefault("")
                    output.respond(if (control(action)) 202 else 409)
                }
                else -> output.respond(404)
            }
        } catch (_: SocketTimeoutException) {
            output.respond(408)
        } catch (_: RequestTooLargeException) {
            output.respond(413)
        }
    }

    override fun close() {
        runCatching { server?.close() }
        server = null
        acceptExecutor.shutdownNow()
        executor.shutdownNow()
    }

    private fun BufferedInputStream.readLine(maxBytes: Int): String? {
        val bytes = ByteArrayOutputStream()
        while (true) {
            val next = read()
            if (next < 0) return null
            if (next == '\n'.code) return bytes.toString(Charsets.US_ASCII.name()).trimEnd('\r')
            if (bytes.size() == maxBytes) throw RequestTooLargeException()
            bytes.write(next)
        }
    }

    private fun BufferedInputStream.readExactly(size: Int): ByteArray? {
        if (size < 0) return null
        val bytes = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val count = read(bytes, offset, size - offset)
            if (count < 0) return null
            offset += count
        }
        return bytes
    }

    private fun BufferedOutputStream.respond(
        status: Int,
        body: String = "",
    ) {
        val reason =
            when (status) {
                200 -> "OK"
                202 -> "Accepted"
                204 -> "No Content"
                400 -> "Bad Request"
                401 -> "Unauthorized"
                404 -> "Not Found"
                408 -> "Request Timeout"
                409 -> "Conflict"
                413 -> "Payload Too Large"
                503 -> "Service Unavailable"
                else -> "Error"
            }
        val bodyBytes = body.toByteArray()
        val responseHeader =
            "HTTP/1.1 $status $reason\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: ${bodyBytes.size}\r\n" +
                "Connection: close\r\n\r\n"
        write(responseHeader.toByteArray())
        write(bodyBytes)
        flush()
    }

    private class RequestTooLargeException : Exception()

    companion object {
        const val PORT = 8931
        private const val MAX_PENDING_CLIENTS = 8
        private const val MAX_WORKERS = 4
        private const val MAX_QUEUED_CLIENTS = 8
        private const val MAX_HEADER_COUNT = 32
        private const val MAX_HEADER_LINE_BYTES = 4 * 1024
        private const val MAX_BODY_BYTES = 32 * 1024
        private const val READ_TIMEOUT_MILLIS = 5_000
    }
}
