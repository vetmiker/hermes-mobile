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
            id = "tiny.en-q5_1",
            url =
                "https://huggingface.co/ggerganov/whisper.cpp/resolve/" +
                    "5359861c739e955e79d9a303bcbc70fb988958b1/ggml-tiny.en-q5_1.bin",
            sizeBytes = 32_166_155,
            sha256 = "c77c5766f1cef09b6b7d47f21b546cbddd4157886b3b5d6d4f709e91e66c7c2b",
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
