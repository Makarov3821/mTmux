# Third-party sources for the audio comparison tool

The test app reuses mTmux's recorder, tokenizer and released punctuation reconstruction
under the repository MIT license. Its Whisper JNI is a snapshot of the same CPU
decoder with an additional load-time measurement and its own JNI symbol namespace.
The production app sources are not changed by this standalone project.

Bundled runtime/code license texts are in `app/src/main/assets/licenses/`:

- whisper.cpp 1.9.5 and OpenAI Whisper: MIT.
- sherpa-onnx v1.13.8: Apache-2.0. The Android static-ONNX-runtime build is pinned in
  `runtime.lock.json`, including both tested ABI libraries and the matching Kotlin API.
  https://github.com/k2-fsa/sherpa-onnx/tree/v1.13.8
- ONNX Runtime Android 1.23.2: MIT, including its third-party notices.
- AndroidX / Jetpack Compose and Kotlin / coroutines use their upstream bundled notices.
- Apache Commons Compress 1.28.0 and transitive Apache Commons libraries: Apache-2.0,
  with upstream license / notices retained in dependency resources.
- CT punctuation: Apache-2.0, and SRF punctuation: MIT, as documented in the main
  repository's bundled model notices.

Model weights are NOT bundled in the APK. The model downloader and optional local
ZIP use exactly the upstream files enumerated in `app/src/main/assets/models.json`.
Runtime code licenses do not by themselves establish a model weight license.
Original model release / model-card sources for the added candidates:

- Paraformer Small: https://huggingface.co/csukuangfj/sherpa-onnx-paraformer-zh-small-2024-03-09
- Offline Zipformer: https://huggingface.co/zrjin/icefall-asr-zipformer-multi-zh-en-2023-11-22
- Streaming Zipformer Small:
  https://k2-fsa.github.io/sherpa/onnx/pretrained_models/online-transducer/zipformer-transducer-models.html
  and https://huggingface.co/csukuangfj/k2fsa-zipformer-bilingual-zh-en-t
- X-ASR:
  https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models
  (`sherpa-onnx-x-asr-480ms-streaming-zipformer-transducer-zh-en-punct-int8-2026-06-05`)
- SenseVoice:
  https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09
- Whisper: https://huggingface.co/ggerganov/whisper.cpp

The local diagnostic sample is the main repository's public FunASR Chinese sample,
not a user's recording. See `../docs/testing/voice-models-20261023.md` for provenance.
Saved user recordings and transcripts remain in the test app's private directory
unless the user explicitly exports the results ZIP. No analytics or upload endpoint
is implemented. Internet access is used only for the pinned model downloads.
