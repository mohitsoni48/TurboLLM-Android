package com.turbollm.app

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

private const val TAG = "TurboLLM"

/** The name the page finds the bridge under: `window.TurboLLMNative` (web/src/lib/voice/tts.ts). */
private const val JS_OBJECT = "TurboLLMNative"

/** The voice id the page is given when the engine lists none of its own: "whatever the engine speaks with". */
private const val DEFAULT_VOICE_ID = "default"

/** A piece the page sends is a sentence or two. Anything far longer is not from it. */
private const val MAX_UTTERANCE_CHARS = 4000

/**
 * Reads replies aloud with Android's own text-to-speech, for the web UI's "Read aloud".
 *
 * The UI speaks through the browser's `speechSynthesis` everywhere else, but the Android System WebView has no working
 * one: it lists no voices and says nothing. So the page is given this instead, and its speech code talks to whichever
 * of the two it finds (web/src/lib/voice/tts.ts).
 *
 * It is a message channel, not an `addJavascriptInterface` object, because of who else runs script in this WebView: a
 * chat artifact, in a sandboxed frame, written by the model. An injected interface is visible to every frame, so an
 * artifact could make the phone talk. `addWebMessageListener` injects the channel only into frames of the daemon's own
 * origin, a sandboxed frame has no origin to match, and each message says whether it came from the main frame, which
 * is the only one answered.
 *
 * Messages are JSON text, both ways:
 *
 *     page → app   {"type":"tts.voices"}
 *                  {"type":"tts.speak","id":"u1","text":"…","voice":"<voice id or ''>","lang":"<language or ''>","rate":1}
 *                  {"type":"tts.stop"}
 *                  {"type":"tts.install"}                      (open the engine's voice downloads)
 *     app → page   {"type":"tts.voices","voices":[{"id":"…","name":"…","lang":"en-US","local":true}]}   (empty: nothing can speak)
 *                  {"type":"tts.done","id":"u1","ok":true}
 *
 * The page picks the voice: it knows the language of what it is reading, and reads a reply in Spanish with a Spanish
 * voice (web/src/lib/voice/voice-choice.ts). So it is told every voice that can speak now, each with its language and
 * whether it speaks on the phone (`local`) or through the engine maker's servers. A network voice is sent the text;
 * the page uses one only after the user has allowed it, and this side never picks one by itself. Voices whose data is
 * not downloaded are left out, and "tts.install" opens the engine's own screen for downloading them.
 *
 * The engine is started by the first message, not with the app: most sessions never ask for speech, and a bound
 * text-to-speech service is memory the model could use.
 */
class TtsBridge(context: Context) : WebViewCompat.WebMessageListener {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var engine: TextToSpeech? = null

    /** null while the engine is starting; then whether it can speak. */
    private var ready: Boolean? = null

    /** What was asked for while the engine was starting, run once it has. */
    private val waiting = ArrayList<() -> Unit>()

    /** The page's end of the channel, from its latest message: where an utterance's end is reported. */
    private var page: JavaScriptReplyProxy? = null
    private var closed = false

    /** Gives the page of [webView] the channel. [originRule] is the daemon's origin (scheme, host and port, no path). */
    fun attach(webView: WebView, originRule: String) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            // A WebView too old for it: the page finds no bridge and offers no "Read aloud".
            Log.i(TAG, "tts: this WebView has no web message listener; read-aloud is unavailable")
            return
        }
        WebViewCompat.addWebMessageListener(webView, JS_OBJECT, setOf(originRule), this)
    }

    override fun onPostMessage(
        view: WebView,
        message: WebMessageCompat,
        sourceOrigin: Uri,
        isMainFrame: Boolean,
        replyProxy: JavaScriptReplyProxy,
    ) {
        if (!isMainFrame || closed) return
        val request = try {
            JSONObject(message.data ?: return)
        } catch (e: Exception) {
            return
        }
        page = replyProxy
        when (request.optString("type")) {
            "tts.voices" -> whenReady { sendVoices() }
            "tts.speak" -> {
                val id = request.optString("id")
                val text = request.optString("text")
                if (id.isEmpty()) return
                val voice = request.optString("voice")
                val language = request.optString("lang")
                val rate = request.optDouble("rate", 1.0).toFloat()
                whenReady { speak(id, text, voice, language, rate) }
            }
            "tts.stop" -> {
                // Only a running engine has anything to stop; one still starting drops what it was asked to say.
                waiting.clear()
                engine?.stop()
            }
            "tts.install" -> installVoices()
        }
    }

    /** Stops speaking and lets the engine go. The bridge is not used again. */
    fun shutdown() {
        closed = true
        waiting.clear()
        page = null
        engine?.let {
            it.stop()
            it.shutdown()
        }
        engine = null
    }

    /** Runs [action] now if the engine has started, else once it has (starting it if nothing has yet). */
    private fun whenReady(action: () -> Unit) {
        if (ready != null) {
            action()
            return
        }
        waiting.add(action)
        if (engine != null) return
        engine = TextToSpeech(appContext) { status ->
            // The engine reports on a thread of its own.
            main.post { started(status == TextToSpeech.SUCCESS) }
        }
    }

    private fun started(ok: Boolean) {
        if (closed) return
        ready = ok
        if (ok) {
            engine?.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String) {}
                override fun onDone(utteranceId: String) = done(utteranceId, true)
                override fun onStop(utteranceId: String, interrupted: Boolean) = done(utteranceId, false)
                override fun onError(utteranceId: String, errorCode: Int) = done(utteranceId, false)

                // The platform deprecated this overload for the one above, but it is still abstract, and what an
                // older engine calls.
                @Suppress("OVERRIDE_DEPRECATION")
                override fun onError(utteranceId: String) = done(utteranceId, false)
            })
        } else {
            Log.w(TAG, "tts: no text-to-speech engine could be started")
        }
        val actions = ArrayList(waiting)
        waiting.clear()
        actions.forEach { it() }
    }

    private fun speak(id: String, text: String, voiceId: String, language: String, rate: Float) {
        val tts = engine
        if (ready != true || tts == null || text.isBlank()) {
            done(id, false)
            return
        }
        try {
            tts.setSpeechRate(rate.coerceIn(0.25f, 4f))
            val voices = voicesOf(tts)
            val named = voices.firstOrNull { it.name == voiceId }
            if (named != null) {
                tts.voice = named
            } else {
                // No voice the engine knows by that id (none was named, or it has since been removed): one for the
                // text's language, and only one that speaks on the phone. An engine that lists no voices is told the
                // language itself. Failing both, the engine's own default.
                val locale = if (language.isEmpty()) null else Locale.forLanguageTag(language)
                val forLanguage = locale?.let { want -> voices.firstOrNull { !it.isNetworkConnectionRequired && it.locale.language == want.language } }
                when {
                    forLanguage != null -> tts.voice = forLanguage
                    voices.isEmpty() -> {
                        // Back to the phone's own language for text whose language is not known, so the last
                        // reply's does not stick.
                        val want = locale ?: Locale.getDefault()
                        if (tts.isLanguageAvailable(want) >= TextToSpeech.LANG_AVAILABLE) tts.language = want
                    }
                    else -> tts.defaultVoice?.let { tts.voice = it }
                }
            }
            // FLUSH: the page sends one piece at a time and waits for its end, so anything still queued is stale.
            val queued = tts.speak(text.take(MAX_UTTERANCE_CHARS), TextToSpeech.QUEUE_FLUSH, null, id)
            if (queued != TextToSpeech.SUCCESS) done(id, false)
        } catch (e: Exception) {
            Log.w(TAG, "tts: speak failed", e)
            done(id, false)
        }
    }

    private fun sendVoices() {
        val list = JSONArray()
        val tts = engine
        if (ready == true && tts != null) {
            val voices = voicesOf(tts).sortedWith(compareBy({ it.locale.toLanguageTag() }, { it.name }))
            for (voice in voices) {
                list.put(
                    JSONObject()
                        .put("id", voice.name)
                        .put("name", "${voice.locale.displayName} · ${voice.name}")
                        .put("lang", voice.locale.toLanguageTag())
                        .put("local", !voice.isNetworkConnectionRequired),
                )
            }
            // An engine that speaks but lists no voices still has its one.
            if (list.length() == 0) {
                val locale = try { tts.defaultVoice?.locale } catch (e: Exception) { null }
                list.put(
                    // Its language is left out: what such an engine can speak is not known from here, so the page
                    // must not take it for a voice of one language only.
                    JSONObject()
                        .put("id", DEFAULT_VOICE_ID)
                        .put("name", if (locale != null) "System voice (${locale.displayName})" else "System voice")
                        .put("lang", "")
                        .put("local", true),
                )
            }
        }
        post(JSONObject().put("type", "tts.voices").put("voices", list))
    }

    /** The voices that can speak now: those whose data is on the phone, and the engine's network voices. A voice
     *  still to be downloaded is not one (the engine would stay silent, or fetch it without saying). */
    private fun voicesOf(tts: TextToSpeech): List<Voice> = try {
        tts.voices.orEmpty().filter { !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
    } catch (e: Exception) {
        // Some engines throw here rather than answer null.
        emptyList()
    }

    /** Opens the speech engine's screen for downloading voices, or the system's text-to-speech settings when the
     *  engine has none. The page offers this when a reply is in a language no voice on the phone speaks. */
    private fun installVoices() {
        val enginePackage = try { engine?.defaultEngine } catch (e: Exception) { null }
        val intents = listOf(
            Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).apply { if (!enginePackage.isNullOrEmpty()) setPackage(enginePackage) },
            Intent("com.android.settings.TTS_SETTINGS"),
        )
        for (intent in intents) {
            try {
                appContext.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (e: Exception) {
                // No such screen on this phone: try the next.
            }
        }
        Log.i(TAG, "tts: no voice download or text-to-speech settings screen could be opened")
    }

    private fun done(id: String, ok: Boolean) {
        post(JSONObject().put("type", "tts.done").put("id", id).put("ok", ok))
    }

    /** To the page, from the main thread: the engine's callbacks arrive on its own threads. */
    private fun post(message: JSONObject) {
        main.post {
            if (closed) return@post
            try {
                page?.postMessage(message.toString())
            } catch (e: Exception) {
                // The page that asked has gone (a reload, a renderer crash).
                Log.d(TAG, "tts: the page did not take ${message.optString("type")}: ${e.message}")
            }
        }
    }
}
