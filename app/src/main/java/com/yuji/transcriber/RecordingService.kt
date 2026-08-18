package com.yuji.transcriber

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 只负责音频捕获（开始/暂停/继续/停止）。转写另由 [TranscriptionService] 承担——
 * 二者分开是因为本服务的 foregroundServiceType 是 mediaProjection，Android 14 起
 * 要求调用 startForeground() 时必须持有一个有效的 MediaProjection 会话，而转写本身
 * 与投影无关（尤其是用户手动对旧录音发起的转写，此时并没有 MediaProjection）。
 */
class RecordingService : Service() {

    companion object {
        const val ACTION_START = "com.yuji.transcriber.action.START"
        const val ACTION_STOP = "com.yuji.transcriber.action.STOP"
        const val ACTION_PAUSE = "com.yuji.transcriber.action.PAUSE"
        const val ACTION_RESUME = "com.yuji.transcriber.action.RESUME"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        const val EXTRA_TITLE = "title"
        const val CHANNEL_ID = "recording_channel"
        const val NOTIFICATION_ID = 1
        const val SAMPLE_RATE = 16000
        private const val WAKE_LOCK_TIMEOUT_MS = 6 * 60 * 60 * 1000L
    }

    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var recordThread: Thread? = null
    private val isRecording = AtomicBoolean(false)
    private val isPaused = AtomicBoolean(false)
    private var currentTitle: String = "未命名课程"
    private var currentOutFile: File? = null
    private var startTimeMillis: Long = 0L
    private var recordedMillis: Long = 0L
    private var lastResumeMillis: Long = 0L
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_STOP -> handleStop()
            ACTION_PAUSE -> handlePause()
            ACTION_RESUME -> handleResume()
        }
        return START_NOT_STICKY
    }

    private fun handleStart(intent: Intent) {
        createChannelIfNeeded()
        startForeground(NOTIFICATION_ID, buildNotification("正在录制课程音频…"))

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "未命名课程"

        if (resultData == null) {
            stopSelf()
            return
        }

        // 持有唤醒锁，避免录制中途熄屏后系统限制 CPU 导致丢帧/服务被系统判定为空闲。
        acquireWakeLock()

        val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = projectionManager.getMediaProjection(resultCode, resultData)
        mediaProjection = projection

        val playbackConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .build()

        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()

        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = if (minBufferSize > 0) minBufferSize * 4 else SAMPLE_RATE * 2

        val record = AudioRecord.Builder()
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(bufferSize)
            .setAudioPlaybackCaptureConfig(playbackConfig)
            .build()

        val dir = Storage.recordingsDir
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(Date())
        val safeTitle = title.replace(Regex("[^\\u4e00-\\u9fa5A-Za-z0-9_-]"), "_")
        val outFile = File(dir, "${stamp}_${safeTitle}.wav")

        currentTitle = title
        currentOutFile = outFile
        startTimeMillis = System.currentTimeMillis()
        recordedMillis = 0L
        lastResumeMillis = startTimeMillis
        isPaused.set(false)

        audioRecord = record
        isRecording.set(true)
        record.startRecording()

        recordThread = Thread {
            writeWavFile(record, outFile, bufferSize)
        }.also { it.start() }
    }

    private fun handlePause() {
        if (!isRecording.get() || isPaused.get()) return
        isPaused.set(true)
        recordedMillis += System.currentTimeMillis() - lastResumeMillis
        audioRecord?.stop()
        updateNotification("已暂停录制「$currentTitle」")
    }

    private fun handleResume() {
        if (!isRecording.get() || !isPaused.get()) return
        lastResumeMillis = System.currentTimeMillis()
        isPaused.set(false)
        audioRecord?.startRecording()
        updateNotification("正在录制课程音频…")
    }

    private fun writeWavFile(record: AudioRecord, outFile: File, bufferSize: Int) {
        val buffer = ByteArray(bufferSize)
        val raf = RandomAccessFile(outFile, "rw")
        raf.setLength(0)
        raf.write(ByteArray(44)) // 占位，录制完成后回填真实的 WAV 头
        var totalBytes = 0L
        try {
            while (isRecording.get()) {
                if (isPaused.get()) {
                    Thread.sleep(100)
                    continue
                }
                val read = record.read(buffer, 0, buffer.size)
                if (read > 0) {
                    raf.write(buffer, 0, read)
                    totalBytes += read
                }
            }
        } finally {
            finalizeWavHeader(raf, totalBytes)
            raf.close()
        }
    }

    private fun finalizeWavHeader(raf: RandomAccessFile, totalAudioLen: Long) {
        val totalDataLen = totalAudioLen + 36
        val channels = 1
        val byteRate = SAMPLE_RATE * channels * 2

        raf.seek(0)
        val header = ByteArray(44)
        header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
        writeIntLE(header, 4, totalDataLen.toInt())
        header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
        writeIntLE(header, 16, 16)
        writeShortLE(header, 20, 1)
        writeShortLE(header, 22, channels)
        writeIntLE(header, 24, SAMPLE_RATE)
        writeIntLE(header, 28, byteRate)
        writeShortLE(header, 32, channels * 2)
        writeShortLE(header, 34, 16)
        header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
        writeIntLE(header, 40, totalAudioLen.toInt())
        raf.write(header)
    }

    private fun writeIntLE(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xff).toByte()
        bytes[offset + 1] = ((value shr 8) and 0xff).toByte()
        bytes[offset + 2] = ((value shr 16) and 0xff).toByte()
        bytes[offset + 3] = ((value shr 24) and 0xff).toByte()
    }

    private fun writeShortLE(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xff).toByte()
        bytes[offset + 1] = ((value shr 8) and 0xff).toByte()
    }

    private fun handleStop() {
        isRecording.set(false)
        if (!isPaused.get()) {
            recordedMillis += System.currentTimeMillis() - lastResumeMillis
        }
        recordThread?.join(2000)
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        mediaProjection?.stop()
        mediaProjection = null

        val outFile = currentOutFile
        val title = currentTitle
        val durationSeconds = recordedMillis / 1000
        val recordedAtMillis = startTimeMillis

        if (outFile != null && Transcriber.isModelReady()) {
            val intent = Intent(this, TranscriptionService::class.java).apply {
                putExtra(TranscriptionService.EXTRA_AUDIO_PATH, outFile.absolutePath)
                putExtra(TranscriptionService.EXTRA_TITLE, title)
                putExtra(TranscriptionService.EXTRA_RECORDED_AT_MILLIS, recordedAtMillis)
                putExtra(TranscriptionService.EXTRA_DURATION_SECONDS, durationSeconds)
            }
            ContextCompat.startForegroundService(this, intent)
        }

        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Yuji:record")
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
                CHANNEL_ID, "课程录制", NotificationManager.IMPORTANCE_LOW
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
        isRecording.set(false)
        mediaProjection?.stop()
        releaseWakeLock()
    }
}
