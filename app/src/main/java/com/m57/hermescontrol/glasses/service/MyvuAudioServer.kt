package com.m57.hermescontrol.glasses.service
import android.util.Log
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * One authenticated local-network consumer for 16 kHz mono PCM16 from MYVU.
 *
 * Authentication happens before any PCM is written, so unauthenticated
 * connections cannot distinguish an active microphone session from an idle port.
 */
class MyvuAudioServer(
    private val token: String,
    private val listenPort: Int = DEFAULT_PORT,
    private val maxQueuedFrames: Int = MAX_QUEUED_FRAMES,
    private val onClientReady: () -> Unit = {},
    private val onTransportFailure: (AudioTransportFailure) -> Unit = {},
    private val outputFactory: (Socket) -> OutputStream = { socket ->
        BufferedOutputStream(socket.getOutputStream())
    },
) : Closeable {
    private val lock = Any()
    private val executor: ExecutorService =
        Executors.newFixedThreadPool(2) { runnable ->
            Thread(runnable, "myvu-audio-server").apply { isDaemon = true }
        }
    private val frames = ArrayBlockingQueue<ByteArray>(maxQueuedFrames)

    @Volatile
    private var serverSocket: ServerSocket? = null
    private var session: AudioSession? = null
    val boundPort: Int
        get() = serverSocket?.localPort ?: -1

    fun start() {
        check(token.isNotBlank()) { "MYVU audio token is required" }
        synchronized(lock) {
            if (serverSocket != null) return
            serverSocket = ServerSocket(listenPort)
            executor.execute(::acceptLoop)
            executor.execute(::writeLoop)
            logInfo("MYVU_SERVER listening port=${serverSocket?.localPort}")
        }
    }

    fun publish(pcm: ByteArray): Boolean = publish(pcm, pcm.size)

    fun publish(
        pcm: ByteArray,
        size: Int,
    ): Boolean {
        require(size in 1..pcm.size) { "Invalid PCM buffer size: $size" }
        val overflowedSession =
            synchronized(lock) {
                if (session == null) return false
                if (frames.offer(pcm.copyOf(size))) return true
                clearSessionLocked()
            }
        closeSession(overflowedSession)
        notifyFailure(AudioTransportFailure.OVERFLOW)
        return false
    }

    override fun close() {
        val closed =
            synchronized(lock) {
                val activeSession = clearSessionLocked()
                val listeningSocket = serverSocket
                serverSocket = null
                activeSession to listeningSocket
            }
        closeSession(closed.first)
        closed.second?.close()
        executor.shutdownNow()
    }

    private fun writeLoop() {
        while (!Thread.currentThread().isInterrupted) {
            val frame =
                try {
                    frames.take()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
            val activeSession = synchronized(lock) { session } ?: continue
            try {
                activeSession.output.write(frame)
                activeSession.output.flush()
            } catch (error: Exception) {
                logWarn("MYVU_SERVER client write failed", error)
                val disconnected = clearSessionIfCurrent(activeSession)
                closeSession(activeSession)
                if (disconnected) notifyFailure(AudioTransportFailure.DISCONNECTED)
            }
        }
    }

    private fun acceptLoop() {
        while (!Thread.currentThread().isInterrupted) {
            val socket =
                try {
                    serverSocket?.accept() ?: return
                } catch (_: Exception) {
                    return
                }
            logInfo("MYVU_SERVER client accepted remote=${socket.remoteSocketAddress}")
            serve(socket)
        }
    }

    private fun serve(socket: Socket) {
        var acceptedSession: AudioSession? = null
        try {
            val authorized = isAuthorized(socket.getInputStream())
            logInfo("MYVU_SERVER client authorized=$authorized")
            if (!authorized) return
            acceptedSession = AudioSession(socket, outputFactory(socket))
            val replacedSession =
                synchronized(lock) {
                    val activeSession = clearSessionLocked()
                    session = acceptedSession
                    activeSession
                }
            closeSession(replacedSession)
            logInfo("MYVU_SERVER raw PCM stream ready sampleRate=16000 channels=1 encoding=PCM_16BIT")
            onClientReady()
        } catch (error: Exception) {
            logWarn("MYVU_SERVER client failed", error)
            if (acceptedSession == null || !clearSessionIfCurrent(acceptedSession)) {
                socket.close()
            } else {
                closeSession(acceptedSession)
            }
        } finally {
            if (acceptedSession == null) socket.close()
        }
    }

    private fun isAuthorized(input: InputStream): Boolean {
        val candidate = ByteArray(MAX_TOKEN_BYTES)
        var size = 0
        while (size < candidate.size) {
            val next = input.read()
            if (next == -1 || next == '\n'.code) break
            if (next != '\r'.code) candidate[size++] = next.toByte()
        }
        if (size == candidate.size) return false
        return MessageDigest.isEqual(token.encodeToByteArray(), candidate.copyOf(size))
    }

    private fun logInfo(message: String) {
        try {
            Log.i(TAG, message)
        } catch (_: RuntimeException) {
            // android.jar's local-unit-test stubs intentionally throw.
        }
    }

    private fun notifyFailure(failure: AudioTransportFailure) {
        onTransportFailure(failure)
    }

    private fun logWarn(
        message: String,
        error: Exception,
    ) {
        try {
            Log.w(TAG, message, error)
        } catch (_: RuntimeException) {
            // android.jar's local-unit-test stubs intentionally throw.
        }
    }

    private fun clearSessionLocked(): AudioSession? {
        val activeSession = session
        session = null
        frames.clear()
        return activeSession
    }

    private fun clearSessionIfCurrent(activeSession: AudioSession): Boolean =
        synchronized(lock) {
            if (session !== activeSession) {
                false
            } else {
                clearSessionLocked()
                true
            }
        }

    private fun closeSession(session: AudioSession?) {
        if (session == null) return
        try {
            session.socket.close()
        } catch (_: Exception) {
        }
        try {
            session.output.close()
        } catch (_: Exception) {
        }
    }

    private data class AudioSession(
        val socket: Socket,
        val output: OutputStream,
    )

    private companion object {
        const val DEFAULT_PORT = 8932
        const val MAX_TOKEN_BYTES = 256
        const val MAX_QUEUED_FRAMES = 32

        const val TAG = "HermesMyvuServer"
    }
}

enum class AudioTransportFailure {
    OVERFLOW,
    DISCONNECTED,
}
