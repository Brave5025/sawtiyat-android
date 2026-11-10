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
import com.sawtiyat.app.data.AppDatabase
import com.sawtiyat.app.data.Book
import com.sawtiyat.app.text.ArabicTextProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale

/** Background playback service. It converts and queues one short chunk at a time. */
class SpeechService : Service(), TextToSpeech.OnInitListener {
    private data class PlaybackChunk(val rawEnd: Int, val spokenText: String)

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var book: Book? = null
    private var cursor = 0
    private var currentChunk: PlaybackChunk? = null
    private var chunkIndex = 0
    private var paused = false
    private var playbackGeneration = 0L
    private var requestedBookId = -1L
    private var requestedStart = 0
    private var requestedRate = 1.0f
    private var loadGeneration = 0L
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        createChannel()
        tts = TextToSpeech(this, this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> {
                requestedBookId = intent.getLongExtra(EXTRA_BOOK_ID, -1L)
                requestedStart = intent.getIntExtra(EXTRA_START, 0).coerceAtLeast(0)
                requestedRate = intent.getFloatExtra(EXTRA_RATE, 1.0f).coerceIn(0.5f, 2.0f)
                startForeground(NOTIFICATION_ID, notification("جارٍ تجهيز القراءة"))
                loadBook(requestedBookId, requestedStart)
            }
            ACTION_PAUSE -> pausePlayback()
            ACTION_RESUME -> resumePlayback()
            ACTION_STOP -> stopPlayback()
        }
        return START_NOT_STICKY
    }

    private fun loadBook(bookId: Long, start: Int) {
        if (bookId <= 0L) {
            updateNotification("تعذر العثور على الكتاب")
            stopPlayback()
            return
        }
        val thisLoad = ++loadGeneration
        playbackGeneration++
        tts?.stop()
        book = null
        currentChunk = null
        cursor = 0
        chunkIndex = 0
        paused = false
        serviceScope.launch {
            val loaded = runCatching { AppDatabase.get(this@SpeechService).bookDao().getById(bookId) }
                .getOrNull()
            if (thisLoad != loadGeneration) return@launch
            if (loaded == null) {
                updateNotification("تعذر العثور على الكتاب")
                stopPlayback()
                return@launch
            }
            book = loaded
            cursor = start.coerceIn(0, loaded.content.length)
            currentChunk = nextChunk(loaded.content, cursor)
            if (currentChunk == null) {
                updateNotification("لا يوجد نص متبقٍ للقراءة")
                stopPlayback()
                return@launch
            }
            maybeStartPlayback()
        }
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            updateNotification("تعذر تهيئة محرك القراءة")
            stopPlayback()
            return
        }
        ttsReady = true
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) {
                handleUtterance(utteranceId, successful = true)
            }

            @Deprecated("Deprecated in Android API")
            override fun onError(utteranceId: String?) {
                handleUtterance(utteranceId, successful = false)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                handleUtterance(utteranceId, successful = false)
            }
        })
        val result = tts?.setLanguage(Locale.forLanguageTag("ar"))
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            updateNotification("الصوت العربي غير متاح؛ نزّل بيانات صوت عربية من إعدادات الجهاز")
            stopPlayback()
            return
        }
        maybeStartPlayback()
    }

    private fun maybeStartPlayback() {
        if (ttsReady && book != null && !paused) speakCurrentChunk()
    }

    private fun speakCurrentChunk() {
        val currentBook = book ?: return
        if (paused) return
        val chunk = currentChunk ?: nextChunk(currentBook.content, cursor).also { currentChunk = it }
        if (chunk == null) {
            serviceScope.launch {
                AppDatabase.get(this@SpeechService).bookDao()
                    .updateProgress(currentBook.id, currentBook.content.length)
                updateNotification("اكتملت القراءة")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            return
        }
        val generation = ++playbackGeneration
        tts?.setSpeechRate(requestedRate)
        val result = tts?.speak(chunk.spokenText, TextToSpeech.QUEUE_FLUSH, null, utteranceId(generation, chunkIndex))
        if (result == TextToSpeech.ERROR) {
            paused = true
            updateNotification("تعذر تشغيل المقطع؛ اضغط استئناف للمحاولة مجددًا")
        } else {
            updateNotification("تتم القراءة: ${currentBook.title}")
        }
    }

    private fun handleUtterance(id: String?, successful: Boolean) {
        val parts = id?.split('-') ?: return
        if (parts.size != 3 || parts[0] != UTTERANCE_PREFIX) return
        val callbackGeneration = parts[1].toLongOrNull() ?: return
        val callbackIndex = parts[2].toIntOrNull() ?: return
        serviceScope.launch {
            if (callbackGeneration != playbackGeneration || callbackIndex != chunkIndex || paused) return@launch
            if (!successful) {
                paused = true
                playbackGeneration++
                tts?.stop()
                updateNotification("تعذر تشغيل هذا المقطع؛ اضغط استئناف للمحاولة مجددًا")
                return@launch
            }
            val currentBook = book ?: return@launch
            val completedChunk = currentChunk ?: return@launch
            cursor = completedChunk.rawEnd
            AppDatabase.get(this@SpeechService).bookDao().updateProgress(currentBook.id, cursor)
            chunkIndex++
            currentChunk = null
            speakCurrentChunk()
        }
    }

    private fun pausePlayback() {
        if (book == null || paused) return
        paused = true
        playbackGeneration++ // Ignore callbacks caused by TextToSpeech.stop().
        tts?.stop()
        updateNotification("متوقفة مؤقتًا")
    }

    private fun resumePlayback() {
        if (book == null) {
            if (requestedBookId > 0L) loadBook(requestedBookId, requestedStart)
            return
        }
        if (!paused) return
        paused = false
        maybeStartPlayback()
    }

    private fun stopPlayback() {
        loadGeneration++
        playbackGeneration++
        paused = true
        tts?.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Split at punctuation/word boundaries and normalize only the next chunk. */
    private fun nextChunk(text: String, from: Int): PlaybackChunk? {
        var start = from.coerceIn(0, text.length)
        while (start < text.length && text[start].isWhitespace()) start++
        if (start >= text.length) return null

        val maxChars = 700
        val punctuation = charArrayOf('.', '؟', '?', '!', '。', '\n', '،', '؛', ':', '…')
        var end = (start + maxChars).coerceAtMost(text.length)
        if (end < text.length) {
            val minimum = start + maxChars / 2
            val punctuationAt = text.lastIndexOfAny(punctuation, end - 1)
            val whitespaceAt = text.lastIndexOf(' ', end - 1)
            end = when {
                punctuationAt >= minimum -> punctuationAt + 1
                whitespaceAt >= minimum -> whitespaceAt + 1
                else -> end
            }
            if (end < text.length && end > 0 && Character.isHighSurrogate(text[end - 1]) && Character.isLowSurrogate(text[end])) {
                end++
            }
        }
        if (end <= start) end = (start + maxChars).coerceAtMost(text.length)
        val spoken = ArabicTextProcessor.normalizeForTts(text.substring(start, end).trim())
        if (spoken.isBlank()) return null
        return PlaybackChunk(end, spoken)
    }

    private fun utteranceId(generation: Long, chunkIndex: Int) = "$UTTERANCE_PREFIX-$generation-$chunkIndex"

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Playback intentionally continues as a foreground media service after the activity is dismissed.
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "تشغيل صوتيات", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun updateNotification(message: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(message))
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
        val toggle = PendingIntent.getService(
            this, 2, Intent(this, SpeechService::class.java)
                .setAction(if (paused) ACTION_RESUME else ACTION_PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(book?.title ?: "صوتيات")
            .setContentText(message)
            .setContentIntent(open)
            .addAction(
                if (paused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause,
                if (paused) "استئناف" else "إيقاف مؤقت",
                toggle
            )
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "إيقاف", stop)
            .setOngoing(!paused)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        const val ACTION_PLAY = "com.sawtiyat.PLAY"
        const val ACTION_PAUSE = "com.sawtiyat.PAUSE"
        const val ACTION_RESUME = "com.sawtiyat.RESUME"
        const val ACTION_STOP = "com.sawtiyat.STOP"
        const val EXTRA_BOOK_ID = "book_id"
        const val EXTRA_START = "start"
        const val EXTRA_RATE = "rate"
        private const val CHANNEL_ID = "sawtiyat_playback"
        private const val NOTIFICATION_ID = 41
        private const val UTTERANCE_PREFIX = "sawtiyat"
    }
}
