package com.example.webm2gif

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private var selectedUri: Uri? = null
    private var outputFile: File? = null

    // Views
    private lateinit var btnPick: Button
    private lateinit var tvFileName: TextView
    private lateinit var layoutSettings: LinearLayout
    private lateinit var seekFps: SeekBar
    private lateinit var tvFps: TextView
    private lateinit var seekWidth: SeekBar
    private lateinit var tvWidth: TextView
    private lateinit var spinnerRepeat: Spinner
    private lateinit var spinnerQuality: Spinner
    private lateinit var btnConvert: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var tvProgress: TextView
    private lateinit var tvLog: TextView
    private lateinit var layoutResult: LinearLayout
    private lateinit var tvResultInfo: TextView
    private lateinit var btnShare: Button
    private lateinit var btnSave: Button

    private val pickVideo = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { handleSelectedFile(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        setupControls()

        // 处理从其他 App 分享进来的文件
        when (intent?.action) {
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                uri?.let { handleSelectedFile(it) }
            }
            Intent.ACTION_VIEW -> intent.data?.let { handleSelectedFile(it) }
        }
    }

    private fun bindViews() {
        btnPick        = findViewById(R.id.btnPick)
        tvFileName     = findViewById(R.id.tvFileName)
        layoutSettings = findViewById(R.id.layoutSettings)
        seekFps        = findViewById(R.id.seekFps)
        tvFps          = findViewById(R.id.tvFps)
        seekWidth      = findViewById(R.id.seekWidth)
        tvWidth        = findViewById(R.id.tvWidth)
        spinnerRepeat  = findViewById(R.id.spinnerRepeat)
        spinnerQuality = findViewById(R.id.spinnerQuality)
        btnConvert     = findViewById(R.id.btnConvert)
        progressBar    = findViewById(R.id.progressBar)
        tvProgress     = findViewById(R.id.tvProgress)
        tvLog          = findViewById(R.id.tvLog)
        layoutResult   = findViewById(R.id.layoutResult)
        tvResultInfo   = findViewById(R.id.tvResultInfo)
        btnShare       = findViewById(R.id.btnShare)
        btnSave        = findViewById(R.id.btnSave)
    }

    private fun setupControls() {
        btnPick.setOnClickListener { pickVideo.launch("video/webm") }

        // FPS: 5–30，step 1，default 8
        seekFps.max = 25
        seekFps.progress = 3
        updateFpsLabel()
        seekFps.setOnSeekBarChangeListener(simpleSeekListener { updateFpsLabel() })

        // 宽度: 160–800，step 40，default 320
        seekWidth.max = 16
        seekWidth.progress = 4
        updateWidthLabel()
        seekWidth.setOnSeekBarChangeListener(simpleSeekListener { updateWidthLabel() })

        // 循环
        ArrayAdapter.createFromResource(this, R.array.repeat_options,
            android.R.layout.simple_spinner_item).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinnerRepeat.adapter = it
        }

        // 质量
        ArrayAdapter.createFromResource(this, R.array.quality_options,
            android.R.layout.simple_spinner_item).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinnerQuality.adapter = it
            spinnerQuality.setSelection(0) // 默认快速，体积更小
        }

        btnConvert.setOnClickListener { startConvert() }

        btnShare.setOnClickListener {
            outputFile?.let { file ->
                val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
                startActivity(Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "image/gif"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }, "分享 GIF"
                ))
            }
        }

        btnSave.setOnClickListener { saveToDownloads() }
    }

    private fun getFps() = seekFps.progress + 5
    private fun getWidth() = (seekWidth.progress + 4) * 40
    private fun updateFpsLabel() { tvFps.text = "${getFps()} fps" }
    private fun updateWidthLabel() { tvWidth.text = "${getWidth()} px" }

    private fun getQuality() = when (spinnerQuality.selectedItemPosition) {
        0    -> ConvertOptions.Quality.LOW
        2    -> ConvertOptions.Quality.HIGH
        else -> ConvertOptions.Quality.MEDIUM
    }

    private fun getRepeat() = when (spinnerRepeat.selectedItemPosition) {
        1    -> -1
        2    -> 3
        else -> 0
    }

    private fun handleSelectedFile(uri: Uri) {
        selectedUri = uri
        val name = contentResolver.query(uri, arrayOf(
            android.provider.OpenableColumns.DISPLAY_NAME), null, null, null
        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            ?: uri.lastPathSegment ?: "已选文件"

        tvFileName.text = name
        layoutSettings.visibility = View.VISIBLE
        layoutResult.visibility = View.GONE
        tvLog.visibility = View.GONE
        tvLog.text = ""
        btnConvert.isEnabled = true
    }

    private fun startConvert() {
        val uri = selectedUri ?: return
        val options = ConvertOptions(
            fps      = getFps(),
            maxWidth = getWidth(),
            repeat   = getRepeat(),
            quality  = getQuality()
        )

        btnConvert.isEnabled = false
        progressBar.visibility = View.VISIBLE
        progressBar.progress = 0
        tvProgress.visibility = View.VISIBLE
        tvProgress.text = "生成调色板…"
        tvLog.visibility = View.GONE
        tvLog.text = ""
        layoutResult.visibility = View.GONE

        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    FFmpegConverter.convert(
                        context    = this@MainActivity,
                        uri        = uri,
                        options    = options,
                        onProgress = { pct ->
                            runOnUiThread {
                                progressBar.progress = pct
                                tvProgress.text = when {
                                    pct < 40  -> "生成调色板… $pct%"
                                    pct < 100 -> "合成 GIF… $pct%"
                                    else      -> "完成！"
                                }
                            }
                        },
                        onLog = { log ->
                            // 仅在 debug 时显示
                            // runOnUiThread { appendLog(log) }
                        }
                    )
                }
                outputFile = result.file
                showResult(result)
            } catch (e: Exception) {
                tvLog.visibility = View.VISIBLE
                tvLog.text = "错误：${e.message}"
                Toast.makeText(this@MainActivity, "转换失败：${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                btnConvert.isEnabled = true
                progressBar.visibility = View.GONE
                tvProgress.visibility = View.GONE
            }
        }
    }

    private fun showResult(result: ConvertResult) {
        layoutResult.visibility = View.VISIBLE
        val sizeMb = result.sizeBytes / 1024.0 / 1024.0
        tvResultInfo.text = "✓ 转换完成  ${String.format("%.2f", sizeMb)} MB"
    }

    private fun saveToDownloads() {
        val file = outputFile ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, "gif_${System.currentTimeMillis()}.gif")
                    put(MediaStore.Downloads.MIME_TYPE, "image/gif")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val itemUri = contentResolver.insert(collection, values)!!
                contentResolver.openOutputStream(itemUri)!!.use { os -> file.inputStream().copyTo(os) }
                values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0)
                contentResolver.update(itemUri, values, null, null)
            } else {
                val dest = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "gif_${System.currentTimeMillis()}.gif"
                )
                file.copyTo(dest, overwrite = true)
                @Suppress("DEPRECATION")
                sendBroadcast(Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(dest)))
            }
            Toast.makeText(this, "已保存到下载文件夹 ✓", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "保存失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun simpleSeekListener(onChange: () -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar, p: Int, u: Boolean) = onChange()
        override fun onStartTrackingTouch(sb: SeekBar) {}
        override fun onStopTrackingTouch(sb: SeekBar) {}
    }
}
