package com.subtitlereader.app

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

class TTSHelper(private val context: Context, initialSpeed: Float = 1.0f) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech = TextToSpeech(context, this)
    private var isReady = false
    private val pendingQueue = mutableListOf<String>()
    private var currentSpeed = initialSpeed

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val thaiLocale = Locale("th", "TH")
            val result = tts.setLanguage(thaiLocale)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.w("TTS", "Thai not supported, fallback to default")
                tts.setLanguage(Locale.getDefault())
            }
            tts.setSpeechRate(currentSpeed)
            tts.setPitch(1.0f)
            isReady = true
            pendingQueue.forEach { speak(it) }
            pendingQueue.clear()
        } else {
            Log.e("TTS", "TTS init failed")
        }
    }

    fun speak(text: String) {
        if (!isReady) { pendingQueue.add(text); return }
        tts.stop()
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "sub_${System.currentTimeMillis()}")
    }

    fun setSpeed(speed: Float) {
        currentSpeed = speed
        if (isReady) tts.setSpeechRate(speed)
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
    }
}
