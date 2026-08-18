package com.yuji.transcriber

import android.os.Environment
import java.io.File

object Storage {
    private const val DIR_NAME = "Yuji"

    val baseDir: File
        get() = File(Environment.getExternalStorageDirectory(), DIR_NAME)

    val recordingsDir: File
        get() = File(baseDir, "recordings").apply { mkdirs() }

    val transcriptsDir: File
        get() = File(baseDir, "transcripts").apply { mkdirs() }
}
