package dev.hermeskotlin.core.voice

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.audio.AudioSource
import com.google.mlkit.genai.speechrecognition.SpeechRecognition
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerResponse
import com.google.mlkit.genai.speechrecognition.speechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.speechRecognizerRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay
import kotlin.time.TimeSource
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Conduit's engine order: Android on-device recognition, then ML Kit Advanced/Basic.
 * The call's one microphone supplies raw PCM to the recognizer as it is captured. No network
 * recognizer or Hermes audio endpoint is used. Engine integration is independently implemented.
 */
class AndroidCallRecognition(private val context: Context) : CallRecognition {
    private val gate = Mutex()
    @Volatile private var input: Channel<ByteArray>? = null
    private val microphone = AndroidVoiceRecorder { pcm ->
        // A slow or failed engine must not silently lose words or block microphone cancellation.
        val channel = input
        if (channel != null && channel.trySend(pcm).isFailure) {
            error("On-device speech recognition couldn't keep up. Try again.")
        }
    }

    override fun finish() = microphone.finish()

    override suspend fun record(activity: VoiceActivity, onLevel: (Float) -> Unit, onSpeech: () -> Unit): Recording =
        capture { microphone.record(activity, onLevel, onSpeech) }!!

    override suspend fun listenOver(
        bargeIn: BargeIn,
        playing: () -> Boolean,
        onTrip: () -> Unit,
        onLevel: (Float) -> Unit,
        onListening: () -> Unit,
    ): Recording? = capture { microphone.listenOver(bargeIn, playing, onTrip, onLevel, onListening) }

    private suspend fun capture(record: suspend () -> Recording?): Recording? = gate.withLock {
        // Prepare/download before opening the microphone, so preparation cannot overflow its PCM queue.
        val engine = withTimeoutOrNull(120_000) { prepare() }
            ?: error("On-device speech preparation timed out. Try again. No audio was uploaded.")
        val pipe = try { ParcelFileDescriptor.createPipe() } catch (e: Exception) {
            engine.close()
            throw e
        }
        val frames = Channel<ByteArray>(128)
        try {
            coroutineScope {
                input = frames
                val words = async {
                    val transcript = try {
                        // For barge-in, the recorder feeds its pre-roll only once someone cuts in.
                        // Do not start a recognizer that would time out while nobody interrupts.
                        val first = frames.receive()
                        coroutineScope {
                            val writer = launch(Dispatchers.IO) {
                                ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { stream ->
                                    val started = TimeSource.Monotonic.markNow()
                                    var sentBytes = 0L
                                    suspend fun write(frame: ByteArray) {
                                        stream.write(frame)
                                        sentBytes += frame.size
                                        // ML Kit requires PCM at real-time speed, including barge-in pre-roll.
                                        delay((sentBytes * 1_000 / 32_000 - started.elapsedNow().inWholeMilliseconds).coerceAtLeast(0))
                                    }
                                    write(first)
                                    for (frame in frames) write(frame)
                                }
                            }
                            try {
                                DeviceTranscript.Text(engine.recognize(pipe[0]))
                            } finally {
                                // Close before cancellation: an OS pipe write can be blocking.
                                runCatching { pipe[0].close() }
                                runCatching { pipe[1].close() }
                                writer.cancel()
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        DeviceTranscript.Failure(e.message ?: "On-device transcription failed. No audio was uploaded.")
                    }
                    // A recognizer that ends early (for example, a missing language pack) must
                    // not leave the microphone filling a queue that nobody consumes.
                    microphone.finish()
                    transcript
                }
                try {
                    val recording = record()
                    frames.close()
                    if (recording == null) return@coroutineScope null
                    // Silence is handled by the existing VAD. Interruptions can contain a brief stop
                    // even without continued speech, so their transcript must still be collected.
                    val transcript = withTimeoutOrNull(15_000) { words.await() }
                        ?: DeviceTranscript.Failure("On-device transcription timed out. Try again. No audio was uploaded.")
                    Recording(recording.bytes, recording.mimeType, recording.heardSpeech, transcript)
                } finally {
                    input = null
                    frames.cancel()
                    runCatching { pipe[0].close() }
                    runCatching { pipe[1].close() }
                    words.cancel()
                }
            }
        } finally {
            runCatching { pipe[0].close() }
            runCatching { pipe[1].close() }
            engine.close()
        }
    }

    private interface Engine {
        suspend fun recognize(audio: ParcelFileDescriptor): String
        fun close() = Unit
    }

    private suspend fun prepare(): Engine {
        if (Build.VERSION.SDK_INT >= 33 && withContext(Dispatchers.Main) {
                runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false)
            }) return PlatformEngine()
        if (Build.VERSION.SDK_INT >= 31) {
            for (mode in listOf(SpeechRecognizerOptions.Mode.MODE_ADVANCED, SpeechRecognizerOptions.Mode.MODE_BASIC)) {
                val client = SpeechRecognition.getClient(speechRecognizerOptions {
                    locale = Locale.getDefault()
                    preferredMode = mode
                })
                var keep = false
                try {
                    when (client.checkStatus()) {
                        FeatureStatus.DOWNLOADABLE, FeatureStatus.DOWNLOADING -> client.download().collect {
                            if (it is DownloadStatus.DownloadFailed) throw it.e
                        }
                    }
                    if (client.checkStatus() == FeatureStatus.AVAILABLE) {
                        keep = true
                        return object : Engine {
                            override suspend fun recognize(audio: ParcelFileDescriptor): String {
                                val committed = mutableListOf<String>()
                                var partial = ""
                                client.startRecognition(speechRecognizerRequest {
                                    audioSource = AudioSource.fromPfd(audio)
                                }).collect { response ->
                                    when (response) {
                                        is SpeechRecognizerResponse.PartialTextResponse -> partial = response.text
                                        is SpeechRecognizerResponse.FinalTextResponse -> {
                                            committed += response.text
                                            partial = ""
                                        }
                                        is SpeechRecognizerResponse.ErrorResponse -> throw response.e
                                        else -> Unit
                                    }
                                }
                                return (committed + partial).filter(String::isNotBlank).joinToString(" ").trim()
                            }
                            override fun close() = client.close()
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Try Basic if Advanced isn't supported or cannot prepare on this phone.
                } finally {
                    if (!keep) client.close()
                }
            }
        }
        error("On-device call transcription isn't available. Check the phone's speech language pack. No audio was uploaded.")
    }

    private inner class PlatformEngine : Engine {
        override suspend fun recognize(audio: ParcelFileDescriptor): String = withContext(Dispatchers.Main) {
            // Explicit on-device factory: never createSpeechRecognizer(), which can select a server engine.
            val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            try {
                suspendCancellableCoroutine { continuation ->
                    val segments = mutableListOf<String>()
                    fun result(bundle: Bundle?) = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    recognizer.setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) = Unit
                        override fun onBeginningOfSpeech() = Unit
                        override fun onRmsChanged(rmsdB: Float) = Unit
                        override fun onBufferReceived(buffer: ByteArray?) = Unit
                        override fun onEndOfSpeech() = Unit
                        override fun onPartialResults(partialResults: Bundle?) = Unit
                        override fun onEvent(eventType: Int, params: Bundle?) = Unit
                        override fun onSegmentResults(segmentResults: Bundle) {
                            if (continuation.isActive) result(segmentResults).takeIf(String::isNotBlank)?.let(segments::add)
                        }
                        override fun onEndOfSegmentedSession() {
                            if (continuation.isActive) continuation.resume(segments.joinToString(" ").trim())
                        }
                        override fun onResults(results: Bundle?) {
                            if (continuation.isActive) continuation.resume(result(results).ifBlank { segments.joinToString(" ") }.trim())
                        }
                        override fun onError(error: Int) {
                            if (!continuation.isActive) return
                            if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                                continuation.resume(segments.joinToString(" "))
                            } else {
                                val reason = when (error) {
                                    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "Install the phone's speech language pack."
                                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Allow microphone access."
                                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "The phone's speech recognizer is busy. Try again."
                                    else -> "On-device speech recognition failed ($error)."
                                }
                                continuation.resumeWithException(IllegalStateException("$reason No audio was uploaded."))
                            }
                        }
                    })
                    recognizer.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, audio)
                        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16_000)
                        // EOF from Herald's existing VAD/Send now ends this recognition session.
                        putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
                    })
                }
            } finally {
                recognizer.destroy()
            }
        }
    }
}
