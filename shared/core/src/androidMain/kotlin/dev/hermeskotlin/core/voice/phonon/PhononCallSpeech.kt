package dev.hermeskotlin.core.voice.phonon

import android.content.Context
import android.os.Build
import dev.hermeskotlin.core.voice.CallSpeech
import dev.hermeskotlin.core.voice.SpokenAudio
import dev.hermeskotlin.core.voice.wav
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One process-wide model, serialized off the UI thread. Only the pinned English Zoey pack is accepted. */
class PhononCallSpeech(context: Context) : CallSpeech {
    private val context = context.applicationContext
    private val lock = Mutex()
    private var engine: PhononTTS? = null

    override suspend fun synthesize(text: String): SpokenAudio = withContext(Dispatchers.IO) {
        lock.withLock {
            val job = currentCoroutineContext()
            job.ensureActive()
            try {
                check(Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a") {
                    "Phonon Zoey requires an ARM64 device."
                }
                val model = engine ?: run {
                    val pack = PhononVoicePack.install(context)
                    val loaded = PhononTTS(pack.path, "en")
                    try {
                        check(PhononVoicePack.VOICE in loaded.voices) { "Zoey is missing from the voice pack." }
                        loaded.setVoice(PhononVoicePack.VOICE)
                        engine = loaded
                        loaded
                    } catch (e: Throwable) {
                        loaded.close()
                        throw e
                    }
                }
                job.ensureActive()
                val pcm = ByteArrayOutputStream()
                model.speak(text) { frame ->
                    if (!job.isActive) false else {
                        val bytes = ByteArray(frame.size * 2)
                        for (i in frame.indices) {
                            val sample = (frame[i].coerceIn(-1f, 1f) * 32767f).toInt()
                            bytes[2 * i] = sample.toByte()
                            bytes[2 * i + 1] = (sample shr 8).toByte()
                        }
                        pcm.write(bytes)
                        true
                    }
                }
                job.ensureActive()
                check(pcm.size() > 0) { "Phonon Zoey generated no audio." }
                SpokenAudio(wav(pcm.toByteArray(), 24_000), "audio/wav")
            } catch (e: CancellationException) {
                throw e
            } catch (e: LinkageError) {
                throw IllegalStateException("Phonon Zoey native runtime could not load. No other voice was used.", e)
            }
        }
    }
}
