package com.m57.hermescontrol.glasses.service

import com.m57.hermescontrol.glasses.GlassesModeController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class MyvuControlServerTest {
    @Test
    fun exact_end_phrase_is_acknowledged_without_creating_a_turn_and_schedules_cleanup() {
        val harness = TranscriptHarness()
        harness.start()
        try {
            assertEquals(
                202,
                postTranscript(
                    harness.server.boundPort,
                    harness.listeningGeneration,
                    harness.listeningStreamId,
                    "utterance-end",
                    " End   Glasses Mode ",
                ),
            )

            assertTrue(harness.cleanupScheduled.await(1, TimeUnit.SECONDS))

            assertEquals(0, harness.reservations.get())
            assertEquals(0, harness.commits.get())
            assertEquals(1, harness.cleanupSchedules.get())
        } finally {
            harness.close()
        }
    }

    @Test
    fun prompt_containing_end_phrase_remains_an_accepted_normal_turn() {
        val harness = TranscriptHarness()
        harness.start()
        try {
            assertEquals(
                202,
                postTranscript(
                    harness.server.boundPort,
                    harness.listeningGeneration,
                    harness.listeningStreamId,
                    "utterance-normal",
                    "please end glasses mode after this answer",
                ),
            )

            assertEquals(1, harness.reservations.get())
            assertEquals(1, harness.commits.get())
            assertEquals(0, harness.cleanupSchedules.get())
        } finally {
            harness.close()
        }
    }

    @Test
    fun oversized_content_length_is_rejected_before_body_allocation() {
        val harness = TranscriptHarness()
        harness.start()
        try {
            assertEquals(
                413,
                requestStatus(
                    harness.server.boundPort,
                    "POST /display HTTP/1.1\r\n" +
                        "Authorization: Bearer test-token\r\n" +
                        "Content-Length: 32769\r\n\r\n",
                ),
            )
        } finally {
            harness.close()
        }
    }

    @Test
    fun slow_unauthenticated_headers_time_out_without_calling_handlers() {
        val harness = TranscriptHarness(readTimeoutMillis = 50)
        harness.start()
        try {
            Socket("127.0.0.1", harness.server.boundPort).use { socket ->
                socket.soTimeout = 1_000
                socket.getOutputStream().writer().apply {
                    write("GET /health HTTP/1.1\r\n")
                    flush()
                }
                assertEquals(408, socket.getInputStream().bufferedReader().readLine().split(' ')[1].toInt())
            }
        } finally {
            harness.close()
        }
    }

    private class TranscriptHarness(
        readTimeoutMillis: Int = 5_000,
    ) {
        val controller = GlassesModeController()
        val reservations = AtomicInteger()
        val commits = AtomicInteger()
        val cleanupSchedules = AtomicInteger()
        val cleanupScheduled = CountDownLatch(1)
        private val listening = controller.start("stored", "runtime")
        val listeningGeneration = listening.generation
        val listeningStreamId: String
            get() = controller.snapshot.value.activeStreamId!!
        val server =
            MyvuControlServer(
                token = "test-token",
                health = { throw AssertionError("health should not be called") },
                display = {},
                transcript = { generation, streamId, utteranceId, text ->
                    controller
                        .acceptTranscript(generation, streamId, utteranceId, text)
                        .also { acceptance ->
                            if (acceptance.accepted) {
                                reservations.incrementAndGet()
                                commits.incrementAndGet()
                            }
                        }
                },
                transcriptEnded = {
                    cleanupSchedules.incrementAndGet()
                    cleanupScheduled.countDown()
                },
                control = { false },
                listenPort = 0,
                readTimeoutMillis = readTimeoutMillis,
            )

        fun start() {
            assertTrue(controller.initialDisplayCompleted(listening.generation, "stored", "runtime"))
            server.start()
        }

        fun close() {
            server.close()
        }
    }

    private fun postTranscript(
        port: Int,
        generation: Long,
        streamId: String,
        utteranceId: String,
        text: String,
    ): Int {
        val body =
            """{"generation":$generation,"streamId":"$streamId","utteranceId":"$utteranceId","text":"$text"}"""
        Socket("127.0.0.1", port).use { socket ->
            val writer = socket.getOutputStream().writer()
            writer.write(
                "POST /transcript HTTP/1.1\r\n" +
                    "Authorization: Bearer test-token\r\n" +
                    "Content-Length: ${body.toByteArray().size}\r\n\r\n" +
                    body,
            )
            writer.flush()
            val reader = socket.getInputStream().bufferedReader()
            val statusLine = reader.readLine()
            return statusLine.split(' ')[1].toInt()
        }
    }

    private fun requestStatus(
        port: Int,
        request: String,
    ): Int =
        Socket("127.0.0.1", port).use { socket ->
            socket.getOutputStream().writer().apply {
                write(request)
                flush()
            }
            socket.getInputStream().bufferedReader().readLine().split(' ')[1].toInt()
        }
}
