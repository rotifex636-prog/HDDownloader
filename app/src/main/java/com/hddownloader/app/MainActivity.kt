package com.hddownloader.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.hddownloader.app.databinding.ActivityMainBinding
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.mapper.VideoInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Expert Solutions Downloader — ek hi screen, ek hi gola (hole).
 * Link copy karo, gola dabao: processing 1-100%, phir download khud start.
 * Quality backend par auto (video ki apni best quality). Baqi options nahi.
 */
class MainActivity : AppCompatActivity(), HoleView.Listener {

    private lateinit var b: ActivityMainBinding

    private var flow: HoleView.State = HoleView.State.IDLE
    private var currentUrl: String? = null
    private var currentTitle: String = ""
    private var currentDescription: String = ""

    private var fetchJob: Job? = null
    private var tickerJob: Job? = null
    private var downloadJob: Job? = null
    private var processId: String? = null
    private var userCancelled = false
    private var lastPct = 0
    private var pendingPrepared: Prepared? = null

    private data class QualityOption(
        val label: String,
        val selector: String,
        val audioOnly: Boolean = false,
        val height: Int = 0,
        val directUrl: String? = null,
        val mergeAudioUrl: String? = null
    )

    private data class Prepared(
        val opt: QualityOption,
        val totalBytes: Long?,
        val title: String,
        val description: String,
        val durationSec: Int
    )

    private val prefs by lazy { getSharedPreferences("hdd_settings", Context.MODE_PRIVATE) }

    /** Direct downloads (FB fast / NewPipe) ke liye — yt-dlp ki zaroorat nahi. */
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    private val activeCalls = java.util.Collections.synchronizedSet(mutableSetOf<Call>())

    private var lastErrorDetail: String = ""
    private var lastNpError: String = ""

    private fun cancelActiveCalls() {
        synchronized(activeCalls) {
            activeCalls.forEach { try { it.cancel() } catch (_: Exception) { } }
            activeCalls.clear()
        }
    }

    private val storagePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val p = pendingPrepared
            pendingPrepared = null
            if (granted && p != null) {
                startDownload(p)
            } else {
                toast("Storage permission is needed to save the video")
                setFlow(HoleView.State.IDLE, defaultHint())
                stopKeepAlive()
            }
        }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* optional */ }

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.holeView.listener = this
        b.holeView.hintText = defaultHint()
        b.btnCopyDescription.setOnClickListener {
            copyText("Description", currentDescription, "Description copied ✓")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        askBatteryExemptionOnce()

        // Engine ko background me garam rakho + roz auto-update (bina kisi button ke)
        lifecycleScope.launch {
            try {
                ensureEngine()
                autoUpdateEngineIfDue()
            } catch (_: Exception) { }
        }

        handleShareIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        if (flow == HoleView.State.IDLE) b.holeView.hintText = defaultHint()
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND) {
            val url = extractUrl(intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty())
            if (url != null) {
                if (flow == HoleView.State.IDLE || flow == HoleView.State.DONE ||
                    flow == HoleView.State.ERROR
                ) {
                    beginProcessing(url)
                } else {
                    toast("Busy — finish the current download first")
                }
            }
        }
    }

    /** Pehli dafa battery optimization se chutkara mangta hai taake background
     *  download phone ki battery setting ki wajah se band na ho. */
    private fun askBatteryExemptionOnce() {
        if (prefs.getBoolean("battery_asked", false)) return
        prefs.edit().putBoolean("battery_asked", true).apply()
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")
                    )
                )
            }
        } catch (_: Exception) { }
    }

    // ------------------------------------------------------------------ hole taps

    override fun onHoleTap() {
        when (flow) {
            HoleView.State.IDLE, HoleView.State.DONE, HoleView.State.ERROR -> {
                val url = clipboardUrl()
                if (url != null) beginProcessing(url) else showLinkDialog()
            }
            HoleView.State.PROCESSING, HoleView.State.DOWNLOADING -> cancelCurrent()
        }
    }

    // ------------------------------------------------------------------ flow

    private fun setFlow(state: HoleView.State, hint: String? = null) {
        flow = state
        b.holeView.state = state
        if (hint != null) b.holeView.hintText = hint
    }

    private fun defaultHint(): String =
        if (clipboardUrl() != null) "Link ready — tap the hole!"
        else "Copy a video link, then tap"

    private fun clipboardText(): String {
        return try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        } catch (_: Exception) {
            ""
        }
    }

    private fun clipboardUrl(): String? = extractUrl(clipboardText())

    private fun showLinkDialog() {
        val et = EditText(this).apply {
            hint = "Paste video link"
            setSingleLine(true)
            val clip = clipboardText()
            if (clip.isNotBlank()) setText(clip)
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, pad / 4)
        }
        AlertDialog.Builder(this)
            .setTitle("Paste a video link")
            .setView(et)
            .setPositiveButton("Start") { _, _ ->
                val url = extractUrl(et.text?.toString().orEmpty())
                if (url != null) beginProcessing(url)
                else toast("Please paste a valid video link")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun beginProcessing(url: String) {
        if (flow == HoleView.State.PROCESSING || flow == HoleView.State.DOWNLOADING) return
        currentUrl = url
        currentTitle = ""
        currentDescription = ""
        userCancelled = false
        b.cardDescription.visibility = View.GONE
        setFlow(HoleView.State.PROCESSING, "Processing…")
        b.holeView.percent = 0
        startKeepAlive()

        // Ring 1 se 90 tak khud barhta hai jab tak asal info tayyar hoti hai
        tickerJob = lifecycleScope.launch {
            while (true) {
                if (flow == HoleView.State.PROCESSING && b.holeView.percent < 90) {
                    b.holeView.percent += (1..3).random()
                }
                delay(130)
            }
        }

        fetchJob = lifecycleScope.launch {
            try {
                val p = prepare(url)
                if (flow != HoleView.State.PROCESSING) return@launch
                currentTitle = p.title
                currentDescription = p.description
                tickerJob?.cancel()
                b.holeView.percent = 100
                b.holeView.hintText = p.title.take(56)
                delay(450)
                if (flow != HoleView.State.PROCESSING) return@launch
                startDownload(p)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastErrorDetail = buildErrorDetail(e)
                if (flow == HoleView.State.PROCESSING) showError(shortError(e))
            }
        }
    }

    /** Video ki info + best quality tayyar karta hai (background par chalta hai). */
    private suspend fun prepare(url: String): Prepared {
        if (isFacebook(url)) {
            val fast = fetchFacebookFast(url)
            val direct = fast?.hdUrl ?: fast?.sdUrl
            if (fast != null && direct != null) {
                return Prepared(
                    QualityOption("Auto", "", directUrl = direct),
                    null,
                    fast.title.ifBlank { "Facebook Video" },
                    fast.description.trim(),
                    fast.durationSec
                )
            }
        }
        if (isYoutube(url)) {
            // Option A hybrid: pehle NewPipe (tez), fail ho to neeche yt-dlp
            val np = tryNewPipe(url)
            if (np != null) return np
        }
        val info = fetchVideoInfo(url)
        val opt = QualityOption("Auto", "bv*+ba/b")
        return Prepared(
            opt,
            computeTotalBytes(info, opt),
            info.title ?: "Video",
            info.description?.trim().orEmpty(),
            (info.duration ?: 0).toInt()
        )
    }

    /** NewPipe se YouTube ka tez tareeqa — streams direct milte hain. */
    private suspend fun tryNewPipe(url: String): Prepared? = withContext(Dispatchers.IO) {
        try {
            val r = NpEngine.extract(url)
            val vo = r.videoOnly
            val au = r.audio
            val title = r.title.ifBlank { "Video" }
            val desc = r.description.trim()
            when {
                vo != null && au != null && vo.height >= 720 -> Prepared(
                    QualityOption("Auto", "", directUrl = vo.url, mergeAudioUrl = au.url),
                    null, title, desc, r.durationSec
                )
                r.progressive != null -> Prepared(
                    QualityOption("Auto", "", directUrl = r.progressive.url),
                    null, title, desc, r.durationSec
                )
                vo != null && au != null -> Prepared(
                    QualityOption("Auto", "", directUrl = vo.url, mergeAudioUrl = au.url),
                    null, title, desc, r.durationSec
                )
                else -> null
            }
        } catch (e: Exception) {
            lastNpError = (e.message ?: e.javaClass.simpleName).take(300)
            null
        }
    }

    private fun startDownload(p: Prepared) {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            pendingPrepared = p
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        val url = currentUrl ?: return
        lastPct = 0
        setFlow(HoleView.State.DOWNLOADING, "Starting download…")
        b.holeView.percent = 0
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Description + Copy download ke doran hi — khatam hone ka intezar nahi
        showCard(p, null, false)

        val pid = UUID.randomUUID().toString()
        processId = pid
        val tmpDir = File(getExternalFilesDir(null) ?: cacheDir, "tmp/$pid").apply { mkdirs() }

        downloadJob = lifecycleScope.launch {
            try {
                if (p.opt.directUrl == null) ensureEngine()
                val result = withContext(Dispatchers.IO) {
                    runDownload(url, p.opt, pid, tmpDir, p.totalBytes) { pct, text ->
                        runOnUiThread {
                            if (flow == HoleView.State.DOWNLOADING) {
                                if (pct >= 0 && pct > lastPct) {
                                    lastPct = pct
                                    b.holeView.percent = pct
                                }
                                if (text.isNotBlank()) b.holeView.hintText = text
                            }
                        }
                    }
                }
                b.holeView.percent = 100
                setFlow(HoleView.State.DONE, "✓ Saved to Gallery › HDDownloader")
                showCard(p, result.second, true)
                DownloadNotifier.finished(this@MainActivity, "Download complete", true)
                toast("Download complete")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!userCancelled) {
                    lastErrorDetail = buildErrorDetail(e)
                    DownloadNotifier.finished(this@MainActivity, "Download failed", false)
                    showError(shortError(e))
                }
            } finally {
                tmpDir.deleteRecursively()
                processId = null
                stopKeepAlive()
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    /** Description card — download ke doran (isFinal=false) bhi, aur DONE par bhi. */
    private fun showCard(p: Prepared, fileBytes: Long?, isFinal: Boolean) {
        val sizeBytes = when {
            isFinal && fileBytes != null && fileBytes > 0 -> fileBytes
            !isFinal && p.totalBytes != null && p.totalBytes > 0 -> p.totalBytes
            else -> 0L
        }
        val meta = listOf(
            if (sizeBytes > 0) fmtBytes(sizeBytes) else "",
            if (p.durationSec > 0) formatDuration(p.durationSec) else ""
        ).filter { it.isNotBlank() }.joinToString("  •  ")

        b.tvDoneMeta.text = meta
        b.tvDoneMeta.visibility = if (meta.isBlank()) View.GONE else View.VISIBLE

        val hasDesc = currentDescription.isNotBlank()
        b.tvDescription.text = currentDescription
        b.tvDescription.visibility = if (hasDesc) View.VISIBLE else View.GONE
        b.btnCopyDescription.visibility = if (hasDesc) View.VISIBLE else View.GONE

        b.cardDescription.visibility =
            if (meta.isNotBlank() || hasDesc) View.VISIBLE else View.GONE
    }

    private fun cancelCurrent() {
        userCancelled = true
        val wasDownloading = flow == HoleView.State.DOWNLOADING
        fetchJob?.cancel()
        tickerJob?.cancel()
        downloadJob?.cancel()
        cancelActiveCalls()
        processId?.let {
            try {
                YoutubeDL.getInstance().destroyProcessById(it)
            } catch (_: Exception) { }
        }
        processId = null
        stopKeepAlive()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setFlow(HoleView.State.IDLE, defaultHint())
        if (wasDownloading) {
            DownloadNotifier.finished(this, "Download cancelled", false)
        }
        toast("Cancelled")
    }

    private fun showError(msg: String) {
        tickerJob?.cancel()
        stopKeepAlive()
        setFlow(HoleView.State.ERROR, "Failed: $msg")
        toast("Failed: $msg")
        lifecycleScope.launch {
            delay(2400)
            if (flow == HoleView.State.ERROR) setFlow(HoleView.State.IDLE, defaultHint())
        }
        showErrorDialog(msg)
    }

    private fun buildErrorDetail(e: Throwable): String {
        val sb = StringBuilder()
        if (lastNpError.isNotBlank()) {
            sb.append("Fast engine (NewPipe): ").append(lastNpError).append("\n\n")
        }
        sb.append(e.javaClass.simpleName).append(": ").append(e.message ?: "")
        return sb.toString().take(4000)
    }

    /** Fault aye to poora error samne popup me — Copy karke bhejne ke liye. */
    private fun showErrorDialog(shortMsg: String) {
        if (isFinishing || lastErrorDetail.isBlank()) return
        try {
            AlertDialog.Builder(this)
                .setTitle("Download Failed")
                .setMessage("$shortMsg\n\n—— Full error ——\n$lastErrorDetail")
                .setPositiveButton("Copy Error") { _, _ ->
                    copyText("App error", lastErrorDetail, "Error copied ✓ — ab mujhe bhej do")
                }
                .setNegativeButton("OK", null)
                .show()
        } catch (_: Exception) { }
    }

    private fun startKeepAlive() {
        try {
            ContextCompat.startForegroundService(this, Intent(this, KeepAliveService::class.java))
        } catch (_: Exception) { }
    }

    private fun stopKeepAlive() {
        try {
            stopService(Intent(this, KeepAliveService::class.java))
        } catch (_: Exception) { }
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

    /** Engine (yt-dlp) ko takreeban roz ek dafa background me khud update karta hai. */
    private suspend fun autoUpdateEngineIfDue() {
        val last = prefs.getLong("engine_auto_update_at", 0L)
        if (System.currentTimeMillis() - last < 24 * 60 * 60 * 1000L) return
        try {
            withContext(Dispatchers.IO) {
                YoutubeDL.getInstance().updateYoutubeDL(applicationContext)
            }
            prefs.edit()
                .putLong("engine_auto_update_at", System.currentTimeMillis())
                .apply()
        } catch (_: Exception) { }
    }

    private suspend fun fetchVideoInfo(url: String): VideoInfo {
        ensureEngine()
        return withContext(Dispatchers.IO) {
            val req = YoutubeDLRequest(url)
            req.addOption("--no-playlist")
            req.addOption("--socket-timeout", "15")
            if (isYoutube(url)) {
                req.addOption("--extractor-args", "youtube:player_client=android")
            }
            YoutubeDL.getInstance().getInfo(req)
        }
    }

    private fun isYoutube(url: String) =
        url.contains("youtube.com") || url.contains("youtu.be")

    private fun isFacebook(url: String) =
        url.contains("facebook.com") || url.contains("fb.watch")

    // ------------------------------------------------------------------ facebook fast fetch

    private data class FbFastInfo(
        val title: String,
        val description: String,
        val thumb: String,
        val hdUrl: String?,
        val sdUrl: String?,
        val durationSec: Int
    )

    /**
     * Facebook page se direct HD/SD links ek hi request me — koi bahari server
     * nahi, sab kuch phone par. Fail ho to yt-dlp fallback khud chalta hai.
     */
    private suspend fun fetchFacebookFast(url: String): FbFastInfo? =
        withContext(Dispatchers.IO) {
            val attempts = mutableListOf(url)
            val id = Regex("""(?:[?&]v=|/videos/|/reel/|/watch/)(\d{5,})""").find(url)
                ?.groupValues?.get(1)
            if (id != null) attempts += "https://www.facebook.com/watch/?v=$id"
            try {
                attempts += "https://www.facebook.com/plugins/video.php?href=" +
                    java.net.URLEncoder.encode(url, "UTF-8") + "&show_text=0&width=560"
            } catch (_: Exception) { }
            for (a in attempts.distinct()) {
                val info = fetchFbAttempt(a)
                if (info != null) return@withContext info
            }
            null
        }

    /** Ek FB page ko gehra scan karke direct links nikalne ki koshish. */
    private fun fetchFbAttempt(pageUrl: String): FbFastInfo? {
        return try {
            val conn = URL(pageUrl).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 9000
            conn.readTimeout = 12000
            conn.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
            )
            conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            if (conn.responseCode !in 200..299) return null
            val html = conn.inputStream.bufferedReader().use { reader ->
                val sb = StringBuilder()
                val buf = CharArray(16384)
                var total = 0
                while (true) {
                    val n = reader.read(buf)
                    if (n <= 0) break
                    sb.append(buf, 0, n)
                    total += n
                    if (total > 20_000_000) break
                    if (total > 600_000 && (sb.contains("\"hd_src\"") ||
                        sb.contains("browser_native_hd_url") ||
                        sb.contains("\"playable_url\"") || sb.contains("\"sd_src\""))
                    ) break
                }
                sb.toString()
            }
            parseFbHtml(html)
        } catch (_: Exception) {
            null
        }
    }

    private fun parseFbHtml(html: String): FbFastInfo? {
        fun findUrl(vararg keys: String): String? {
            for (k in keys) {
                val m = Regex(""""$k":"([^"]+)"""").find(html) ?: continue
                val u = unescapeFb(m.groupValues[1])
                if (u.startsWith("http")) return u
            }
            return null
        }
        val hd = findUrl("playable_url_quality_hd", "browser_native_hd_url", "hd_src")
        val sd = findUrl("playable_url", "browser_native_sd_url", "sd_src")
        if (hd == null && sd == null) return null
        fun meta(prop: String): String {
            val m = Regex("""<meta[^>]+property="$prop"[^>]+content="([^"]*)"""").find(html)
                ?: Regex("""<meta[^>]+content="([^"]*)"[^>]+property="$prop"""").find(html)
            val raw = m?.groupValues?.get(1) ?: return ""
            return htmlUnescape(raw)
        }
        val durMs = Regex(""""playable_duration_in_ms":(\d+)""").find(html)
            ?.groupValues?.get(1)?.toLongOrNull()
            ?: Regex(""""duration_in_millis":(\d+)""").find(html)
                ?.groupValues?.get(1)?.toLongOrNull()
        return FbFastInfo(
            title = meta("og:title"),
            description = meta("og:description"),
            thumb = meta("og:image"),
            hdUrl = hd,
            sdUrl = sd,
            durationSec = ((durMs ?: 0L) / 1000).toInt()
        )
    }

    private fun unescapeFb(s: String): String {
        var out = s.replace("\\/", "/")
        val sb = StringBuilder()
        var i = 0
        while (i < out.length) {
            if (out[i] == '\\' && out.getOrNull(i + 1) == 'u' && i + 6 <= out.length) {
                val code = out.substring(i + 2, i + 6).toIntOrNull(16)
                if (code != null) {
                    sb.append(code.toChar())
                    i += 6
                    continue
                }
            }
            sb.append(out[i])
            i++
        }
        out = sb.toString()
        return out.replace("\\&", "&")
    }

    private fun htmlUnescape(s: String): String = s
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")

    // ------------------------------------------------------------------ download

    /** Background thread par chalta hai. Save path + file size (bytes) wapas karta hai. */
    private fun runDownload(
        url: String,
        opt: QualityOption,
        pid: String,
        tmpDir: File,
        totalBytes: Long?,
        uiProgress: (Int, String) -> Unit
    ): Pair<String, Long> {
        val direct = opt.directUrl
        if (direct != null) {
            // Direct file (Facebook fast / NewPipe): OkHttp se seedha download —
            // Python engine ki zaroorat nahi, tez aur halka
            return downloadDirect(url, opt, tmpDir, uiProgress)
        }
        val req = YoutubeDLRequest(url)
        req.addOption("--no-playlist")
        if (direct == null) req.addOption("-f", opt.selector)
        req.addOption("-o", "${tmpDir.absolutePath}/%(title).80B [%(id)s].%(ext)s")
        req.addOption("--windows-filenames")
        req.addOption("--no-mtime")

        // Speed: parallel fragments + bare chunks (tez download, koi slow-down nahi)
        req.addOption("-N", "8")
        req.addOption("--http-chunk-size", "10M")
        req.addOption("--retries", "10")
        req.addOption("--fragment-retries", "10")
        req.addOption("--socket-timeout", "15")

        if (isYoutube(url)) {
            req.addOption("--extractor-args", "youtube:player_client=android")
        }

        if (direct != null) {
            // Direct file link (Facebook fast mode): merge ki zaroorat nahi
        } else if (opt.audioOnly) {
            req.addOption("-x")
            req.addOption("--audio-format", "mp3")
            req.addOption("--audio-quality", "0")
        } else {
            req.addOption("-S", "res,ext:mp4:m4a")
            req.addOption("--merge-output-format", "mp4/mkv")
        }

        YoutubeDL.getInstance().execute(req, pid) { progress, eta, line ->
            val pct = progress.toInt().coerceIn(0, 100)
            val text = buildProgressText(pct, eta.toInt(), line, totalBytes)
            if (progress >= 0) uiProgress(pct, text) else uiProgress(-1, text)
            DownloadNotifier.update(applicationContext, text, if (progress >= 0) pct else null)
        }

        uiProgress(-1, "Saving to gallery…")

        val file = tmpDir.listFiles()
            ?.filter {
                it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") &&
                    !it.name.endsWith(".temp")
            }
            ?.maxByOrNull { it.length() }
            ?: throw IllegalStateException("Downloaded file not found")

        val size = file.length()
        return saveToGallery(file) to size
    }

    /**
     * Direct stream download (OkHttp) — video [+ audio] file utaarta hai,
     * zaroorat ho to MediaMuxer se merge karke gallery me save karta hai.
     * Koi re-encode nahi: tez, battery halki.
     */
    private fun downloadDirect(
        pageUrl: String,
        opt: QualityOption,
        tmpDir: File,
        uiProgress: (Int, String) -> Unit
    ): Pair<String, Long> {
        val videoUrl = opt.directUrl ?: throw IOException("No direct link")
        val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        val referer = when {
            isFacebook(pageUrl) -> "https://www.facebook.com/"
            isYoutube(pageUrl) -> "https://www.youtube.com/"
            else -> pageUrl
        }
        val safeTitle = currentTitle.ifBlank { "Video" }
            .replace(Regex("""[\\/:*?"<>|]"""), "_").take(70).trim().ifBlank { "Video" }

        val videoFile = File(tmpDir, "video.mp4")
        val audioFile = File(tmpDir, "audio.m4a")

        val vTotal = headSize(videoUrl, ua, referer)
        val aTotal = if (opt.mergeAudioUrl != null) headSize(opt.mergeAudioUrl, ua, referer) else 0L
        val total = vTotal + aTotal
        var doneBase = 0L

        fun progress(doneInFile: Long) {
            val d = doneBase + doneInFile
            if (total > 0) {
                val pct = (d * 100 / total).toInt().coerceIn(0, 99)
                val txt = "${fmtBytes(d)} of ${fmtBytes(total)}"
                uiProgress(pct, txt)
                DownloadNotifier.update(applicationContext, txt, pct)
            } else {
                val txt = "${fmtBytes(d)} downloaded"
                uiProgress(-1, txt)
                DownloadNotifier.update(applicationContext, txt, null)
            }
        }

        downloadFile(videoUrl, ua, referer, videoFile, vTotal) { progress(it) }
        doneBase += if (vTotal > 0) vTotal else videoFile.length()

        var outFile = videoFile
        if (opt.mergeAudioUrl != null) {
            downloadFile(opt.mergeAudioUrl, ua, referer, audioFile, aTotal) { progress(it) }
            uiProgress(-1, "Merging…")
            DownloadNotifier.update(applicationContext, "Merging…", null)
            val merged = File(tmpDir, "$safeTitle.mp4")
            Muxer.merge(videoFile, audioFile, merged)
            outFile = merged
        } else {
            val named = File(tmpDir, "$safeTitle.mp4")
            if (named.absolutePath != videoFile.absolutePath && videoFile.renameTo(named)) {
                outFile = named
            }
        }

        uiProgress(-1, "Saving to gallery…")
        val size = outFile.length()
        return saveToGallery(outFile) to size
    }

    private fun headSize(url: String, ua: String, referer: String): Long {
        return try {
            val req = okhttp3.Request.Builder()
                .url(url).head()
                .header("User-Agent", ua)
                .header("Referer", referer)
                .build()
            val call = httpClient.newCall(req)
            activeCalls.add(call)
            try {
                call.execute().use { resp ->
                    if (resp.isSuccessful) resp.header("Content-Length")?.toLongOrNull() ?: 0L else 0L
                }
            } finally {
                activeCalls.remove(call)
            }
        } catch (_: Exception) {
            0L
        }
    }

    private fun downloadFile(
        url: String,
        ua: String,
        referer: String,
        dest: File,
        expectedTotal: Long,
        onBytes: (Long) -> Unit
    ) {
        var total = expectedTotal
        var ranges = false
        try {
            val hreq = okhttp3.Request.Builder().url(url).head()
                .header("User-Agent", ua)
                .header("Referer", referer)
                .build()
            val hcall = httpClient.newCall(hreq)
            activeCalls.add(hcall)
            try {
                hcall.execute().use { resp ->
                    if (resp.isSuccessful) {
                        if (total <= 0) {
                            total = resp.header("Content-Length")?.toLongOrNull() ?: 0L
                        }
                        ranges = resp.header("Accept-Ranges")?.contains("bytes") == true
                    }
                }
            } finally {
                activeCalls.remove(hcall)
            }
        } catch (_: Exception) { }

        if (total >= 8L * 1024 * 1024 && ranges) {
            try {
                downloadParallel(url, ua, referer, dest, total, onBytes)
                return
            } catch (e: Exception) {
                if (userCancelled) throw e
                try { dest.delete() } catch (_: Exception) { }
            }
        }
        downloadSingle(url, ua, referer, dest, onBytes)
    }

    private fun downloadSingle(
        url: String,
        ua: String,
        referer: String,
        dest: File,
        onBytes: (Long) -> Unit
    ) {
        val req = okhttp3.Request.Builder()
            .url(url)
            .header("User-Agent", ua)
            .header("Referer", referer)
            .build()
        val call = httpClient.newCall(req)
        activeCalls.add(call)
        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("Download failed (${resp.code})")
                val body = resp.body ?: throw IOException("Empty response")
                body.byteStream().use { input ->
                    dest.outputStream().use { out ->
                        val buf = ByteArray(256 * 1024)
                        var done = 0L
                        var lastReport = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (done - lastReport >= 512 * 1024) {
                                lastReport = done
                                onBytes(done)
                            }
                        }
                        onBytes(done)
                    }
                }
            }
        } finally {
            activeCalls.remove(call)
        }
    }

    /** Bari file 4 hisson me ek sath — har hissa alag connection, akhir me jor do. */
    private fun downloadParallel(
        url: String,
        ua: String,
        referer: String,
        dest: File,
        total: Long,
        onBytes: (Long) -> Unit
    ) {
        val parts = 4
        val counters = Array(parts) { java.util.concurrent.atomic.AtomicLong(0) }
        val reported = java.util.concurrent.atomic.AtomicLong(0)
        fun report() {
            val sum = counters.sumOf { it.get() }
            if (sum - reported.get() >= 512 * 1024 || sum >= total) {
                reported.set(sum)
                onBytes(sum)
            }
        }
        val pool = java.util.concurrent.Executors.newFixedThreadPool(parts)
        try {
            val futures = (0 until parts).map { i ->
                val startB = total * i / parts
                val endB = if (i == parts - 1) total - 1 else total * (i + 1) / parts - 1
                pool.submit(java.util.concurrent.Callable<File> {
                    val partFile = File(dest.parentFile, dest.name + ".p" + i)
                    val req = okhttp3.Request.Builder().url(url)
                        .header("User-Agent", ua)
                        .header("Referer", referer)
                        .header("Range", "bytes=$startB-$endB")
                        .build()
                    val call = httpClient.newCall(req)
                    activeCalls.add(call)
                    try {
                        call.execute().use { resp ->
                            if (resp.code != 206) throw IOException("Range failed (${resp.code})")
                            val body = resp.body ?: throw IOException("Empty response")
                            body.byteStream().use { input ->
                                partFile.outputStream().use { out ->
                                    val buf = ByteArray(256 * 1024)
                                    while (true) {
                                        val n = input.read(buf)
                                        if (n < 0) break
                                        out.write(buf, 0, n)
                                        counters[i].addAndGet(n.toLong())
                                        report()
                                    }
                                }
                            }
                        }
                    } finally {
                        activeCalls.remove(call)
                    }
                    partFile
                })
            }
            val files = futures.map { it.get() }
            dest.outputStream().use { out ->
                val buf = ByteArray(1024 * 1024)
                files.forEach { pf ->
                    pf.inputStream().use { input ->
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                        }
                    }
                    pf.delete()
                }
            }
            onBytes(total)
        } finally {
            pool.shutdownNow()
        }
    }

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
        val folder = "HDDownloader"

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
                ?: throw IOException("MediaStore insert failed")
            contentResolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { it.copyTo(out, 1024 * 1024) }
            } ?: throw IOException("Could not open the output file")
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

    private fun buildProgressText(pct: Int, etaSec: Int, line: String?, totalBytes: Long? = null): String {
        val parts = mutableListOf<String>()
        val l = line.orEmpty()
        val speed = Regex("""at\s+([\d.]+)\s*(GiB|MiB|KiB)/s""").find(l)
        if (speed != null) {
            parts += "${fmtNum(speed.groupValues[1].toDouble())} ${speed.groupValues[2]}/s"
        }
        if (etaSec > 0) parts += "ETA ${formatDuration(etaSec)}"
        if (totalBytes != null && totalBytes > 0) {
            parts += "Total ${fmtBytes(totalBytes)}"
        } else {
            val size = Regex("""of\s+~?\s*([\d.]+)\s*(GiB|MiB|KiB)""").find(l)
            if (size != null && pct in 0..100) {
                val total = size.groupValues[1].toDouble()
                parts += "${fmtNum(total * pct / 100.0)} of ${fmtNum(total)} ${size.groupValues[2]}"
            }
        }
        return if (parts.isEmpty()) "$pct%" else parts.joinToString("  •  ")
    }

    private fun fmtNum(v: Double): String = when {
        v >= 100 -> v.toInt().toString()
        v >= 10 -> "%.1f".format(v)
        else -> "%.2f".format(v).trimEnd('0').trimEnd('.')
    }

    /** Auto quality (best) ka total size formats se — progress me dikhane ke liye. */
    private fun computeTotalBytes(info: VideoInfo?, opt: QualityOption): Long? {
        val formats = info?.formats.orEmpty()
        if (formats.isEmpty()) return null
        val vids = formats.filter { (it.vcodec ?: "none") != "none" }
        if (vids.isEmpty()) return null
        val pool = if (opt.height > 0) vids.filter { (it.height ?: 0) <= opt.height } else vids
        val v = pool.ifEmpty { vids }.maxWithOrNull(
            compareBy(
                { it.height ?: 0 },
                { try { (it.fileSize as Number).toLong() } catch (e: Exception) { 0L } }
            )
        ) ?: return null
        val vSize = try { (v.fileSize as Number).toLong() } catch (e: Exception) { 0L }
        if (vSize <= 0) return null
        if ((v.acodec ?: "none") != "none") return vSize
        val a = formats
            .filter { (it.vcodec ?: "none") == "none" && (it.acodec ?: "none") != "none" }
            .maxByOrNull { try { (it.fileSize as Number).toLong() } catch (e: Exception) { 0L } }
        val aSize = if (a == null) 0L else try { (a.fileSize as Number).toLong() } catch (e: Exception) { 0L }
        return if (aSize > 0) vSize + aSize else null
    }

    private fun fmtBytes(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 100) "${mb.toInt()} MB" else "%.1f MB".format(mb)
    }

    private fun copyText(label: String, text: String, doneMsg: String) {
        if (text.isBlank()) {
            toast("Nothing to copy")
            return
        }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        toast(doneMsg)
    }

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
