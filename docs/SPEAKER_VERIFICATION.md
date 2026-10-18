# Local speaker filter

The optional **local speaker filter** is a probabilistic pre-STT gate, not an identity guarantee or a security lock.

## Data flow and consent

- Nothing is recorded for enrollment until the user opens the consent dialog, checks the consent box, and accepts.
- Enrollment captures three four-second PCM WAV samples. The samples are processed on-device into ECAPA embeddings and deleted after each sample is processed; interrupted enrollment recordings are purged when the app starts again.
- The averaged 192-value template is encrypted with AES-GCM using an Android Keystore key. It is not part of settings export/import or Android backup/device transfer. Deleting the profile also deletes the Keystore key.
- The model file is downloaded only after enrollment is explicitly started. The download contains no microphone audio. Its SHA-256 is checked before it is opened by ONNX Runtime.
- When enabled, dictation is recorded to a temporary WAV, compared locally with the template, and rejected recordings are deleted before STT. A clip that passes is sent to the selected STT engine; cloud STT therefore still receives passing audio. The clip and enrollment samples are never sent to the model host.

## Model and frontend provenance

The runtime model is `voxceleb_ECAPA512_LM.onnx` from `Wespeaker/wespeaker-ecapa-tdnn512-LM`, pinned to revision `a2f3dcb1c8702caccc7a55ceb57f5e8d1842112b`. The published SHA-256 used by the downloader is `d71b85d9b48058ef68004f04f1b78acebefb9dfcf542e19b976a12a5ad1f10b0`. The upstream model-preparation project attributes this artifact to CC BY 4.0; see [the license](https://creativecommons.org/licenses/by/4.0/) and [the model source](https://huggingface.co/Wespeaker/wespeaker-ecapa-tdnn512-LM). The ONNX file is fetched at runtime and is not bundled in this repository or APK.

The upstream provider expects a sequence shaped `[1, frames, 80]` and a 192-value speaker embedding. Inference uses that input shape and rejects outputs that do not contain exactly 192 values, so an incompatible model fails closed rather than creating a profile. The Kotlin frontend uses 16 kHz PCM, 25 ms Hamming windows, 10 ms frame shift, 80 log-mel bins, no dither, and per-utterance mean normalization. Its golden test was compared with `kaldi-native-fbank` 1.22.3 using the same options as the upstream JNI reference. This validates the frontend implementation against that reference configuration; it is not a biometric accuracy or threshold calibration study.

## Limitations

- Noise, microphone distance, illness, or changes in speaking style can falsely reject the enrolled user.
- Similar voices, imitation, and replayed recordings can falsely pass the filter.
- The check applies to the recording as a whole; it cannot isolate or attribute words when speakers overlap.
- The 0.70 cosine threshold is an operating cutoff, not a calibrated security boundary. Do not use this feature to authorize financial, safety-critical, or other high-value actions.
