package com.example.webm2gif

import android.content.Context
import android.net.Uri
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File

data class ConvertOptions(
    val fps: Int = 10,
    val maxWidth: Int = 480,
    val repeat: Int = 0, // 0 = infinite, -1 = no loop, N = loop N times
    val quality: Quality = Quality.MEDIUM
) {
    enum class Quality(val colors: Int, val dither: String) {
        LOW(64, "bayer"),
        MEDIUM(128, "floyd_steinberg"),
        HIGH(256, "floyd_steinberg")
    }
}

data class ConvertResult(
    val file: File,
    val sizeBytes: Long
)

object FFmpegConverter {

    fun convert(
        context: Context,
        uri: Uri,
        options: ConvertOptions,
        onProgress: (Int) -> Unit,
        onLog: ((String) -> Unit)? = null
    ): ConvertResult {
        val cacheDir = context.cacheDir
        val timestamp = System.currentTimeMillis()
        val inputFile = File(cacheDir, "input_$timestamp.webm")
        val paletteFile = File(cacheDir, "palette_$timestamp.png")
        val outputFile = File(cacheDir, "output_$timestamp.gif")

        try {
            onProgress(5)
            copyUriToCache(context, uri, inputFile)

            val paletteFilter = buildString {
                append("fps=${options.fps},")
                append("scale=${options.maxWidth}:-1:flags=lanczos,")
                append("palettegen=max_colors=${options.quality.colors}:stats_mode=diff")
            }
            val paletteCmd =
                "-y -i \"${inputFile.absolutePath}\" -vf \"$paletteFilter\" \"${paletteFile.absolutePath}\""

            val paletteSession = FFmpegKit.execute(paletteCmd)
            val paletteLogs = paletteSession.allLogsAsString.orEmpty()
            onLog?.invoke(paletteLogs)

            if (!ReturnCode.isSuccess(paletteSession.returnCode) || !paletteFile.exists()) {
                throw RuntimeException(
                    "调色板生成失败 rc=${paletteSession.returnCode}\n${tailLogs(paletteLogs)}"
                )
            }

            onProgress(40)

            val gifFilter = buildString {
                append("[0:v]fps=${options.fps},")
                append("scale=${options.maxWidth}:-1:flags=lanczos[x];")
                append("[x][1:v]paletteuse=dither=${options.quality.dither}")
            }
            val gifCmd = buildString {
                append("-y ")
                append("-i \"${inputFile.absolutePath}\" ")
                append("-i \"${paletteFile.absolutePath}\" ")
                append("-filter_complex \"$gifFilter\" ")
                append("-loop ${options.repeat} ")
                append("\"${outputFile.absolutePath}\"")
            }

            onProgress(60)
            val gifSession = FFmpegKit.execute(gifCmd)
            val gifLogs = gifSession.allLogsAsString.orEmpty()
            onLog?.invoke(gifLogs)

            if (!ReturnCode.isSuccess(gifSession.returnCode) || !outputFile.exists() || outputFile.length() <= 0L) {
                throw RuntimeException(
                    "GIF 合成失败 rc=${gifSession.returnCode}\n${tailLogs(gifLogs)}"
                )
            }

            onProgress(100)
            return ConvertResult(outputFile, outputFile.length())
        } finally {
            inputFile.delete()
            paletteFile.delete()
        }
    }

    private fun copyUriToCache(context: Context, uri: Uri, destFile: File) {
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("无法打开所选文件")

        inputStream.use { input ->
            destFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }

        if (!destFile.exists() || destFile.length() <= 0L) {
            throw IllegalArgumentException("选中的文件内容为空或复制失败")
        }
    }

    private fun tailLogs(logs: String, maxLines: Int = 18): String {
        if (logs.isBlank()) return "没有拿到 FFmpeg 日志输出"
        val lines = logs
            .lineSequence()
            .map { it.trimEnd() }
            .filter { it.isNotBlank() }
            .toList()
        return lines
            .takeLast(maxLines)
            .joinToString("\n")
    }
}
