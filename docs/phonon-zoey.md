# Phone-call speech

This fork uses on-phone Phonon 31M English q8 with Zoey
(`NbpkqMVS3CJeq2j8`) for outgoing voice chats and answered phone calls.
The model, tokenizer, configuration, and voice conditioning match Conduit
commit `8bfe3fb95729e18985ebefdbf9ce7245f6e989b3`. The wrapper comes directly from the dual-licensed Phonon project;
Herald provides its own integration and asset preparation code. Runtime source is
`gradium-ai/xn-ptts@49b9033e44ab0fbb935973eca3213757644d1bbb`.

Call speech bypasses server synthesis, server speech streaming, TTS leases,
and GPT-Live/WebRTC. Missing or invalid assets show an error; calls do not
switch to Android system TTS or another voice. Speech is synthesized in sentence
chunks on an IO dispatcher and played through Herald's existing cancellable
player. A model is reused for the process lifetime. Ending a call cancels synthesis
at the next native audio callback and stops playback.

Phone calls also transcribe on the device. Like Conduit, they prefer Android's
on-device recognizer and use ML Kit Advanced, then Basic, when the platform input
path is unavailable. Herald uses its existing 16 kHz mono PCM microphone frames
for recognition, including interruption pre-roll. It streams headerless PCM at
real-time speed, as required by [ML Kit](https://developers.google.com/ml-kit/genai/speech-recognition/android),
and uses Android's [external audio source](https://developer.android.com/reference/android/speech/RecognizerIntent#EXTRA_AUDIO_SOURCE)
with segmented recognition so closing the pipe ends the utterance.

The platform path requires Android 13 or later; ML Kit requires a supported
Android 12 or later device and may download a speech model before first use.
The recognizer uses the phone's default locale. Unsupported devices, missing
language packs, and recognition failures show an error. Calls never select a
network recognition service or upload audio to Hermes as a fallback.

Only recognized text goes to Hermes for agent replies. Phonon and Zoey remain
on the phone. Message dictation, Read aloud, and watch calls retain their existing
behavior. This change does not alter the Hermes server's Parakeet configuration.

## Build

The phone APK now targets ARM64 only. Model assets and native binaries are
ignored build outputs, never Git blobs. Prepare them before building:

```sh
cargo install --locked cargo-ndk --version 4.1.2
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/29.0.14206865"
bash scripts/voice/build_phonon_android.sh
./gradlew :shared:ui:testAndroidHostTest :androidApp:assembleDebug
```

The build script checks out the pinned native source and uses its Cargo lockfile.
The asset preparation script downloads pinned Hugging Face revisions, verifies
sizes and SHA-256 hashes, and includes license and attribution files. The APK build
requires the pack; the Android installer rechecks model/voice hashes before use.
JNA 5.17.0 uses its Apache-2.0 license option. The model and Zoey conditioning use
Apache-2.0; the native runtime uses MIT OR Apache-2.0.

## Verification limits

Host tests cover local call routing, transcription failure without upload,
interruption and stop-command transcripts, Send now, synthesis failure without
server fallback, and cancellation. A successful APK build alone does not verify native speech,
audio routing, interruption, or background calls on a physical phone.

The native Kotlin wrapper uses the upstream MIT OR Apache-2.0 license.
Copies are in `third-party/phonon-LICENSE-MIT` and
`third-party/phonon-LICENSE-APACHE`; model attribution is packaged in the APK.
