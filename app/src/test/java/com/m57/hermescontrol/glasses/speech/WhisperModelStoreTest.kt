package com.m57.hermescontrol.glasses.speech

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class WhisperModelStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun direct_payload_promotes_once_and_cached_prepare_makes_no_requests() =
        runTest {
            val payload = "model".encodeToByteArray()
            val requests = mutableListOf<String>()
            val store = store(payload, requests)

            val first = store.prepare()
            val requestsAfterFirstPrepare = requests.size
            val second = store.prepare()

            assertEquals(2, requestsAfterFirstPrepare)
            assertEquals(requestsAfterFirstPrepare, requests.size)
            assertEquals(first, second)
            assertTrue(File(first.whisperPath).isFile)
            assertTrue(File(first.vadPath).isFile)
        }

    @Test
    fun allowlisted_redirect_promotes_and_http_downgrade_fails_closed() =
        runTest {
            val payload = "model".encodeToByteArray()
            val artifact = artifact("https://models.example/first", payload)
            val secondArtifact = artifact.copy(id = "vad", url = "https://models.example/vad")
            val responses =
                mapOf(
                    "https://models.example/first" to response(302, location = "https://cdn.example/model"),
                    "https://cdn.example/model" to response(200, payload = payload),
                    "https://models.example/vad" to response(200, payload = payload),
                )
            val store =
                store(
                    listOf(artifact, secondArtifact),
                    mutableListOf(),
                ) { url ->
                    checkNotNull(responses[url.toString()])
                }

            store.prepare()
            val downgradedArtifact = artifact.copy(id = "downgraded")
            val downgraded =
                store(listOf(downgradedArtifact, secondArtifact), mutableListOf()) {
                    response(302, location = "http://cdn.example/model")
                }
            val failure = runCatching { downgraded.prepare() }.exceptionOrNull()
            assertTrue(failure is WhisperModelStore.Failure.Network)
            assertFalse(
                File(temporaryFolder.root, "whisper/${downgradedArtifact.id}/${downgradedArtifact.fileName}").exists(),
            )
        }

    @Test
    fun pinned_hugging_face_artifact_accepts_its_aws_delivery_redirect() =
        runTest {
            val payload = "model".encodeToByteArray()
            val whisper =
                WhisperModelManifest.whisper.copy(
                    id = "pinned-whisper",
                    sizeBytes = payload.size.toLong(),
                    sha256 = MessageDigest.getInstance("SHA-256").digest(payload).toHex(),
                )
            val vad =
                WhisperModelManifest.vad.copy(
                    id = "pinned-vad",
                    sizeBytes = payload.size.toLong(),
                    sha256 = MessageDigest.getInstance("SHA-256").digest(payload).toHex(),
                )
            val store =
                store(listOf(whisper, vad), mutableListOf()) { url ->
                    if (url == whisper.initialUrl) {
                        response(302, location = "https://us.aws.cdn.hf.co/pinned-whisper")
                    } else {
                        response(200, payload = payload)
                    }
                }

            store.prepare()
        }

    @Test
    fun truncated_oversize_and_wrong_hash_never_promote() =
        runTest {
            val payload = "model".encodeToByteArray()
            listOf(
                response(200, payload = payload.copyOf(payload.size - 1)),
                response(200, payload = payload + byteArrayOf(1)),
                response(200, payload = payload, declaredLength = payload.size.toLong()),
            ).forEachIndexed { index, reply ->
                val artifact =
                    artifact("https://models.example/$index", payload).let {
                        if (index == 2) it.copy(sha256 = "0".repeat(64)) else it
                    }
                val store = store(listOf(artifact), mutableListOf()) { reply }

                assertTrue(runCatching { store.prepare() }.exceptionOrNull() is WhisperModelStore.Failure.Integrity)
                assertFalse(File(temporaryFolder.root, "whisper/${artifact.id}/${artifact.fileName}").exists())
            }
        }

    @Test
    fun cancelling_a_stalled_body_closes_it_before_any_model_is_promoted() =
        runBlocking {
            val payload = "model".encodeToByteArray()
            val artifact = artifact("https://models.example/stalled", payload)
            val body = StalledInputStream()
            val store =
                store(listOf(artifact, artifact.copy(id = "vad")), mutableListOf()) {
                    WhisperModelStore.HttpResponse(
                        code = 200,
                        location = null,
                        contentLength = payload.size.toLong(),
                        body = body,
                        onClose = body::release,
                    )
                }
            val transfer = async(Dispatchers.IO) { store.prepare() }

            assertTrue(body.started.await(2, TimeUnit.SECONDS))
            transfer.cancel()
            try {
                withTimeout(250) { transfer.join() }
            } finally {
                body.release()
                transfer.cancelAndJoin()
            }

            assertFalse(
                File(temporaryFolder.root, "whisper/${artifact.id}/${artifact.fileName}").exists(),
            )
        }

    @Test
    fun cancelling_a_stalled_call_cancels_the_active_request_before_any_model_is_promoted() =
        runBlocking {
            val payload = "model".encodeToByteArray()
            val artifact = artifact("https://models.example/stalled-call", payload)
            val requestStarted = CountDownLatch(1)
            val requestCancelled = CountDownLatch(1)
            val releaseRequest = CountDownLatch(1)
            val context = mockk<Context>()
            every { context.noBackupFilesDir } returns temporaryFolder.root
            val store =
                WhisperModelStore(
                    context = context,
                    artifacts = listOf(artifact, artifact.copy(id = "vad")),
                    requestFactory = {
                        WhisperModelStore.CancellableHttpRequest(
                            executeRequest = {
                                requestStarted.countDown()
                                releaseRequest.await()
                                throw IOException("request cancelled")
                            },
                            cancelRequest = {
                                requestCancelled.countDown()
                                releaseRequest.countDown()
                            },
                        )
                    },
                    resolve = { listOf(InetAddress.getByName("8.8.8.8")) },
                )
            val transfer = async(Dispatchers.IO) { store.prepare() }

            assertTrue(requestStarted.await(2, TimeUnit.SECONDS))
            transfer.cancel()
            withTimeout(250) { transfer.join() }

            assertTrue(requestCancelled.await(2, TimeUnit.SECONDS))
            assertFalse(
                File(temporaryFolder.root, "whisper/${artifact.id}/${artifact.fileName}").exists(),
            )
        }

    private fun store(
        payload: ByteArray,
        requests: MutableList<String>,
    ): WhisperModelStore {
        val artifacts =
            listOf(artifact("https://models.example/whisper", payload), artifact("https://models.example/vad", payload))
        return store(artifacts, requests) {
            response(200, payload = payload)
        }
    }

    private fun store(
        artifacts: List<WhisperModelArtifact>,
        requests: MutableList<String>,
        response: (okhttp3.HttpUrl) -> WhisperModelStore.HttpResponse,
    ): WhisperModelStore {
        val context = mockk<Context>()
        every { context.noBackupFilesDir } returns temporaryFolder.root
        return WhisperModelStore(
            context = context,
            artifacts = artifacts,
            request = { url ->
                requests += url.toString()
                response(url)
            },
            resolve = { listOf(InetAddress.getByName("8.8.8.8")) },
        )
    }

    private fun artifact(
        url: String,
        payload: ByteArray,
    ): WhisperModelArtifact =
        WhisperModelArtifact(
            id = url.substringAfterLast('/'),
            url = url,
            sizeBytes = payload.size.toLong(),
            sha256 = MessageDigest.getInstance("SHA-256").digest(payload).toHex(),
            redirectHosts = setOf("models.example", "cdn.example"),
        )

    private fun response(
        code: Int,
        location: String? = null,
        payload: ByteArray = byteArrayOf(),
        declaredLength: Long = payload.size.toLong(),
    ): WhisperModelStore.HttpResponse =
        WhisperModelStore.HttpResponse(
            code = code,
            location = location,
            contentLength = declaredLength,
            body = ByteArrayInputStream(payload),
        )

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private class StalledInputStream : InputStream() {
        val started = CountDownLatch(1)
        private val released = AtomicBoolean(false)

        override fun read(): Int {
            started.countDown()
            while (!released.get()) Thread.sleep(10)
            return -1
        }

        fun release() {
            released.set(true)
        }
    }
}
