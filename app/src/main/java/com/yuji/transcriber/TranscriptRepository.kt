package com.yuji.transcriber

import java.io.File

data class TranscriptEntry(
    val file: File,
    val title: String,
    val date: String,
    val body: String,
    val audioFileName: String?,
)

object TranscriptRepository {

    fun loadAll(): List<TranscriptEntry> {
        return Storage.transcriptsDir.listFiles { f -> f.extension == "md" }
            ?.sortedByDescending { it.lastModified() }
            ?.map { parse(it) }
            ?: emptyList()
    }

    /** 录音文件已存在、但还没有对应转写 .md 的文件（自动转写失败/被中断时的补救入口）。 */
    fun loadPendingRecordings(): List<File> {
        val transcribedAudioFiles = Storage.transcriptsDir.listFiles { f -> f.extension == "md" }
            ?.mapNotNull { readFrontmatter(it)["audio_file"] }
            ?.toSet()
            ?: emptySet()
        return Storage.recordingsDir.listFiles { f -> f.extension == "wav" }
            ?.filter { it.name !in transcribedAudioFiles && !TranscriptionService.isTranscribing(it.absolutePath) }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    /** 删除一条转写记录；[alsoDeleteAudio] 为 true 时连同原始录音一并删除，
     * 否则只删文字，原始录音会重新出现在"待转写录音"里，方便日后重新转写。 */
    fun deleteTranscript(entry: TranscriptEntry, alsoDeleteAudio: Boolean) {
        entry.file.delete()
        if (alsoDeleteAudio && entry.audioFileName != null) {
            File(Storage.recordingsDir, entry.audioFileName).delete()
        }
    }

    fun deletePendingRecording(file: File) {
        file.delete()
    }

    private fun readFrontmatter(file: File): Map<String, String> {
        val content = file.readText()
        if (!content.startsWith("---")) return emptyMap()
        val end = content.indexOf("\n---", 3)
        if (end == -1) return emptyMap()
        return content.substring(3, end).trim().lines().mapNotNull { line ->
            val idx = line.indexOf(':')
            if (idx > 0) line.substring(0, idx).trim() to line.substring(idx + 1).trim() else null
        }.toMap()
    }

    private fun parse(file: File): TranscriptEntry {
        val content = file.readText()
        if (!content.startsWith("---")) {
            return TranscriptEntry(file, file.nameWithoutExtension, "", content, null)
        }
        val end = content.indexOf("\n---", 3)
        if (end == -1) {
            return TranscriptEntry(file, file.nameWithoutExtension, "", content, null)
        }
        val frontmatter = readFrontmatter(file)
        val body = content.substring(end + 4).trimStart('\n')
        val title = frontmatter["title"] ?: file.nameWithoutExtension
        val date = frontmatter["date"] ?: ""
        return TranscriptEntry(file, title, date, body, frontmatter["audio_file"])
    }
}
