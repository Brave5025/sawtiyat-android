package com.sawtiyat.app.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import com.sawtiyat.app.MainActivity
import com.sawtiyat.app.R
import com.sawtiyat.app.data.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Locale

class SpeechService : Service(), TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    private var chunks: List<String> = emptyList()
    private var index = 0
    private var bookId = -1L
    private var initialOffset = 0
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        createChannel()
        tts = TextToSpeech(this, this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { tts?.stop(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
            ACTION_PLAY -> {
                bookId = intent.getLongExtra(EXTRA_BOOK_ID, -1L)
                val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
                val start = intent.getIntExtra(EXTRA_START, 0).coerceIn(0, text.length)
                initialOffset = start
                chunks = splitText(text.substring(start))
                index = 0
                startForeground(NOTIFICATION_ID, notification("جارٍ تجهيز القراءة"))
                if (tts != null) speakNext()
            }
            ACTION_PAUSE -> tts?.stop()
        }
        return START_NOT_STICKY
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onError(utteranceId: String?) {
                    index++
                    speakNext()
                }
                override fun onDone(utteranceId: String?) {
                    index++
                    val position = initialOffset + chunks.take(index).sumOf { it.length }
                    scope.launch {
                        if (bookId > 0) {
                            AppDatabase.get(this@SpeechService).bookDao().updateProgress(bookId, position)
                        }
                    }
                    speakNext()
                }
            })
            val languageResult = tts?.setLanguage(Locale.forLanguageTag("ar"))
            tts?.setSpeechRate(1.0f)
            if (languageResult == TextToSpeech.LANG_MISSING_DATA ||
                languageResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                android.util.Log.w("SawtiyatTTS", "Arabic voice data is unavailable on this device")
            }
            speakNext()
        }
    }

    private fun splitText(text: String): List<String> {
        val max = 2200
        val result = mutableListOf<String>()
        var cursor = 0
        while (cursor < text.length) {
            var end = (cursor + max).coerceAtMost(text.length)
            if (end < text.length) {
                val boundary = text.lastIndexOfAny(charArrayOf('.', '؟', '!', '\n', '،', '؛'), end)
                if (boundary > cursor + max / 2) end = boundary + 1
            }
            result += text.substring(cursor, end).trim()
            cursor = end
        }
        return result.filter { it.isNotBlank() }
    }

    private fun speakNext() {
        if (index >= chunks.size) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        val current = index
        tts?.speak(chunks[index], TextToSpeech.QUEUE_FLUSH, null, "sawtiyat-$current")
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "تشغيل صوتيات", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun notification(message: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, SpeechService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("صوتيات")
            .setContentText(message)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_media_pause, "إيقاف", stop)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_PLAY = "com.sawtiyat.PLAY"
        const val ACTION_PAUSE = "com.sawtiyat.PAUSE"
        const val ACTION_STOP = "com.sawtiyat.STOP"
        const val EXTRA_BOOK_ID = "book_id"
        const val EXTRA_TEXT = "text"
        const val EXTRA_START = "start"
        private const val CHANNEL_ID = "sawtiyat_playback"
        private const val NOTIFICATION_ID = 41
    }
}
