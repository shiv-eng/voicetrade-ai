package com.quietstack.voicetrade.data.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

sealed interface ListenEvent {
    data class Partial(val text: String) : ListenEvent
    data class Final(val text: String) : ListenEvent
    /** 0f..1f loudness of the user's voice, drives the orb. */
    data class Level(val level: Float) : ListenEvent
    /** [silence] means nothing was said (not a real error). */
    data class Failed(val code: Int, val silence: Boolean, val fatal: Boolean) : ListenEvent
}

/**
 * The phone's own speech-to-text and text-to-speech. Used when the server has no Agora voice, so the app is
 * still a real spoken conversation: it hears you, sends the words to the backend, and reads the answer aloud.
 */
@Singleton
class DeviceVoice @Inject constructor(@ApplicationContext private val context: Context) {

    private var tts: TextToSpeech? = null
    private var ttsOk: Boolean? = null
    private var speaking: CompletableDeferred<Unit>? = null

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    /** Listens for one utterance. Emits partials as the user speaks, then a [ListenEvent.Final] or a failure. */
    fun listen(languageTag: String): Flow<ListenEvent> = callbackFlow {
        var recognizer: SpeechRecognizer? = null
        launch(Dispatchers.Main.immediate) {
            val r = SpeechRecognizer.createSpeechRecognizer(context)
            recognizer = r
            r.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit

                override fun onRmsChanged(rmsdB: Float) {
                    trySend(ListenEvent.Level(((rmsdB + 2f) / 12f).coerceIn(0f, 1f)))
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    partialResults?.best()?.let { trySend(ListenEvent.Partial(it)) }
                }

                override fun onResults(results: Bundle?) {
                    val text = results?.best().orEmpty()
                    if (text.isNotBlank()) trySend(ListenEvent.Final(text)) else trySend(ListenEvent.Failed(SpeechRecognizer.ERROR_NO_MATCH, silence = true, fatal = false))
                    close()
                }

                override fun onError(error: Int) {
                    val silence = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                    val fatal = error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
                    Timber.d("SpeechRecognizer error %d", error)
                    trySend(ListenEvent.Failed(error, silence, fatal))
                    close()
                }
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1400L)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            }
            r.startListening(intent)
        }
        awaitClose {
            CoroutineScope(Dispatchers.Main.immediate).launch {
                recognizer?.runCatching { cancel(); destroy() }
            }
        }
    }.flowOn(Dispatchers.Main.immediate)

    private fun Bundle.best(): String? =
        getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()

    /** Reads [text] aloud. Returns when finished or when [stopSpeaking] is called (barge-in). */
    suspend fun speak(text: String, rate: Float) {
        val engine = ensureTts() ?: return
        val clean = tidy(text)
        if (clean.isBlank()) return
        val done = CompletableDeferred<Unit>()
        speaking = done
        withContext(Dispatchers.Main) {
            engine.setSpeechRate(rate.coerceIn(0.5f, 2f))
            val id = UUID.randomUUID().toString()
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) { done.complete(Unit) }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { done.complete(Unit) }
                override fun onStop(utteranceId: String?, interrupted: Boolean) { done.complete(Unit) }
            })
            engine.speak(clean, TextToSpeech.QUEUE_FLUSH, null, id)
        }
        try {
            done.await()
        } finally {
            if (speaking === done) speaking = null
        }
    }

    fun stopSpeaking() {
        tts?.stop()
        speaking?.complete(Unit)
    }

    fun shutdown() {
        stopSpeaking()
        tts?.shutdown()
        tts = null
        ttsOk = null
    }

    private suspend fun ensureTts(): TextToSpeech? {
        val existing = tts
        if (existing != null && ttsOk == true) return existing
        return suspendCancellableCoroutine { cont ->
            lateinit var engine: TextToSpeech
            engine = TextToSpeech(context) { status ->
                val ok = status == TextToSpeech.SUCCESS
                ttsOk = ok
                if (ok) {
                    val india = Locale("en", "IN")
                    engine.language = if (engine.isLanguageAvailable(india) >= TextToSpeech.LANG_AVAILABLE) india else Locale.getDefault()
                }
                if (cont.isActive) cont.resume(if (ok) engine else null)
            }
            tts = engine
        }
    }

    /** Spoken text should not contain symbols the engine reads out literally. */
    private fun tidy(text: String): String =
        text.replace(Regex("[*_`#>]+"), " ").replace(Regex("\\s+"), " ").trim()
}

