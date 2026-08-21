# Whisper test-fixture provenance

`short.wav`, `representative.wav`, and `thirty-seconds.wav` are deterministic
synthetic PCM16LE silence generated locally for this repository on 2026-08-21.
They contain no speech, personal audio, MYVU capture, or external recording.

They exercise model-free WAV parsing, duration, cancellation, and allocation
paths only. Real-model transcription quality is intentionally evaluated only
at the separately recorded physical Gate A and is never checked into source
control or CI artifacts.
