package com.m57.hermescontrol.glasses.speech

import okhttp3.HttpUrl.Companion.toHttpUrl

internal data class WhisperModelArtifact(
    val id: String,
    val url: String,
    val sizeBytes: Long,
    val sha256: String,
    val redirectHosts: Set<String>,
) {
    val fileName: String get() = "$sha256.bin"
    val initialUrl get() = url.toHttpUrl()
}

internal object WhisperModelManifest {
    private val huggingFaceHosts = setOf("huggingface.co", "cdn-lfs.huggingface.co", "us.aws.cdn.hf.co")

    val whisper =
        WhisperModelArtifact(
            id = "base.en-q5_1",
            url =
                "https://huggingface.co/ggerganov/whisper.cpp/resolve/" +
                    "5359861c739e955e79d9a303bcbc70fb988958b1/ggml-base.en-q5_1.bin",
            sizeBytes = 59_721_011,
            sha256 = "4baf70dd0d7c4247ba2b81fafd9c01005ac77c2f9ef064e00dcf195d0e2fdd2f",
            redirectHosts = huggingFaceHosts,
        )
    val vad =
        WhisperModelArtifact(
            id = "silero-v6.2.0",
            url =
                "https://huggingface.co/ggml-org/whisper-vad/resolve/" +
                    "9ffd54a1e1ee413ddf265af9913beaf518d1639b/ggml-silero-v6.2.0.bin",
            sizeBytes = 885_098,
            sha256 = "2aa269b785eeb53a82983a20501ddf7c1d9c48e33ab63a41391ac6c9f7fb6987",
            redirectHosts = huggingFaceHosts,
        )

    val all = listOf(whisper, vad)
}
