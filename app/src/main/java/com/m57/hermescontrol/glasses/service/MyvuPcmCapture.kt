package com.m57.hermescontrol.glasses.service

import android.annotation.SuppressLint
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

@SuppressLint("MissingPermission")
private fun buildAudioRecord(
    format: AudioFormat,
    bufferSize: Int,
): AudioRecord =
    AudioRecord.Builder()
        .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
        .setAudioFormat(format)
        .setBufferSizeInBytes(bufferSize)
        .build()

/** Captures the MYVU Bluetooth SCO communication device as 16 kHz mono PCM16. */
class MyvuPcmCapture(
    private val audioManager: AudioManager,
    private val onPcm: (ByteArray, Int) -> Unit,
    private val minimumBufferSize: () -> Int = {
        AudioRecord.getMinBufferSize(
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
    },
    private val recorderFactory: (AudioFormat, Int) -> AudioRecord = ::buildAudioRecord,
) : Closeable {
    private val capturing = AtomicBoolean(false)
    private var recorder: AudioRecord? = null
    private var captureThread: Thread? = null
    private var originalMode = AudioManager.MODE_NORMAL
    private var routedDevice: AudioDeviceInfo? = null
    private var frameCount = 0L

    @RequiresApi(Build.VERSION_CODES.S)
    @RequiresPermission(android.Manifest.permission.RECORD_AUDIO)
    fun start(): Result<Unit> =
        runCatching {
            check(!capturing.get()) { "MYVU PCM capture is already running" }
            originalMode = audioManager.mode
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            val scoDevice =
                audioManager.availableCommunicationDevices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                } ?: error("No Bluetooth SCO communication device is connected")
            check(audioManager.setCommunicationDevice(scoDevice)) {
                "Could not route communication audio to Bluetooth SCO"
            }
            routedDevice = scoDevice
            Log.i(TAG, "MYVU_CAPTURE route selected type=BLUETOOTH_SCO product=${scoDevice.productName}")
            val format =
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE_HZ)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build()
            val minimumBuffer = minimumBufferSize()
            check(minimumBuffer > 0) { "AudioRecord does not support 16 kHz mono PCM16" }
            val activeRecorder = recorderFactory(format, maxOf(minimumBuffer, PCM_FRAME_BYTES * 4))
            check(activeRecorder.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord initialization failed" }
            recorder = activeRecorder
            activeRecorder.startRecording()
            check(activeRecorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                "AudioRecord did not enter recording state"
            }
            capturing.set(true)
            captureThread =
                Thread({ readLoop(activeRecorder) }, "myvu-sco-pcm").apply {
                    isDaemon = true
                    start()
                }
            Log.i(TAG, "MYVU_CAPTURE recorder started sampleRate=$SAMPLE_RATE_HZ channels=1 encoding=PCM_16BIT")
            Unit
        }.onFailure {
            Log.e(TAG, "MYVU_CAPTURE start failed", it)
            close()
        }

    override fun close() {
        val wasCapturing = capturing.getAndSet(false)
        val activeRecorder = recorder
        recorder = null
        if (wasCapturing) runCatching { activeRecorder?.stop() }
        runCatching { activeRecorder?.release() }
        captureThread?.interrupt()
        captureThread = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && routedDevice != null) {
            audioManager.clearCommunicationDevice()
        }
        routedDevice = null
        audioManager.mode = originalMode
        if (wasCapturing || activeRecorder != null) {
            Log.i(TAG, "MYVU_CAPTURE stopped frames=$frameCount")
        }
    }

    private fun readLoop(activeRecorder: AudioRecord) {
        val buffer = ByteArray(PCM_FRAME_BYTES)
        while (capturing.get()) {
            val bytesRead = activeRecorder.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
            when {
                bytesRead > 0 -> {
                    frameCount += 1
                    if (frameCount <= 5 || frameCount % FRAME_LOG_INTERVAL == 0L) {
                        Log.i(TAG, "MYVU_CAPTURE frame count=$frameCount bytes=$bytesRead")
                    }
                    onPcm(buffer, bytesRead)
                }
                bytesRead != AudioRecord.ERROR_DEAD_OBJECT && capturing.get() -> {
                    Log.w(TAG, "MYVU_CAPTURE read failed code=$bytesRead")
                }
                else -> return
            }
        }
    }

    private companion object {
        const val SAMPLE_RATE_HZ = 16_000
        const val PCM_FRAME_BYTES = 640
        const val FRAME_LOG_INTERVAL = 250L
        const val TAG = "HermesMyvuCapture"
    }
}
