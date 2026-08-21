package com.m57.hermescontrol.glasses.speech

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal class WhisperModelStore(
    context: Context,
    private val client: OkHttpClient? = null,
    private val artifacts: List<WhisperModelArtifact> = WhisperModelManifest.all,
    request: ((HttpUrl) -> HttpResponse)? = null,
    requestFactory: ((HttpUrl) -> CancellableHttpRequest)? = null,
    private val resolve: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).asList() },
) {
    internal class HttpResponse(
        val code: Int,
        val location: String?,
        val contentLength: Long,
        val body: InputStream?,
        private val onClose: () -> Unit = {},
    ) : Closeable {
        val isRedirect get() = code in 300..399

        override fun close() = onClose()
    }

    internal class CancellableHttpRequest(
        private val executeRequest: () -> HttpResponse,
        private val cancelRequest: () -> Unit = {},
    ) {
        fun execute(): HttpResponse = executeRequest()

        fun cancel() = cancelRequest()
    }

    private val defaultClient: OkHttpClient by lazy {
        client ?: OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .callTimeout(45, TimeUnit.SECONDS)
            .build()
    }
    private val newRequest: (HttpUrl) -> CancellableHttpRequest =
        requestFactory ?: { url ->
            request?.let { injected ->
                CancellableHttpRequest(executeRequest = { injected(url) })
            } ?: run {
                val call = defaultClient.newCall(Request.Builder().url(url).get().build())
                CancellableHttpRequest(
                    executeRequest = { call.execute().toHttpResponse(call) },
                    cancelRequest = call::cancel,
                )
            }
        }
    private val root = File(context.noBackupFilesDir, "whisper")
    private val mutex = Mutex()

    data class ReadyModels(
        val whisperPath: String,
        val vadPath: String,
    )

    sealed class Failure(message: String, cause: Throwable? = null) : Exception(message, cause) {
        class Network(message: String, cause: Throwable? = null) : Failure(message, cause)

        class Integrity(message: String) : Failure(message)

        class Storage(message: String, cause: Throwable? = null) : Failure(message, cause)
    }

    suspend fun prepare(onProgress: (String) -> Unit = {}): ReadyModels =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                cancellableTransfer { transfer ->
                    val paths =
                        artifacts.associateWith { artifact ->
                            currentCoroutineContext().ensureActive()
                            onProgress("Preparing ${artifact.id}")
                            ensureModel(artifact, transfer)
                        }
                    ReadyModels(
                        whisperPath = checkNotNull(paths[artifacts[0]]).absolutePath,
                        vadPath = checkNotNull(paths[artifacts[1]]).absolutePath,
                    )
                }
            }
        }

    private suspend fun ensureModel(
        artifact: WhisperModelArtifact,
        transfer: ActiveTransfer,
    ): File {
        currentCoroutineContext().ensureActive()
        val directory =
            File(root, artifact.id).also {
                if (!it.exists() && !it.mkdirs()) throw Failure.Storage("Could not create model storage")
            }
        directory.listFiles { candidate -> candidate.name.startsWith("${artifact.sha256}.tmp-") }?.forEach(File::delete)
        val finalFile = File(directory, artifact.fileName)
        if (isValid(finalFile, artifact)) return finalFile
        finalFile.delete()
        download(artifact, directory, finalFile, transfer)
        return finalFile
    }

    private suspend fun download(
        artifact: WhisperModelArtifact,
        directory: File,
        finalFile: File,
        transfer: ActiveTransfer,
    ) {
        val temporary = File(directory, "${artifact.sha256}.tmp-${UUID.randomUUID()}")
        try {
            var location = artifact.initialUrl
            repeat(MAX_REDIRECTS + 1) { hop ->
                currentCoroutineContext().ensureActive()
                if (location.scheme != "https") throw Failure.Network("Model request violates HTTPS policy")
                validateDestination(location.host, artifact)
                val request = newRequest(location)
                transfer.begin(request)
                try {
                    request.execute().use { response ->
                        transfer.open(response)
                        if (response.isRedirect) {
                            if (hop == MAX_REDIRECTS) throw Failure.Network("Too many model redirects")
                            val next =
                                response.location?.let(location::resolve)
                                    ?: throw Failure.Network("Model redirect has no location")
                            if (next.scheme != "https" || next.host !in artifact.redirectHosts) {
                                throw Failure.Network("Model redirect violates HTTPS host policy")
                            }
                            location = next
                            return@use
                        }
                        if (response.code !in 200..299) {
                            throw Failure.Network(
                                "Model request failed: HTTP ${response.code}",
                            )
                        }
                        val body = response.body ?: throw Failure.Network("Model response has no body")
                        if (response.contentLength !in 0..artifact.sizeBytes) {
                            throw Failure.Integrity("Model response exceeds pinned size")
                        }
                        val digest = MessageDigest.getInstance("SHA-256")
                        var bytes = 0L
                        body.use { input ->
                            FileOutputStream(temporary).use { output ->
                                val buffer = ByteArray(BUFFER_BYTES)
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val read = input.read(buffer)
                                    currentCoroutineContext().ensureActive()
                                    if (read < 0) break
                                    bytes += read
                                    if (bytes > artifact.sizeBytes) {
                                        throw Failure.Integrity(
                                            "Model download exceeds pinned size",
                                        )
                                    }
                                    digest.update(buffer, 0, read)
                                    output.write(buffer, 0, read)
                                }
                                output.fd.sync()
                            }
                        }
                        currentCoroutineContext().ensureActive()
                        if (bytes != artifact.sizeBytes || digest.digest().toHex() != artifact.sha256) {
                            throw Failure.Integrity("Model digest or size did not match the pinned artifact")
                        }
                        transfer.promote { moveAtomically(temporary, finalFile) }
                        return
                    }
                } finally {
                    transfer.complete(request)
                }
            }
            throw Failure.Network("Model redirect did not resolve")
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Failure) {
            throw failure
        } catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            throw Failure.Network("Model download failed", failure)
        } finally {
            temporary.delete()
        }
    }

    private fun validateDestination(
        host: String,
        artifact: WhisperModelArtifact,
    ) {
        if (host !in artifact.redirectHosts) throw Failure.Network("Model host is not allowlisted")
        val addresses =
            runCatching { resolve(host) }
                .getOrElse { throw Failure.Network("Could not resolve model host", it) }
        if (addresses.isEmpty() || addresses.any(::isPrivateAddress)) {
            throw Failure.Network("Model host resolved to a non-public address")
        }
    }

    private fun isValid(
        file: File,
        artifact: WhisperModelArtifact,
    ): Boolean {
        if (!file.isFile || file.length() != artifact.sizeBytes) return false
        return runCatching { digest(file).toHex() == artifact.sha256 }.getOrDefault(false)
    }

    private fun digest(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) return digest.digest()
                digest.update(buffer, 0, read)
            }
        }
    }

    private fun moveAtomically(
        source: File,
        target: File,
    ) {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            throw Failure.Storage("Model filesystem does not support atomic promotion")
        } catch (failure: Exception) {
            throw Failure.Storage("Could not promote verified model", failure)
        }
    }

    private fun isPrivateAddress(address: InetAddress): Boolean =
        address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress

    private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }

    private fun Response.toHttpResponse(call: Call): HttpResponse =
        HttpResponse(
            code = code,
            location = header("Location"),
            contentLength = body?.contentLength() ?: -1,
            body = body?.byteStream(),
            onClose = {
                close()
                call.cancel()
            },
        )

    private suspend fun <T> cancellableTransfer(block: suspend (ActiveTransfer) -> T): T =
        coroutineScope {
            val transfer = ActiveTransfer()
            val cancellationWatcher =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        awaitCancellation()
                    } finally {
                        transfer.cancel()
                    }
                }
            try {
                block(transfer)
            } finally {
                transfer.cancel()
                cancellationWatcher.cancel()
            }
        }

    private class ActiveTransfer {
        private val request = AtomicReference<CancellableHttpRequest?>()
        private val response = AtomicReference<HttpResponse?>()
        private val lock = Any()
        private var cancelled = false

        fun begin(value: CancellableHttpRequest) {
            request.set(value)
        }

        fun open(value: HttpResponse) {
            response.set(value)
        }

        fun complete(value: CancellableHttpRequest) {
            response.set(null)
            request.compareAndSet(value, null)
        }

        fun promote(action: () -> Unit) {
            synchronized(lock) {
                if (cancelled) throw CancellationException("Model transfer cancelled")
                action()
            }
        }

        fun cancel() {
            val active =
                synchronized(lock) {
                    cancelled = true
                    request.getAndSet(null) to response.getAndSet(null)
                }
            active.first?.cancel()
            active.second?.close()
        }
    }

    private companion object {
        const val BUFFER_BYTES = 32 * 1024
        const val MAX_REDIRECTS = 3
    }
}
