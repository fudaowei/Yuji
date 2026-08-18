package com.yuji.transcriber

import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class TranscriptViewActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FILE_PATH = "filePath"
        const val EXTRA_QUERY = "query"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_transcript_view)

        val path = intent.getStringExtra(EXTRA_FILE_PATH) ?: return
        val query = intent.getStringExtra(EXTRA_QUERY).orEmpty()
        val file = File(path)
        if (!file.exists()) return

        val content = file.readText()
        val end = if (content.startsWith("---")) content.indexOf("\n---", 3) else -1
        val frontmatter = if (end != -1) content.substring(3, end).trim() else ""
        val body = if (end != -1) content.substring(end + 4).trimStart('\n') else content

        var title = file.nameWithoutExtension
        var date = ""
        var audioFileName: String? = null
        frontmatter.lines().forEach { line ->
            val idx = line.indexOf(':')
            if (idx > 0) {
                val key = line.substring(0, idx).trim()
                val value = line.substring(idx + 1).trim()
                when (key) {
                    "title" -> title = value
                    "date" -> date = value
                    "audio_file" -> audioFileName = value
                }
            }
        }

        findViewById<TextView>(R.id.detailTitle).text = title
        findViewById<TextView>(R.id.detailDate).text = date
        findViewById<Button>(R.id.deleteButton).setOnClickListener {
            confirmDelete(TranscriptEntry(file, title, date, body, audioFileName))
        }

        val bodyView = findViewById<TextView>(R.id.detailBody)
        if (query.isNotBlank()) {
            val spannable = SpannableString(body)
            var searchFrom = 0
            var firstMatch = -1
            while (true) {
                val idx = body.indexOf(query, searchFrom, ignoreCase = true)
                if (idx == -1) break
                if (firstMatch == -1) firstMatch = idx
                spannable.setSpan(
                    BackgroundColorSpan(Color.YELLOW),
                    idx,
                    idx + query.length,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
                searchFrom = idx + query.length
            }
            bodyView.text = spannable

            if (firstMatch != -1) {
                bodyView.post {
                    val layout = bodyView.layout ?: return@post
                    val line = layout.getLineForOffset(firstMatch)
                    val y = layout.getLineTop(line)
                    val scrollView = bodyView.parent.parent as? ScrollView
                    scrollView?.smoothScrollTo(0, bodyView.top + y)
                }
            }
        } else {
            bodyView.text = body
        }
    }

    private fun confirmDelete(entry: TranscriptEntry) {
        AlertDialog.Builder(this)
            .setTitle("删除「${entry.title}」")
            .setMessage("这条转写内容还满意吗？")
            .setPositiveButton("仅删文字，保留录音") { _, _ ->
                TranscriptRepository.deleteTranscript(entry, alsoDeleteAudio = false)
                Toast.makeText(this, "已删除转写文字，录音已放回待转写列表", Toast.LENGTH_SHORT).show()
                finish()
            }
            .setNegativeButton("连录音一起删除") { _, _ ->
                TranscriptRepository.deleteTranscript(entry, alsoDeleteAudio = true)
                Toast.makeText(this, "已删除转写文字和录音", Toast.LENGTH_SHORT).show()
                finish()
            }
            .setNeutralButton("取消", null)
            .show()
    }
}
