package com.m57.hermescontrol.glasses.service

import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MyvuPcmCaptureTest {
    @Test
    fun forwards_valid_byte_count_from_a_reused_recorder_buffer() {
        val manager = mockk<AudioManager>(relaxed = true)
        val device = mockk<AudioDeviceInfo>()
        every { device.type } returns AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        every { device.productName } returns "MYVU"
        every { manager.availableCommunicationDevices } returns listOf(device)
        every { manager.setCommunicationDevice(device) } returns true
        every { manager.mode } returns AudioManager.MODE_NORMAL
        val recorder = mockk<AudioRecord>(relaxed = true)
        every { recorder.state } returns AudioRecord.STATE_INITIALIZED
        every { recorder.recordingState } returns AudioRecord.RECORDSTATE_RECORDING
        val reads = mutableListOf(3, 2, AudioRecord.ERROR_DEAD_OBJECT)
        every { recorder.read(any<ByteArray>(), 0, 640, AudioRecord.READ_BLOCKING) } answers {
            val buffer = firstArg<ByteArray>()
            buffer[0] = reads.size.toByte()
            buffer[1] = 2
            buffer[2] = 3
            reads.removeAt(0)
        }
        val received = mutableListOf<Pair<ByteArray, Int>>()
        val delivered = CountDownLatch(2)
        val capture =
            MyvuPcmCapture(
                audioManager = manager,
                onPcm = { bytes, count ->
                    received += bytes to count
                    delivered.countDown()
                },
                minimumBufferSize = { 640 },
                recorderFactory = { _: AudioFormat, _: Int -> recorder },
            )

        assertTrue(capture.start().isSuccess)
        assertTrue(delivered.await(2, TimeUnit.SECONDS))
        capture.close()

        assertEquals(listOf(3, 2), received.map(Pair<ByteArray, Int>::second))
        assertTrue(received[0].first === received[1].first)
        verify(exactly = 1) { recorder.release() }
    }
}
