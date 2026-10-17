# Local personalized wake word

Retrobit's optional wake-word monitor uses a small, bundled speech-embedding model and dynamic time warping (DTW) over three user-recorded examples. It does not keep a passive Android `SpeechRecognizer` session alive and does not use network recognition for the wake phrase.

## Setup and behavior

- The feature is off by default. The user explicitly starts enrollment in Settings and records the selected phrase three times; the default phrase is `hai retro`.
- Each example is captured as 3 seconds of 16 kHz mono PCM. The app converts it to speech embeddings in memory, deletes the temporary WAV, and saves only the embedding sequences and normalized phrase.
- The encrypted profile is stored in the app's `noBackupFilesDir` using AES-GCM and an Android Keystore key. It is not included in settings export or Android backup. Deleting the profile also deletes its Keystore key.
- The ONNX model is bundled in the APK and copied to private, non-backed-up app storage on first use. No model download or microphone upload is required.
- Passive monitoring uses `AudioRecord` in the existing microphone foreground service. It does not call `AudioManager.requestAudioFocus`; playback is therefore not paused by passive wake-word monitoring. When a wake word is detected, the existing assistant flow opens. The assistant overlay still takes transient exclusive audio focus while visible, as requested, and releases it when closed.
- Changing the configured phrase invalidates the matching profile and turns wake-word monitoring off until the new phrase is enrolled. The wake word always opens the assistant overlay, even if a text field is focused; it never becomes dictation.
- Audio recorded for a later assistant command follows the existing selected STT settings. This is separate from wake-word monitoring and enrollment.

## Personalization and limitations

The embedding model is a feature extractor, not a multilingual text recognizer. The configured phrase is matched to the enrolled acoustic examples rather than converted to text, so the user can enroll the current Indonesian phrase without selecting an ASR language. This is intentionally **speaker-dependent**: it is designed to work best for the voice or voices represented by the three examples. It is not a Google Assistant-equivalent general-purpose wake-word model, may miss speech in noise or with a different speaking style, and may occasionally trigger on similar sounds, including audio playing through a nearby speaker. Other users can be represented by enrolling examples from them, but that broadens the acoustic profile and may change false-trigger behavior.

The runtime currently uses a 0.14 cosine-DTW distance cutoff and requires two matching sliding windows. This is a starting operating point, not an Android-device accuracy guarantee or a security boundary. If detection is unreliable, re-enroll in a quiet place, say the exact phrase clearly, and keep the microphone distance similar to normal use. Wake-word detection must not be used to authorize sensitive actions.

## Model, algorithm, and licenses

- **Speech embeddings:** `speech-embedding.onnx` accepts mono 16 kHz float PCM and yields 96-dimensional vectors; SHA-256 `0140c2b1455c016783f9b859e16f47b5187c69079503cd053f406b86f9094560`. The model is an ONNX conversion of Google's [`speech_embedding/1`](https://www.kaggle.com/models/google/speech-embedding), licensed under Apache-2.0. The bundled conversion was obtained from [`st-matskevich/local-wake`](https://github.com/st-matskevich/local-wake/tree/c7afdec4140319428d91b9895f3a8c1ff6039e9b), whose project is MIT-licensed.
- The Kotlin pipeline is implemented in `WakeWordAudioFeatures.kt`, `LocalWakeWordKws.kt`, `WakeWordListener.kt`, and `WakeWordEnrollmentManager.kt`. The matching approach follows the local-wake embedding + DTW method; the per-user samples and profile are created only on the device.
- The model's I/O was checked with desktop ONNX Runtime 1.20.1. Android ONNX Runtime 1.20.0 compatibility, real-device latency, battery use, and detection accuracy still require device testing.
- License texts and attribution are bundled under `app/src/main/assets/wakeword/` alongside the ONNX asset.
