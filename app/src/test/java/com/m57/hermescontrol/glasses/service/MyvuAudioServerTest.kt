package com.m57.hermescontrol.glasses.service

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.net.Socket
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class MyvuAudioServerTest {
    @Test
    fun authenticatesBeforeWritingRawPcm() {
        val server = MyvuAudioServer(token = "test-token", listenPort = 0)
        server.start()
        try {
            Socket("127.0.0.1", server.boundPort).use { socket ->
                OutputStreamWriter(socket.getOutputStream()).use { writer ->
                    writer.write("wrong\n")
                    writer.flush()
                }
                assertFalse(server.publish(byteArrayOf(9)))
            }

            Socket("127.0.0.1", server.boundPort).use { socket ->
                val writer = OutputStreamWriter(socket.getOutputStream())
                writer.write("test-token\n")
                writer.flush()
                awaitClient(server)
                assertEquals(true, server.publish(byteArrayOf(7, 8, 9)))
                assertArrayEquals(byteArrayOf(0), socket.getInputStream().readExactly(1))
                assertArrayEquals(byteArrayOf(7, 8, 9), socket.getInputStream().readExactly(3))
            }
        } finally {
            server.close()
        }
    }

    @Test
    fun blocked_writer_does_not_block_publish_and_reports_overflow() {
        val failures = Collections.synchronizedList(mutableListOf<AudioTransportFailure>())
        val output = BlockingFirstWriteOutputStream()
        val ready = CountDownLatch(1)
        val server =
            MyvuAudioServer(
                token = "test-token",
                listenPort = 0,
                maxQueuedFrames = 1,
                onClientReady = ready::countDown,
                onTransportFailure = failures::add,
                outputFactory = { output },
            )
        server.start()
        try {
            Socket("127.0.0.1", server.boundPort).use { socket ->
                authenticate(socket)
                assertTrue(ready.await(1, TimeUnit.SECONDS))
                assertTrue(server.publish(byteArrayOf(1)))
                assertTrue(output.awaitWriteStarted())
                assertTrue(server.publish(byteArrayOf(2)))

                val producer = Executors.newSingleThreadExecutor()
                try {
                    assertFalse(producer.submit<Boolean> { server.publish(byteArrayOf(3)) }.get(1, TimeUnit.SECONDS))
                } finally {
                    producer.shutdownNow()
                }

                assertTrue(output.awaitWriteFinished())
                assertEquals(listOf(AudioTransportFailure.OVERFLOW), failures)
            }
        } finally {
            output.close()
            server.close()
        }
    }

    @Test
    fun stale_writer_failure_does_not_disconnect_replacement_client() {
        val failures = Collections.synchronizedList(mutableListOf<AudioTransportFailure>())
        val firstOutput = BlockingFirstWriteOutputStream()
        val readyClients = AtomicInteger()
        val outputCount = AtomicInteger()
        val server =
            MyvuAudioServer(
                token = "test-token",
                listenPort = 0,
                onClientReady = readyClients::incrementAndGet,
                onTransportFailure = failures::add,
                outputFactory = { socket ->
                    if (outputCount.getAndIncrement() == 0) {
                        firstOutput
                    } else {
                        BufferedOutputStream(socket.getOutputStream())
                    }
                },
            )
        server.start()
        var firstClient: Socket? = null
        var replacementClient: Socket? = null
        try {
            firstClient = Socket("127.0.0.1", server.boundPort)
            authenticate(firstClient)
            awaitClientReady(readyClients, 1)
            assertTrue(server.publish(byteArrayOf(1)))
            assertTrue(firstOutput.awaitWriteStarted())

            replacementClient = Socket("127.0.0.1", server.boundPort)
            authenticate(replacementClient)
            awaitClientReady(readyClients, 2)
            assertTrue(firstOutput.awaitWriteFinished())
            assertTrue(failures.isEmpty())

            assertTrue(server.publish(byteArrayOf(7, 8, 9)))
            assertArrayEquals(byteArrayOf(7, 8, 9), replacementClient.getInputStream().readExactly(3))
        } finally {
            firstOutput.close()
            firstClient?.close()
            replacementClient?.close()
            server.close()
        }
    }

    private fun authenticate(socket: Socket) {
        val writer = OutputStreamWriter(socket.getOutputStream())
        writer.write("test-token\n")
        writer.flush()
    }

    private fun awaitClientReady(
        readyClients: AtomicInteger,
        count: Int,
    ) {
        repeat(100) {
            if (readyClients.get() >= count) return
            TimeUnit.MILLISECONDS.sleep(10)
        }
        throw AssertionError("Authenticated client was not ready")
    }

    private fun awaitClient(server: MyvuAudioServer) {
        repeat(50) {
            if (server.publish(byteArrayOf(0))) return
            TimeUnit.MILLISECONDS.sleep(10)
        }
        throw AssertionError("Authenticated client was not ready")
    }

    private fun InputStream.readExactly(size: Int): ByteArray {
        val bytes = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val count = read(bytes, offset, size - offset)
            if (count < 0) throw AssertionError("Unexpected end of stream")
            offset += count
        }
        return bytes
    }

    private class BlockingFirstWriteOutputStream : OutputStream() {
        private val writeStarted = CountDownLatch(1)
        private val releaseWrite = CountDownLatch(1)
        private val writeFinished = CountDownLatch(1)

        @Volatile
        private var closed = false

        override fun write(value: Int) {
            write(byteArrayOf(value.toByte()))
        }

        override fun write(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ) {
            writeStarted.countDown()
            try {
                releaseWrite.await()
                if (closed) throw IOException("Output closed")
            } finally {
                writeFinished.countDown()
            }
        }

        override fun close() {
            closed = true
            releaseWrite.countDown()
        }

        fun awaitWriteStarted(): Boolean = writeStarted.await(1, TimeUnit.SECONDS)

        fun awaitWriteFinished(): Boolean = writeFinished.await(1, TimeUnit.SECONDS)
    }
}
