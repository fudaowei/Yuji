package com.yuji.transcriber

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var titleInput: EditText
    private lateinit var startButton: Button
    private lateinit var pauseButton: Button
    private lateinit var stopButton: Button
    private lateinit var listView: ListView
    private lateinit var pendingLabel: TextView
    private lateinit var pendingList: ListView
    private lateinit var searchNavButton: Button
    private var recording = false
    private var paused = false
    private var entries: List<TranscriptEntry> = emptyList()
    private var pendingFiles: List<File> = emptyList()

    private val projectionManager by lazy {
        getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val title = titleInput.text.toString().ifBlank { "未命名课程" }
            val intent = Intent(this, RecordingService::class.java).apply {
                action = RecordingService.ACTION_START
                putExtra(RecordingService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(RecordingService.EXTRA_RESULT_DATA, result.data)
                putExtra(RecordingService.EXTRA_TITLE, title)
            }
            ContextCompat.startForegroundService(this, intent)
            recording = true
            paused = false
            updateButtons()
        } else {
            Toast.makeText(this, "未获得音频捕获授权，无法录制", Toast.LENGTH_LONG).show()
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            launchProjection()
        } else {
            Toast.makeText(this, "需要录音和通知权限才能录制", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        titleInput = findViewById(R.id.titleInput)
        startButton = findViewById(R.id.startButton)
        pauseButton = findViewById(R.id.pauseButton)
        stopButton = findViewById(R.id.stopButton)
        listView = findViewById(R.id.recordingList)
        pendingLabel = findViewById(R.id.pendingLabel)
        pendingList = findViewById(R.id.pendingList)
        searchNavButton = findViewById(R.id.searchNavButton)

        startButton.setOnClickListener { onStartClicked() }
        pauseButton.setOnClickListener { onPauseClicked() }
        stopButton.setOnClickListener { onStopClicked() }
        searchNavButton.setOnClickListener {
            startActivity(Intent(this, SearchActivity::class.java))
        }
        listView.setOnItemClickListener { _, _, position, _ ->
            val entry = entries[position]
            val intent = Intent(this, TranscriptViewActivity::class.java).apply {
                putExtra(TranscriptViewActivity.EXTRA_FILE_PATH, entry.file.absolutePath)
            }
            startActivity(intent)
        }
        pendingList.setOnItemClickListener { _, _, position, _ ->
            onPendingItemClicked(pendingFiles[position])
        }
        listView.setOnItemLongClickListener { _, _, position, _ ->
            confirmDeleteTranscript(entries[position])
            true
        }
        pendingList.setOnItemLongClickListener { _, _, position, _ ->
            confirmDeletePending(pendingFiles[position])
            true
        }

        updateButtons()
        refreshList()
    }

    private fun confirmDeleteTranscript(entry: TranscriptEntry) {
        AlertDialog.Builder(this)
            .setTitle("删除「${entry.title}」")
            .setMessage("这条转写内容还满意吗？")
            .setPositiveButton("仅删文字，保留录音") { _, _ ->
                TranscriptRepository.deleteTranscript(entry, alsoDeleteAudio = false)
                Toast.makeText(this, "已删除转写文字，录音已放回待转写列表", Toast.LENGTH_SHORT).show()
                refreshList()
            }
            .setNegativeButton("连录音一起删除") { _, _ ->
                TranscriptRepository.deleteTranscript(entry, alsoDeleteAudio = true)
                Toast.makeText(this, "已删除转写文字和录音", Toast.LENGTH_SHORT).show()
                refreshList()
            }
            .setNeutralButton("取消", null)
            .show()
    }

    private fun confirmDeletePending(file: File) {
        if (recording) {
            Toast.makeText(this, "请先停止当前录制，再删除其他录音", Toast.LENGTH_SHORT).show()
            return
        }
        val title = file.nameWithoutExtension.replace(Regex("^\\d{8}_\\d{6}_"), "")
        AlertDialog.Builder(this)
            .setTitle("删除录音「$title」？")
            .setMessage("这条录音还没有转写文字，删除后无法恢复。")
            .setPositiveButton("删除") { _, _ ->
                TranscriptRepository.deletePendingRecording(file)
                Toast.makeText(this, "已删除录音", Toast.LENGTH_SHORT).show()
                refreshList()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    private fun onPauseClicked() {
        val action = if (paused) RecordingService.ACTION_RESUME else RecordingService.ACTION_PAUSE
        val intent = Intent(this, RecordingService::class.java).apply { this.action = action }
        startService(intent)
        paused = !paused
        updateButtons()
    }

    private fun onPendingItemClicked(file: File) {
        if (recording) {
            Toast.makeText(this, "请先停止当前录制，再手动转写其他录音", Toast.LENGTH_SHORT).show()
            return
        }
        val title = file.nameWithoutExtension.replace(Regex("^\\d{8}_\\d{6}_"), "")
        val intent = Intent(this, TranscriptionService::class.java).apply {
            putExtra(TranscriptionService.EXTRA_AUDIO_PATH, file.absolutePath)
            putExtra(TranscriptionService.EXTRA_TITLE, title)
        }
        ContextCompat.startForegroundService(this, intent)
        Toast.makeText(this, "已开始转写「$title」，进度见通知栏", Toast.LENGTH_SHORT).show()
        listView.postDelayed({ refreshList() }, 1000)
    }

    private fun onStartClicked() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            Toast.makeText(this, "请先授予“所有文件访问权限”，用于保存转写文本", Toast.LENGTH_LONG).show()
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
            return
        }
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.RECORD_AUDIO
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        } else {
            launchProjection()
        }
    }

    private fun launchProjection() {
        projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    private fun onStopClicked() {
        val intent = Intent(this, RecordingService::class.java).apply {
            action = RecordingService.ACTION_STOP
        }
        startService(intent)
        recording = false
        paused = false
        updateButtons()
        listView.postDelayed({ refreshList() }, 1000)
    }

    private fun updateButtons() {
        startButton.isEnabled = !recording
        pauseButton.isEnabled = recording
        pauseButton.text = if (paused) "继续" else "暂停"
        stopButton.isEnabled = recording
        titleInput.isEnabled = !recording
    }

    private fun refreshList() {
        entries = TranscriptRepository.loadAll()
        listView.adapter = EntryAdapter(entries)

        pendingFiles = TranscriptRepository.loadPendingRecordings()
        pendingList.adapter = PendingAdapter(pendingFiles)
        val hasPending = pendingFiles.isNotEmpty()
        pendingLabel.visibility = if (hasPending) View.VISIBLE else View.GONE
        pendingList.visibility = if (hasPending) View.VISIBLE else View.GONE
    }

    private inner class EntryAdapter(private val items: List<TranscriptEntry>) : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(this@MainActivity)
                .inflate(android.R.layout.simple_list_item_2, parent, false)
            val item = items[position]
            view.findViewById<TextView>(android.R.id.text1).text = item.title
            view.findViewById<TextView>(android.R.id.text2).text = item.date
            return view
        }
    }

    private inner class PendingAdapter(private val items: List<File>) : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(this@MainActivity)
                .inflate(android.R.layout.simple_list_item_2, parent, false)
            val item = items[position]
            val title = item.nameWithoutExtension.replace(Regex("^\\d{8}_\\d{6}_"), "")
            view.findViewById<TextView>(android.R.id.text1).text = title
            view.findViewById<TextView>(android.R.id.text2).text = "尚未转写，点击手动转写"
            return view
        }
    }
}
