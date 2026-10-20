package com.hddownloader.app

import android.Manifest
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import coil.load
import com.hddownloader.app.databinding.ActivityMainBinding
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.mapper.VideoInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding

    private var player: ExoPlayer? = null
    private var currentUrl: String? = null
    private var previewUrl: String? = null
    private var previewHeaders: Map<String, String>? = null

    private var options: List<QualityOption> = emptyList()
    private var downloadJob: Job? = null
    private var processId: String? = null
    private var userCancelled = false

    private data class QualityOption(
        val label: String,
        val selector: String,
        val audioOnly: Boolean = false
    )

    private val storagePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startDownload() else toast("Storage permission chahiye video save karne ke liye")
        }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* optional */ }

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.btnPaste.setOnClickListener { pasteFromClipboard() }
        b.btnFetch.setOnClickListener { fetchInfo() }
        b.btnPlay.setOnClickListener { startPreview() }
        b.btnDownload.setOnClickListener { startDownload() }
        b.btnCancel.setOnClickListener { cancelDownload() }
        b.btnUpdate.setOnClickListener { updateEngine() }
        b.etUrl.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER)
            ) {
                fetchInfo(); true
            } else false
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // Warm up the engine in the background so the first "Get Video" is fast.
        lifecycleScope.launch {
            try { ensureEngine() } catch (_: Exception) { }
        }

        handleShareIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        releasePlayer()
        super.onDestroy()
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND) {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            val url = extractUrl(text)
            if (url != null) {
                b.etUrl.setText(url)
                fetchInfo()
            }
        }
    }

    // ------------------------------------------------------------------ engine

    private suspend fun ensureEngine() = withContext(Dispatchers.IO) {
        engineMutex.withLock {
            if (!engineReady) {
                YoutubeDL.getInstance().init(applicationContext)
                FFmpeg.getInstance().init(applicationContext)
                engineReady = true
            }
        }
    }

    private fun updateEngine() {
        b.btnUpdate.isEnabled = false
        toast("Engine update ho raha hai…")
        lifecycleScope.launch {
            try {
                ensureEngine()
                val status = withContext(Dispatchers.IO) {
                    YoutubeDL.getInstance().updateYoutubeDL(applicationContext)
                }
                toast("Engine: $status")
            } catch (e: Exception) {
                toast("Update fail: ${e.message}")
            } finally {
                b.btnUpdate.isEnabled = true
            }
        }
    }

    // ------------------------------------------------------------------ fetch info

    private fun pasteFromClipboard() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        if (text.isBlank()) {
            toast("Clipboard khali hai")
        } else {
            b.etUrl.setText(extractUrl(text) ?: text.trim())
            fetchInfo()
        }
    }

    private fun fetchInfo() {
        val url = extractUrl(b.etUrl.text?.toString().orEmpty())
        if (url == null) {
            toast("Sahi video link paste karein")
            return
        }
        if (downloadJob?.isActive == true) {
            toast("Pehle current download khatam ya cancel karein")
            return
        }

        releasePlayer()
        b.cardPreview.visibility = View.GONE
        b.downloadSection.visibility = View.GONE
        b.tvFetchStatus.visibility = View.GONE
        b.progressFetch.visibility = View.VISIBLE
        b.btnFetch.isEnabled = false

        lifecycleScope.launch {
            try {
                ensureEngine()
                val result = withContext(Dispatchers.IO) {
                    val req = YoutubeDLRequest(url)
                    req.addOption("--no-playlist")
                    req.addOption("--socket-timeout", "15")
                    YoutubeDL.getInstance().getInfo(req)
                }
                showInfo(url, result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                b.tvFetchStatus.text = "Video nahi mili: ${shortError(e)}"
                b.tvFetchStatus.visibility = View.VISIBLE
            } finally {
                b.progressFetch.visibility = View.GONE
                b.btnFetch.isEnabled = true
            }
        }
    }

    private fun showInfo(url: String, info: VideoInfo) {
        currentUrl = url

        b.tvTitle.text = info.title ?: "Untitled"
        val seconds = (info.duration ?: 0).toInt()
        val uploader = info.uploader ?: ""
        b.tvMeta.text = listOf(uploader, if (seconds > 0) formatDuration(seconds) else "")
            .filter { it.isNotBlank() }
            .joinToString("  •  ")

        // Thumbnail
        val thumb = info.thumbnail
        b.ivThumb.visibility = View.VISIBLE
        if (!thumb.isNullOrBlank()) b.ivThumb.load(thumb) else b.ivThumb.setImageDrawable(null)

        val formats = info.formats.orEmpty()

        // Preview: best stream that already has audio + video together
        val combined = formats
            .filter {
                (it.vcodec ?: "none") != "none" &&
                    (it.acodec ?: "none") != "none" &&
                    !it.url.isNullOrBlank()
            }
            .maxByOrNull { it.height ?: 0 }
        previewUrl = combined?.url ?: info.url
        previewHeaders = combined?.httpHeaders
        b.btnPlay.visibility = if (previewUrl.isNullOrBlank()) View.GONE else View.VISIBLE

        // Quality list built from the real available heights
        val heights = formats
            .filter { (it.vcodec ?: "none") != "none" && (it.height ?: 0) > 0 }
            .map { it.height ?: 0 }
            .distinct()
            .sortedDescending()

        val list = mutableListOf(
            QualityOption("Best available (original quality)", "bv*+ba/b")
        )
        for (h in heights) {
            val tag = when {
                h >= 2160 -> " 4K"
                h >= 1440 -> " 2K"
                h >= 1080 -> " Full HD"
                h >= 720 -> " HD"
                else -> ""
            }
            list += QualityOption("${h}p$tag", "bv*[height<=$h]+ba/b[height<=$h]")
        }
        list += QualityOption("Audio only (MP3)", "ba/b", audioOnly = true)
        options = list

        b.spQuality.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, list.map { it.label }
        )
        b.spQuality.setSelection(0)

        b.cardPreview.visibility = View.VISIBLE
        b.downloadSection.visibility = View.VISIBLE
        b.progressDownload.visibility = View.GONE
        b.tvStatus.visibility = View.GONE
    }

    // ------------------------------------------------------------------ preview

    private fun startPreview() {
        val url = previewUrl ?: return
        releasePlayer()

        val httpFactory = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
        previewHeaders?.let { httpFactory.setDefaultRequestProperties(it) }

        val p = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpFactory))
            .build()
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                toast("Preview play nahi ho saka, lekin download theek kaam karega")
                releasePlayer()
                b.ivThumb.visibility = View.VISIBLE
                b.btnPlay.visibility = View.VISIBLE
            }
        })
        b.playerView.player = p
        p.setMediaItem(MediaItem.fromUri(url))
        p.prepare()
        p.playWhenReady = true
        player = p

        b.ivThumb.visibility = View.GONE
        b.btnPlay.visibility = View.GONE
    }

    private fun releasePlayer() {
        b.playerView.player = null
        player?.release()
        player = null
    }

    // ------------------------------------------------------------------ download

    private fun startDownload() {
        val url = currentUrl ?: return
        val opt = options.getOrNull(b.spQuality.selectedItemPosition) ?: return
        if (downloadJob?.isActive == true) return

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }

        player?.pause()
        userCancelled = false
        val pid = UUID.randomUUID().toString()
        processId = pid
        val tmpDir = File(getExternalFilesDir(null) ?: cacheDir, "tmp/$pid").apply { mkdirs() }

        setDownloading(true)
        ContextCompat.startForegroundService(this, Intent(this, KeepAliveService::class.java))

        downloadJob = lifecycleScope.launch {
            try {
                ensureEngine()
                val savedAs = withContext(Dispatchers.IO) {
                    runDownload(url, opt, pid, tmpDir)
                }
                b.tvStatus.text = "Ho gaya! Saved: $savedAs"
                b.progressDownload.progress = 100
                DownloadNotifier.finished(this@MainActivity, "Download complete", true)
                toast("Download complete")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (userCancelled) {
                    b.tvStatus.text = "Download cancel kar diya"
                    DownloadNotifier.finished(this@MainActivity, "Download cancelled", false)
                } else {
                    b.tvStatus.text = "Download fail: ${shortError(e)}"
                    DownloadNotifier.finished(this@MainActivity, "Download failed", false)
                }
            } finally {
                tmpDir.deleteRecursively()
                stopService(Intent(this@MainActivity, KeepAliveService::class.java))
                setDownloading(false)
                processId = null
            }
        }
    }

    /** Runs on a background thread. Returns a human readable save location. */
    private fun runDownload(url: String, opt: QualityOption, pid: String, tmpDir: File): String {
        val req = YoutubeDLRequest(url)
        req.addOption("--no-playlist")
        req.addOption("-f", opt.selector)
        req.addOption("-o", "${tmpDir.absolutePath}/%(title).80B [%(id)s].%(ext)s")
        req.addOption("--windows-filenames")
        req.addOption("--no-mtime")

        // Speed: download many fragments in parallel, chunked HTTP avoids throttling
        req.addOption("-N", "8")
        req.addOption("--http-chunk-size", "10M")
        req.addOption("--retries", "10")
        req.addOption("--fragment-retries", "10")
        req.addOption("--socket-timeout", "15")

        if (opt.audioOnly) {
            req.addOption("-x")
            req.addOption("--audio-format", "mp3")
            req.addOption("--audio-quality", "0")
        } else {
            // Highest resolution first, then prefer mp4/m4a so it plays everywhere.
            req.addOption("-S", "res,ext:mp4:m4a")
            req.addOption("--merge-output-format", "mp4/mkv")
        }

        YoutubeDL.getInstance().execute(req, pid) { progress, eta, _ ->
            val pct = progress.toInt().coerceIn(0, 100)
            val text = if (eta > 0) "$pct%  •  ${formatDuration(eta.toInt())} baqi" else "$pct%"
            runOnUiThread {
                if (progress >= 0) {
                    b.progressDownload.isIndeterminate = false
                    b.progressDownload.progress = pct
                }
                b.tvStatus.text = "Downloading… $text"
            }
            DownloadNotifier.update(applicationContext, text, if (progress >= 0) pct else null)
        }

        runOnUiThread { b.tvStatus.text = "Save ho raha hai…" }

        val file = tmpDir.listFiles()
            ?.filter {
                it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") &&
                    !it.name.endsWith(".temp")
            }
            ?.maxByOrNull { it.length() }
            ?: throw IllegalStateException("Output file nahi mili")

        return saveToGallery(file)
    }

    private fun cancelDownload() {
        userCancelled = true
        processId?.let { YoutubeDL.getInstance().destroyProcessById(it) }
    }

    private fun setDownloading(active: Boolean) {
        b.btnDownload.isEnabled = !active
        b.spQuality.isEnabled = !active
        b.btnFetch.isEnabled = !active
        b.btnCancel.visibility = if (active) View.VISIBLE else View.GONE
        b.progressDownload.visibility = View.VISIBLE
        b.tvStatus.visibility = View.VISIBLE
        if (active) {
            b.progressDownload.isIndeterminate = true
            b.tvStatus.text = "Shuru ho raha hai…"
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            b.progressDownload.isIndeterminate = false
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // ------------------------------------------------------------------ saving

    private fun saveToGallery(file: File): String {
        val ext = file.extension.lowercase()
        val isAudio = ext in setOf("mp3", "m4a", "opus", "ogg", "aac", "wav")
        val mime = when (ext) {
            "mp4" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "opus", "ogg" -> "audio/ogg"
            else -> if (isAudio) "audio/*" else "video/*"
        }
        val baseDir = if (isAudio) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES
        val folder = "HD Downloader"

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val collection = if (isAudio)
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$baseDir/$folder")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(collection, values)
                ?: throw IOException("MediaStore insert fail")
            contentResolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { it.copyTo(out, 1024 * 1024) }
            } ?: throw IOException("File open nahi ho saki")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
            "$baseDir/$folder/${file.name}"
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(baseDir), folder)
            dir.mkdirs()
            val dest = File(dir, file.name)
            file.copyTo(dest, overwrite = true)
            MediaScannerConnection.scanFile(
                this, arrayOf(dest.absolutePath), arrayOf(mime), null
            )
            dest.absolutePath
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun extractUrl(text: String): String? {
        val m = Regex("""https?://\S+""").find(text) ?: return null
        return m.value.trimEnd('.', ',', ')', ']', '"', '\'')
    }

    private fun formatDuration(total: Int): String {
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    private fun shortError(e: Throwable): String {
        val msg = e.message?.lineSequence()?.lastOrNull { it.isNotBlank() } ?: e.javaClass.simpleName
        return if (msg.length > 160) msg.take(160) + "…" else msg
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private val engineMutex = Mutex()

        @Volatile
        private var engineReady = false
    }
}
