package com.m57.hermescontrol.glasses.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class WhisperModelManifestTest {
    @Test
    fun artifacts_use_reviewed_immutable_hugging_face_revisions() {
        assertEquals("base.en-q5_1", WhisperModelManifest.whisper.id)
        assertEquals(
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/" +
                "5359861c739e955e79d9a303bcbc70fb988958b1/ggml-base.en-q5_1.bin",
            WhisperModelManifest.whisper.url,
        )
        assertEquals(59_721_011, WhisperModelManifest.whisper.sizeBytes)
        assertEquals(
            "4baf70dd0d7c4247ba2b81fafd9c01005ac77c2f9ef064e00dcf195d0e2fdd2f",
            WhisperModelManifest.whisper.sha256,
        )
        assertEquals(
            "https://huggingface.co/ggml-org/whisper-vad/resolve/" +
                "9ffd54a1e1ee413ddf265af9913beaf518d1639b/ggml-silero-v6.2.0.bin",
            WhisperModelManifest.vad.url,
        )
        WhisperModelManifest.all.forEach { artifact ->
            assertFalse(artifact.url.contains("/resolve/main/"))
        }
    }
}
