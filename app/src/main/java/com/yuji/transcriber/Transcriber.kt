package com.yuji.transcriber

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OfflinePunctuation
import com.k2fsa.sherpa.onnx.OfflinePunctuationConfig
import com.k2fsa.sherpa.onnx.OfflinePunctuationModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.WaveReader
import java.io.File

/**
 * 识别 + 标点模型直接打包进安装包的 assets（见 app/src/main/assets/model），
 * 随装随用，不需要用户额外下载或手动拷贝模型文件。
 */
object Transcriber {

    private const val PARAFORMER_DIR = "model/paraformer-zh"
    private const val PUNCT_DIR = "model/punct-ct-transformer"

    /** 非流式识别模型不适合一次性喂入整节课（20-40 分钟）的音频，
     * 否则单次计算量过大，会导致长时间 CPU 满载（发烫、UI 卡顿）甚至耗时失控。
     * 按固定时长分段识别，既能显著降低单次计算峰值，也能中途汇报进度。 */
    private const val SEGMENT_SECONDS = 30

    @Volatile
    private var recognizer: OfflineRecognizer? = null

    @Volatile
    private var punctuation: OfflinePunctuation? = null

    fun isModelReady(): Boolean = true

    @Synchronized
    private fun getOrCreatePunctuation(context: Context): OfflinePunctuation {
        punctuation?.let { return it }

        val config = OfflinePunctuationConfig(
            model = OfflinePunctuationModelConfig(
                ctTransformer = "$PUNCT_DIR/model.int8.onnx",
                numThreads = 2,
            ),
        )
        val p = OfflinePunctuation(assetManager = context.assets, config = config)
        punctuation = p
        return p
    }

    @Synchronized
    private fun getOrCreateRecognizer(context: Context): OfflineRecognizer {
        recognizer?.let { return it }

        val config = OfflineRecognizerConfig(
            modelConfig = OfflineModelConfig(
                paraformer = OfflineParaformerModelConfig(
                    model = "$PARAFORMER_DIR/model.int8.onnx",
                ),
                tokens = "$PARAFORMER_DIR/tokens.txt",
                modelType = "paraformer",
                numThreads = 2,
            ),
        )
        val r = OfflineRecognizer(assetManager = context.assets, config = config)
        recognizer = r
        return r
    }

    /**
     * 同步方法，需在后台线程调用。返回带标点的识别文本。
     * 按 [SEGMENT_SECONDS] 分段识别；[onProgress] 在每段处理完后回调一次
     * （已处理秒数，音频总秒数），供调用方更新进度提示。
     */
    fun transcribe(
        context: Context,
        wavFile: File,
        onProgress: (processedSeconds: Int, totalSeconds: Int) -> Unit = { _, _ -> },
    ): String {
        val appContext = context.applicationContext
        val recognizer = getOrCreateRecognizer(appContext)
        val waveData = WaveReader.readWave(filename = wavFile.absolutePath)
        val sampleRate = waveData.sampleRate
        val samples = waveData.samples
        val totalSeconds = if (sampleRate > 0) {
            Math.ceil(samples.size / sampleRate.toDouble()).toInt()
        } else 0
        val segmentSize = SEGMENT_SECONDS * sampleRate

        val builder = StringBuilder()
        var offset = 0
        while (offset < samples.size) {
            val end = minOf(offset + segmentSize, samples.size)
            val chunk = samples.copyOfRange(offset, end)
            val stream = recognizer.createStream()
            try {
                stream.acceptWaveform(chunk, sampleRate = sampleRate)
                recognizer.decode(stream)
                val chunkText = recognizer.getResult(stream).text
                if (chunkText.isNotBlank()) {
                    builder.append(chunkText)
                }
            } finally {
                stream.release()
            }
            offset = end
            onProgress(minOf(offset / sampleRate, totalSeconds), totalSeconds)
        }

        val rawText = builder.toString()
        val punct = getOrCreatePunctuation(appContext)
        return punct.addPunctuation(rawText)
    }
}
