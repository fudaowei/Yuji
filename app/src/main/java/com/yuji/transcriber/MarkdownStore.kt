package com.yuji.transcriber

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object MarkdownStore {

    fun save(
        title: String,
        recordedAt: Date,
        durationSeconds: Long,
        audioFileName: String,
        text: String,
    ): File {
        val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(recordedAt)
        val stampForName = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(recordedAt)
        val safeTitle = title.replace(Regex("[^\\u4e00-\\u9fa5A-Za-z0-9_-]"), "_")

        val content = buildString {
            append("---\n")
            append("title: ").append(title).append('\n')
            append("date: ").append(dateFmt).append('\n')
            append("duration_seconds: ").append(durationSeconds).append('\n')
            append("audio_file: ").append(audioFileName).append('\n')
            append("---\n\n")
            append(text.trim())
            append('\n')
        }

        val outFile = File(Storage.transcriptsDir, "${stampForName}_${safeTitle}.md")
        outFile.writeText(content)
        return outFile
    }
}
