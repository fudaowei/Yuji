package com.yuji.transcriber

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import java.io.File
import java.util.Date
import java.util.Locale

/**
 * 独立的转写服务：既承接录制结束后的自动转写，也承接用户在主界面对
 * 某条已存在录音手动发起的转写（比如上一次转写中途失败/被打断）。
 * 全程持有唤醒锁，避免熄屏后系统降低 CPU 调度导致转写卡住不动。
 */
class TranscriptionService : Service() {

    companion object {
        const val EXTRA_AUDIO_PATH = "audioPath"
        const val EXTRA_TITLE = "title"
        const val EXTRA_RECORDED_AT_MILLIS = "recordedAtMillis"
        const val EXTRA_DURATION_SECONDS = "durationSeconds"
        const val CHANNEL_ID = "transcription_channel"
        const val NOTIFICATION_ID = 2
        private const val WAKE_LOCK_TIMEOUT_MS = 6 * 60 * 60 * 1000L

        // 停止录制后会自动发起转写，但对应的 .md 落盘前，这个音频文件在"待转写录音"
        // 列表里会短暂地看起来还没转写，容易被再点一次导致同一段录音被转写两次、
        // 生成两份内容相同的文件。用这个集合记录"正在转写中"的音频路径，
        // 既用于拦截重复转写请求，也供 TranscriptRepository 把它从待转写列表里过滤掉。
        private val activePaths = java.util.Collections.synchronizedSet(mutableSetOf<String>())

        fun isTranscribing(path: String): Boolean = activePaths.contains(path)
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val path = intent?.getStringExtra(EXTRA_AUDIO_PATH)
        val file = path?.let { File(it) }
        if (file == null || !file.exists() || !Transcriber.isModelReady()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!activePaths.add(file.absolutePath)) {
            // 这个文件已经有一次转写在进行中，直接忽略这次重复请求，避免生成重复文件。
            stopSelf()
            return START_NOT_STICKY
        }

        createChannelIfNeeded()
        val title = intent.getStringExtra(EXTRA_TITLE) ?: file.nameWithoutExtension
        startForeground(NOTIFICATION_ID, buildNotification("正在转写「$title」…"))

        val recordedAtMillis = intent.getLongExtra(EXTRA_RECORDED_AT_MILLIS, file.lastModified())
        val durationSeconds = intent.getLongExtra(EXTRA_DURATION_SECONDS, estimateWavDurationSeconds(file))

        acquireWakeLock()
        Thread {
            try {
                val text = Transcriber.transcribe(applicationContext, file) { processed, total ->
                    updateNotification("正在转写「$title」… ${formatMinSec(processed)}/${formatMinSec(total)}")
                }
                MarkdownStore.save(
                    title = title,
                    recordedAt = Date(recordedAtMillis),
                    durationSeconds = durationSeconds,
                    audioFileName = file.name,
                    text = text,
                )
                updateNotification("转写完成：$title")
            } catch (e: Exception) {
                updateNotification("转写失败：${e.message}")
            } finally {
                activePaths.remove(file.absolutePath)
                releaseWakeLock()
                Thread.sleep(1500)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }.start()

        return START_NOT_STICKY
    }

    private fun estimateWavDurationSeconds(file: File): Long {
        val dataBytes = (file.length() - 44).coerceAtLeast(0)
        val bytesPerSecond = RecordingService.SAMPLE_RATE * 2 // 16-bit mono
        return dataBytes / bytesPerSecond
    }

    private fun formatMinSec(totalSeconds: Int): String {
        val m = totalSeconds / 60
        val s = totalSeconds % 60
        return String.format(Locale.CHINA, "%d:%02d", m, s)
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Yuji:transcribe")
        lock.acquire(WAKE_LOCK_TIMEOUT_MS)
        wakeLock = lock
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun createChannelIfNeeded() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID, "课程转写", NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("语记")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseWakeLock()
    }
}
