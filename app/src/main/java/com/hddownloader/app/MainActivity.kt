package com.hddownloader.app

import android.Manifest
import android.content.ClipData
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
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding

    private var player: ExoPlayer? = null
    private var currentUrl: String? = null
    private var previewUrl: String? = null
    private var previewHeaders: Map<String, String>? = null

    private var currentTitle: String = ""
    private var currentDescription: String = ""

    private var options: List<QualityOption> = emptyList()
    private var downloadJob: Job? = null
    private var processId: String? = null
    private var userCancelled = false

    private var fetchSeq = 0

    private data class QualityOption(
        val label: String,
        val selector: String,
        val audioOnly: Boolean = false,
        val height: Int = 0
    )

    // ------------------------------------------------------------------ queue

    private data class QueueItem(val url: String, var status: String = "⏳ wait kar raha hai")

    private val queueItems = mutableListOf<QueueItem>()
    private var queueJob: Job? = null
    private var queueCancelled = false

    // ------------------------------------------------------------------ prefs

    private val prefs by lazy { getSharedPreferences("hdd_settings", Context.MODE_PRIVATE) }

    private var defaultHeight: Int
        get() = prefs.getInt("default_height", 720)
        set(v) = prefs.edit().putInt("default_height", v).apply()

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
        b.btnCopyDescription.setOnClickListener {
            copyText("Description", currentDescription, "Description copy ho gayi ✅")
        }
        b.btnAddToQueue.setOnClickListener { addToQueue() }
        b.btnStartQueue.setOnClickListener { startQueue() }
        b.btnClearQueue.setOnClickListener { clearQueue() }
        b.btnCancelQueue.setOnClickListener { cancelQueue() }
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
        // Sirf link box me bharta hai — fetch khud nahi chalta (user ka button dabana zaroori hai)
        if (intent?.action == Intent.ACTION_SEND) {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            val url = extractUrl(text)
            if (url != null) {
                b.etUrl.setText(url)
                toast("Link aa gaya hai — Get Video dabayein")
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
            // Sirf link bharta hai — fetch user khud Get Video dabakar karega
            b.etUrl.setText(extractUrl(text) ?: text.trim())
            toast("Clipboard se link aa gaya 📋")
        }
    }

    private fun isYoutube(url: String) =
        url.contains("youtube.com") || url.contains("youtu.be")

    /** Halki si request: YouTube se title + thumbnail foran (full info se pehle). */
    private suspend fun quickOEmbed(url: String): Pair<String, String>? =
        withContext(Dispatchers.IO) {
            if (!isYoutube(url)) return@withContext null
            try {
                val api = "https://www.youtube.com/oembed?format=json&url=" +
                    URLEncoder.encode(url, "UTF-8")
                val conn = URL(api).openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                if (conn.responseCode != 200) return@withContext null
                val json = JSONObject(conn.inputStream.bufferedReader().readText())
                val title = json.optString("title")
                val thumb = json.optString("thumbnail_url")
                if (title.isBlank()) null else title to thumb
            } catch (_: Exception) {
                null
            }
        }

    /**
     * Halka (flat) yt-dlp pass — formats nikale baghair sirf title/description/thumbnail.
     * Full info se pehle ye cheezein screen par aa jati hain, fetch tez mehsoos hota hai.
     */
    private suspend fun quickFlatInfo(url: String): Triple<String, String, String>? =
        withContext(Dispatchers.IO) {
            try {
                ensureEngine()
                val req = YoutubeDLRequest(url)
                req.addOption("--dump-single-json")
                req.addOption("--flat-playlist")
                req.addOption("--no-playlist")
                req.addOption("--no-warnings")
                req.addOption("--socket-timeout", "15")
                if (isYoutube(url)) {
                    req.addOption("--extractor-args", "youtube:player_client=android")
                }
                val resp = YoutubeDL.getInstance()
                    .execute(req, UUID.randomUUID().toString()) { _, _, _ -> }
                val out = resp.out?.trim().orEmpty()
                if (out.isBlank()) return@withContext null
                val root = JSONObject(out)
                val obj = if (root.has("entries")) {
                    root.optJSONArray("entries")?.optJSONObject(0)
                        ?: return@withContext null
                } else root
                val title = obj.optString("title")
                val desc = obj.optString("description").trim()
                var thumb = obj.optString("thumbnail")
                if (thumb.isBlank()) {
                    thumb = obj.optJSONArray("thumbnails")?.optJSONObject(0)
                        ?.optString("url").orEmpty()
                }
                if (title.isBlank() && desc.isBlank()) null else Triple(title, desc, thumb)
            } catch (_: Exception) {
                null
            }
        }

    private suspend fun fetchVideoInfo(url: String): VideoInfo {
        ensureEngine()
        return withContext(Dispatchers.IO) {
            val req = YoutubeDLRequest(url)
            req.addOption("--no-playlist")
            req.addOption("--socket-timeout", "15")
            if (isYoutube(url)) {
                // Android client: tez aur bot-check se bachata hai
                req.addOption("--extractor-args", "youtube:player_client=android")
            }
            YoutubeDL.getInstance().getInfo(req)
        }
    }

    private fun fetchInfo() {
        val url = extractUrl(b.etUrl.text?.toString().orEmpty())
        if (url == null) {
            toast("Sahi video link paste karein")
            return
        }
        if (downloadJob?.isActive == true || queueJob?.isActive == true) {
            toast("Pehle current download khatam ya cancel karein")
            return
        }

        releasePlayer()
        b.cardPreview.visibility = View.GONE
        b.cardDescription.visibility = View.GONE
        b.downloadSection.visibility = View.GONE
        b.tvFetchStatus.visibility = View.GONE
        b.progressFetch.visibility = View.VISIBLE
        b.btnFetch.isEnabled = false

        val seq = ++fetchSeq
        lifecycleScope.launch {
            try {
                // Quick title pehle dikhao (YouTube oEmbed), full info peeche aati rahegi
                val quick = async { quickOEmbed(url) }
                launch {
                    val q = quick.await()
                    if (q != null && seq == fetchSeq) showEarly(q.first, "", q.second)
                }
                // Halka (flat) pass: title + description foran — Facebook par bhi kaam karta hai
                val flat = async { quickFlatInfo(url) }
                launch {
                    val f = flat.await()
                    if (f != null && seq == fetchSeq) showEarly(f.first, f.second, f.third)
                }
                val result = fetchVideoInfo(url)
                if (seq == fetchSeq) showInfo(url, result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (seq == fetchSeq) {
                    b.tvFetchStatus.text = "Video nahi mili: ${shortError(e)}"
                    b.tvFetchStatus.visibility = View.VISIBLE
                }
            } finally {
                if (seq == fetchSeq) {
                    b.progressFetch.visibility = View.GONE
                    b.btnFetch.isEnabled = true
                }
            }
        }
    }

    /** Full info se pehle title/description/thumbnail dikhata hai (fast feel ke liye). */
    private fun showEarly(title: String, description: String, thumb: String) {
        if (title.isNotBlank()) {
            currentTitle = title
            b.tvTitle.text = title
            b.tvMeta.text = "Details aa rahi hain…"
            b.cardPreview.visibility = View.VISIBLE
        }
        if (thumb.isNotBlank()) {
            b.ivThumb.visibility = View.VISIBLE
            b.ivThumb.load(thumb)
            b.cardPreview.visibility = View.VISIBLE
        }
        b.btnPlay.visibility = View.GONE
        if (description.isNotBlank()) {
            currentDescription = description
            b.tvDescription.text = description
            b.cardDescription.visibility = View.VISIBLE
        }
    }

    private fun showInfo(url: String, info: VideoInfo) {
        currentUrl = url
        currentTitle = info.title ?: "Untitled"
        currentDescription = info.description?.trim().orEmpty()

        b.tvTitle.text = currentTitle
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
            QualityOption("Best available (original quality)", "bv*+ba/b", height = 0)
        )
        for (h in heights) {
            val tag = when {
                h >= 2160 -> " 4K"
                h >= 1440 -> " 2K"
                h >= 1080 -> " Full HD"
                h >= 720 -> " HD"
                else -> ""
            }
            list += QualityOption("${h}p$tag", "bv*[height<=$h]+ba/b[height<=$h]", height = h)
        }
        list += QualityOption("Audio only (MP3)", "ba/b", audioOnly = true, height = -1)
        options = list

        b.spQuality.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, list.map { it.label }
        )
        // Default quality: saved pasandeeda height (default 720p), warna Best
        val defIdx = list.indexOfFirst { it.height == defaultHeight }
        b.spQuality.setSelection(if (defIdx >= 0) defIdx else 0)

        // Description ka alag section + copy button (hashtags description ke andar hi hote hain)
        if (currentDescription.isNotBlank()) {
            b.tvDescription.text = currentDescription
            b.cardDescription.visibility = View.VISIBLE
        } else {
            b.cardDescription.visibility = View.GONE
        }

        b.cardPreview.visibility = View.VISIBLE
        b.downloadSection.visibility = View.VISIBLE
        b.progressRow.visibility = View.GONE
        b.tvStatus.visibility = View.GONE
    }

    private fun copyText(label: String, text: String, doneMsg: String) {
        if (text.isBlank()) {
            toast("Copy karne ke liye kuch nahi hai")
            return
        }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        toast(doneMsg)
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
        if (downloadJob?.isActive == true || queueJob?.isActive == true) return

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }

        // Jo quality chuni, use default bana lo (agli dafa wahi pehle ayegi)
        if (opt.height > 0) defaultHeight = opt.height

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
                    runDownload(url, opt, pid, tmpDir) { pct, text ->
                        runOnUiThread {
                            if (pct >= 0) {
                                b.progressDownload.isIndeterminate = false
                                b.progressDownload.progress = pct
                                b.tvProgressPct.text = "$pct%"
                            }
                            b.tvStatus.text = "Downloading… $text"
                        }
                    }
                }
                b.tvStatus.text = "Ho gaya! Saved: $savedAs"
                b.progressDownload.progress = 100
                b.tvProgressPct.text = "100%"
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
    private fun runDownload(
        url: String,
        opt: QualityOption,
        pid: String,
        tmpDir: File,
        uiProgress: (Int, String) -> Unit
    ): String {
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

        if (isYoutube(url)) {
            req.addOption("--extractor-args", "youtube:player_client=android")
        }

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
            if (progress >= 0) uiProgress(pct, text) else uiProgress(-1, text)
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
        b.progressRow.visibility = View.VISIBLE
        b.tvStatus.visibility = View.VISIBLE
        if (active) {
            b.progressDownload.isIndeterminate = true
            b.progressDownload.progress = 0
            b.tvProgressPct.text = ""
            b.tvStatus.text = "Shuru ho raha hai…"
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            b.progressDownload.isIndeterminate = false
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // ------------------------------------------------------------------ queue

    private fun addToQueue() {
        val url = extractUrl(b.etUrl.text?.toString().orEmpty())
        if (url == null) {
            toast("Pehle sahi video link paste karein")
            return
        }
        if (queueItems.any { it.url == url }) {
            toast("Ye link pehle se queue me hai")
            return
        }
        queueItems += QueueItem(url)
        b.etUrl.text?.clear()
        renderQueue()
        toast("Queue me add ho gaya (${queueItems.size})")
    }

    private fun clearQueue() {
        if (queueJob?.isActive == true) {
            toast("Queue chal rahi hai — pehle cancel karein")
            return
        }
        queueItems.clear()
        renderQueue()
    }

    private fun cancelQueue() {
        queueCancelled = true
        processId?.let { YoutubeDL.getInstance().destroyProcessById(it) }
    }

    private fun renderQueue() {
        if (queueItems.isEmpty()) {
            b.cardQueue.visibility = View.GONE
            return
        }
        b.cardQueue.visibility = View.VISIBLE
        b.tvQueueItems.text = queueItems.mapIndexed { i, item ->
            "${i + 1}. ${item.url.take(55)}${if (item.url.length > 55) "…" else ""}\n    ${item.status}"
        }.joinToString("\n")
    }

    private fun startQueue() {
        if (queueJob?.isActive == true || downloadJob?.isActive == true) return
        val pending = queueItems.filter { it.status.startsWith("⏳") }
        if (pending.isEmpty()) {
            toast("Queue khali hai — pehle links add karein")
            return
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }

        queueCancelled = false
        b.btnStartQueue.isEnabled = false
        b.btnCancelQueue.visibility = View.VISIBLE
        b.progressQueue.visibility = View.VISIBLE
        b.tvQueueStatus.visibility = View.VISIBLE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        ContextCompat.startForegroundService(this, Intent(this, KeepAliveService::class.java))

        queueJob = lifecycleScope.launch {
            try {
                ensureEngine()
                val h = defaultHeight
                val selector =
                    if (h > 0) "bv*[height<=$h]+ba/b[height<=$h]" else "bv*+ba/b"
                val opt = QualityOption("Queue (${if (h > 0) "${h}p" else "Best"})", selector, height = h)

                for (item in queueItems) {
                    if (queueCancelled) break
                    if (!item.status.startsWith("⏳")) continue
                    item.status = "⬇️ Download ho raha hai…"
                    renderQueue()
                    val pid = UUID.randomUUID().toString()
                    processId = pid
                    val tmpDir =
                        File(getExternalFilesDir(null) ?: cacheDir, "tmp/$pid").apply { mkdirs() }
                    try {
                        val info = fetchVideoInfo(item.url)
                        val title = info.title ?: "Video"
                        withContext(Dispatchers.IO) {
                            runDownload(item.url, opt, pid, tmpDir) { pct, text ->
                                runOnUiThread {
                                    if (pct >= 0) {
                                        b.progressQueue.isIndeterminate = false
                                        b.progressQueue.progress = pct
                                    }
                                    b.tvQueueStatus.text = "$title — $text"
                                }
                            }
                        }
                        item.status = "✅ Ho gaya"
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        item.status =
                            if (queueCancelled) "❌ Cancel" else "❌ Fail: ${shortError(e).take(60)}"
                    } finally {
                        tmpDir.deleteRecursively()
                        processId = null
                    }
                    renderQueue()
                }
                DownloadNotifier.finished(this@MainActivity, "Queue khatam ho gayi", true)
                toast("Queue khatam ho gayi")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast("Queue fail: ${shortError(e)}")
            } finally {
                stopService(Intent(this@MainActivity, KeepAliveService::class.java))
                b.btnStartQueue.isEnabled = true
                b.btnCancelQueue.visibility = View.GONE
                b.progressQueue.visibility = View.GONE
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                renderQueue()
            }
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
