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
    private var lastFbDiag: String = ""

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
        b.btnSelfCare.setOnClickListener {
            startActivity(Intent(this, SelfCareActivity::class.java))
        }
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
        lastNpError = ""
        lastFbDiag = ""
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
            // Share link par ID nahi mili: engine se pucho — ya info milegi,
            // ya us ke error me video ki ID chhupi hogi, us se embed wala rasta
            if (extractFbId(url) == null) {
                var engineError: Exception? = null
                val info = try {
                    fetchVideoInfo(url)
                } catch (e: Exception) {
                    engineError = e
                    null
                }
                if (info != null) {
                    val opt = QualityOption("Auto", "bv*+ba/b")
                    return Prepared(
                        opt,
                        info.totalBytesAuto,
                        info.title.ifBlank { "Video" },
                        info.description.trim(),
                        info.durationSec
                    )
                }
                val idE = engineError?.message?.let { msg ->
                    Regex("""\[facebook\]\s+(\d{8,})""").find(msg)?.groupValues?.get(1)
                        ?: Regex("""(\d{12,20})""").find(msg)?.groupValues?.get(1)
                }
                if (idE != null) {
                    val fast2 = fetchFacebookFast(url, idE)
                    val d2 = fast2?.hdUrl ?: fast2?.sdUrl
                    if (fast2 != null && d2 != null) {
                        return Prepared(
                            QualityOption("Auto", "", directUrl = d2),
                            null,
                            fast2.title.ifBlank { "Facebook Video" },
                            fast2.description.trim(),
                            fast2.durationSec
                        )
                    }
                }
                throw engineError ?: IllegalStateException("Facebook fetch failed")
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
            info.totalBytesAuto,
            info.title.ifBlank { "Video" },
            info.description.trim(),
            info.durationSec
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
                var healedDl = false
                var result: Pair<String, Long>? = null
                while (result == null) {
                    try {
                        result = withContext(Dispatchers.IO) {
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
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (!healedDl && !userCancelled && isEngineCorrupt(e)) {
                            healedDl = true
                            healEngine()
                        } else {
                            throw e
                        }
                    }
                }
                val dlResult = result
                b.holeView.percent = 100
                setFlow(HoleView.State.DONE, "✓ Saved to Gallery › HDDownloader")
                showCard(p, dlResult.second, true)
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
        processId?.let { YtEngine.destroy(it) }
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
        try {
            val diag = YtEngine.diagnostics(applicationContext)
            if (diag.isNotBlank()) sb.append("\n\n").append(diag)
        } catch (_: Exception) { }
        if (lastFbDiag.isNotBlank()) {
            sb.append("\n\nFB check (meri app ne dekha):\n").append(lastFbDiag)
        }
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

    private suspend fun ensureEngine() = YtEngine.ensureInit(applicationContext)

    /** Engine (yt-dlp) ko takreeban roz ek dafa background me khud update karta hai. */
    private suspend fun autoUpdateEngineIfDue() = YtEngine.updateIfDue(applicationContext)

    private fun isEngineCorrupt(e: Throwable): Boolean {
        val m = e.message ?: return false
        return m.contains("ZipImportError") || m.contains("bad local file header") ||
            m.contains("zipimport") || m.contains("CANNOT LINK") ||
            m.contains("libandroid-support")
    }

    /** Engine ki file kharab ho jaye to YtEngine fresh engine nikaal deta hai. */
    private suspend fun healEngine() = YtEngine.heal(applicationContext)

    private suspend fun fetchVideoInfo(url: String): EngineInfo {
        ensureEngine()
        return try {
            withContext(Dispatchers.IO) { YtEngine.getInfo(applicationContext, url) }
        } catch (e: Exception) {
            if (isEngineCorrupt(e)) {
                healEngine()
                withContext(Dispatchers.IO) { YtEngine.getInfo(applicationContext, url) }
            } else {
                throw e
            }
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
    private fun extractFbId(u: String): String? {
        val pats = listOf(
            Regex("""[?&]v=(\d{5,})"""),
            Regex("""/videos/(\d{5,})"""),
            Regex("""/reel/(\d{5,})"""),
            Regex("""[?&]story_fbid=(\d{5,})"""),
            Regex("""/watch/(\d{5,})""")
        )
        for (pat in pats) {
            pat.find(u)?.let { return it.groupValues[1] }
        }
        return null
    }

    private suspend fun fetchFacebookFast(url: String, knownId: String? = null): FbFastInfo? =
        withContext(Dispatchers.IO) {
            val diag = StringBuilder()
            var id = knownId ?: extractFbId(url)

            // Share link (share/v/...) me ID nahi hoti — pehle link khol kar
            // redirect ke final URL se ID nikalo (browser wala tareeqa: OkHttp)
            if (id == null) {
                val (info0, final0) = fetchFbAttempt(url, false, diag, "resolve")
                if (info0 != null) return@withContext info0
                id = extractFbId(final0)
                if (id != null) diag.append("resolved-id=").append(id).append("\n")
            }

            if (id != null) {
                val canon = java.net.URLEncoder.encode(
                    "https://www.facebook.com/watch/?v=$id", "UTF-8"
                )
                fetchFbAttempt(
                    "https://www.facebook.com/plugins/video.php?href=$canon&show_text=0&width=560",
                    false, diag, "embed"
                ).first?.let { return@withContext it }
                fetchFbAttempt(
                    "https://www.facebook.com/watch/?v=$id", false, diag, "watch"
                ).first?.let { return@withContext it }
                fetchFbAttempt(
                    "https://m.facebook.com/watch/?v=$id", true, diag, "mobile"
                ).first?.let { return@withContext it }
            }

            // Aakhri koshishein: embed asal link ke sath, aur link mobile roop me
            val enc = java.net.URLEncoder.encode(url, "UTF-8")
            fetchFbAttempt(
                "https://www.facebook.com/plugins/video.php?href=$enc&show_text=0&width=560",
                false, diag, "embed-link"
            ).first?.let { return@withContext it }
            if (id == null) {
                fetchFbAttempt(url, true, diag, "link-mobile").first?.let {
                    return@withContext it
                }
            }

            lastFbDiag = diag.toString().trim()
            null
        }

    /** Ek FB page ko OkHttp (browser jese headers) se gehra scan karke direct
     *  links nikalne ki koshish. Wapas: (info, finalUrl) — finalUrl redirect ke
     *  baad wala asal address hota hai jis se ID mil sakti hai.
     *  Har koshish ka natija diag me likhta hai taake fail ho to wajah pata chale. */
    private fun fetchFbAttempt(
        pageUrl: String,
        mobile: Boolean,
        diag: StringBuilder,
        label: String
    ): Pair<FbFastInfo?, String> {
        var code = -1
        var bytes = 0
        var finalUrl = ""
        var html = ""
        try {
            val req = okhttp3.Request.Builder()
                .url(pageUrl)
                .header(
                    "User-Agent",
                    if (mobile)
                        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                            "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
                    else
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                            "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
                )
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Referer", "https://www.facebook.com/")
                .build()
            val call = httpClient.newCall(req)
            activeCalls.add(call)
            try {
                call.execute().use { resp ->
                    code = resp.code
                    finalUrl = resp.request.url.toString()
                    if (code in 200..299) {
                        val body = resp.body
                        if (body != null) {
                            var total = 0
                            html = body.byteStream().bufferedReader().use { reader ->
                                val sb = StringBuilder()
                                val buf = CharArray(16384)
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
                            bytes = total
                        }
                    }
                }
            } finally {
                activeCalls.remove(call)
            }
        } catch (e: Exception) {
            diag.append(label).append(": EX ").append(e.javaClass.simpleName).append("\n")
            return null to finalUrl
        }
        val info = if (html.isNotBlank()) parseFbHtml(html) else null
        diag.append(label).append(": code=").append(code)
            .append(" bytes=").append(bytes)
            .append(" hd=").append(if (html.contains("hd_src")) "Y" else "N")
            .append(" playable=").append(if (html.contains("playable_url")) "Y" else "N")
            .append(" login=").append(if (html.contains("login")) "Y" else "N")
            .append(" final=").append(finalUrl.take(80)).append("\n")
        return info to finalUrl
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
        val file = YtEngine.downloadToFile(
            applicationContext, url, opt, pid, tmpDir, totalBytes, uiProgress
        )
        uiProgress(-1, "Saving to gallery\u2026")
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

}
