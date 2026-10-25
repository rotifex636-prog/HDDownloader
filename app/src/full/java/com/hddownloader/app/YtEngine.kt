package com.hddownloader.app

import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.mapper.VideoInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * FULL version ka backup engine (yt-dlp) — saara engine wala kaam yahan hai
 * taake lite variant bina Python engine ke ban sakay.
 */
object YtEngine {

    private val mutex = Mutex()

    @Volatile
    private var ready = false

    suspend fun ensureInit(ctx: Context) = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!ready) {
                repairIfBroken(ctx)
                YoutubeDL.getInstance().init(ctx)
                FFmpeg.getInstance().init(ctx)
                if (!engineFilesPresent(ctx)) {
                    // Kuch phones par library ki khud ki extraction adhoori reh
                    // jati hai (usr/lib gayab) — zip haath se khol do, warna
                    // libpython.so "libandroid-support.so not found" par rukta hai.
                    manualExtract(ctx)
                }
                if (!ytdlpPresent(ctx)) restoreYtdlp(ctx)
                ready = true
            }
        }
    }

    /** Asal download program (yt-dlp script) maujood hai ya nahi — engine ke
     *  chalne ke liye ye file sab se aakhir me lazmi hai. */
    private fun ytdlpPresent(ctx: Context): Boolean =
        File(ctx.noBackupFilesDir, "youtubedl-android/yt-dlp/yt-dlp").exists()

    /** yt-dlp script ko app ke andar band (raw resource) wali copy se wapas
     *  lana — bilkul wese hi jese library ki init_ytdlp karti hai. Zaroorat is
     *  liye pari ke library ki init aik process me SIRF aik dafa chalti hai:
     *  heal ke baad dobara init no-op ho jati hai aur yt-dlp wapas nahi aata
     *  (v2.1 ka "can't open file .../yt-dlp/yt-dlp" wala masla). */
    private fun restoreYtdlp(ctx: Context) {
        try {
            val dir = File(ctx.noBackupFilesDir, "youtubedl-android/yt-dlp")
            val out = File(dir, "yt-dlp")
            if (out.exists()) return
            dir.mkdirs()
            val resId = ctx.resources.getIdentifier("ytdlp", "raw", ctx.packageName)
            if (resId == 0) return
            ctx.resources.openRawResource(resId).use { input ->
                java.io.FileOutputStream(out).use { input.copyTo(it) }
            }
            out.setExecutable(true, false)
        } catch (_: Exception) { }
    }

    /** Files apni jagah pakki hon to library ka version-nishan bhi khud laga
     *  do — warna agle start par library phir se pura setup dohraati hai aur
     *  Health Check hamesha "setup baqi" dikhata hai. Nishan wahi hai jo
     *  library khud lagati hai: libpython.zip.so ki length. */
    private fun stampEngineMarker(ctx: Context) {
        try {
            val zipLen = File(ctx.applicationInfo.nativeLibraryDir, "libpython.zip.so")
                .length().toString()
            libPrefs(ctx).edit().putString("pythonLibVersion", zipLen).apply()
        } catch (_: Exception) { }
    }

    /** Library ke zip (libpython.zip.so / libffmpeg.zip.so) ko haath se
     *  youtubedl-android/packages/<name> me kholna — library ki extraction
     *  fail ho jaye to yehi asal files wapas laata hai. */
    private fun manualExtract(ctx: Context) {
        val base = File(ctx.noBackupFilesDir, "youtubedl-android")
        val nativeDir = File(ctx.applicationInfo.nativeLibraryDir)
        extractZip(File(nativeDir, "libpython.zip.so"), File(base, "packages/python"))
        extractZip(File(nativeDir, "libffmpeg.zip.so"), File(base, "packages/ffmpeg"))
    }

    private fun extractZip(zip: File, destDir: File) {
        if (!zip.exists()) return
        try {
            java.util.zip.ZipInputStream(java.io.BufferedInputStream(java.io.FileInputStream(zip))).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val out = File(destDir, entry.name)
                    if (out.canonicalPath.startsWith(destDir.canonicalPath)) {
                        if (entry.isDirectory) {
                            out.mkdirs()
                        } else {
                            out.parentFile?.mkdirs()
                            java.io.FileOutputStream(out).use { zis.copyTo(it) }
                            out.setExecutable(true, false)
                        }
                    }
                    entry = zis.nextEntry
                }
            }
        } catch (_: Exception) { }
    }

    private fun libPrefs(ctx: Context) =
        ctx.getSharedPreferences("youtubedl-android", Context.MODE_PRIVATE)

    /** Engine ki nikali hui asal files (python) maujood hain ya nahi.
     *  Asal layout (library ke source se): youtubedl-android/packages/python/usr/lib */
    private fun engineFilesPresent(ctx: Context): Boolean {
        // Dono files lazmi — aik bhi gayab ho to extraction adhoori hai aur
        // libpython link fail hota hai (v1.9 wala CANNOT LINK masla).
        val lib = File(ctx.noBackupFilesDir, "youtubedl-android/packages/python/usr/lib")
        return File(lib, "libandroid-support.so").exists() &&
            File(lib, "libpython3.12.so.1.0").exists()
    }

    /** Self Care > Health Check: engine ki sehat ki report (bina kuch badle). */
    fun healthChecks(ctx: Context): List<HealthItem> {
        val items = mutableListOf<HealthItem>()
        val pyLib = File(ctx.noBackupFilesDir, "youtubedl-android/packages/python/usr/lib")
        val support = File(pyLib, "libandroid-support.so").exists()
        val pySo = File(pyLib, "libpython3.12.so.1.0").exists()
        items += HealthItem(
            "Engine files", support && pySo,
            if (support && pySo) "python libraries maujood hain"
            else "kuch files gayab hain — Repair karo"
        )
        val jni = try {
            File(ctx.applicationInfo.nativeLibraryDir, "libandroid-support.so").exists()
        } catch (e: Exception) {
            false
        }
        items += HealthItem(
            "Engine support library", jni,
            if (jni) "app ke andar ship hai" else "missing hai"
        )
        val freeMb = try {
            ctx.noBackupFilesDir.usableSpace / (1024 * 1024)
        } catch (e: Exception) {
            -1L
        }
        items += HealthItem(
            "Storage space", freeMb >= 150,
            if (freeMb >= 0) "$freeMb MB free" else "maloom nahi"
        )
        val ytdlp = ytdlpPresent(ctx)
        items += HealthItem(
            "Engine program (yt-dlp)", ytdlp,
            if (ytdlp) "download program maujood hai"
            else "gayab hai — Repair karo"
        )
        return items
    }

    /** Self Care > Repair Engine / FIX ALL: wahi heal jo error par khud hoti hai. */
    suspend fun repair(ctx: Context) = heal(ctx)

    /** Self Care > Fresh Start: engine ka saara data saaf karke bilkul fresh
     *  nikaalta hai. User ki app settings (hdd_settings) ko haath nahi lagata —
     *  sirf engine ka auto-update wala andar ka nishan hatata hai. */
    suspend fun freshStart(ctx: Context) {
        mutex.withLock {
            ready = false
            try {
                File(ctx.noBackupFilesDir, "youtubedl-android").deleteRecursively()
            } catch (_: Exception) { }
            try {
                libPrefs(ctx).edit().clear().apply()
            } catch (_: Exception) { }
            try {
                ctx.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
            } catch (_: Exception) { }
            try {
                ctx.getSharedPreferences("hdd_settings", Context.MODE_PRIVATE)
                    .edit().remove("engine_auto_update_at").apply()
            } catch (_: Exception) { }
            YoutubeDL.getInstance().init(ctx)
            FFmpeg.getInstance().init(ctx)
            if (!engineFilesPresent(ctx)) manualExtract(ctx)
            if (!ytdlpPresent(ctx)) restoreYtdlp(ctx)
            if (engineFilesPresent(ctx) && ytdlpPresent(ctx)) stampEngineMarker(ctx)
            ready = true
        }
    }

    /** Error report ke liye engine ki asal haalat (konsi file hai, konsi nahi). */
    fun diagnostics(ctx: Context): String {
        val base = File(ctx.noBackupFilesDir, "youtubedl-android")
        val pyLib = File(base, "packages/python/usr/lib")
        val support = File(pyLib, "libandroid-support.so").exists()
        val pySo = File(pyLib, "libpython3.12.so.1.0").exists()
        val soCount = pyLib.listFiles()?.count { it.name.endsWith(".so") } ?: -1
        val marker = try {
            libPrefs(ctx).getString("pythonLibVersion", null) ?: "none"
        } catch (e: Exception) {
            "?"
        }
        val binZip = try {
            File(ctx.applicationInfo.nativeLibraryDir, "libpython.zip.so").length()
        } catch (e: Exception) {
            -1L
        }
        val freeMb = try {
            ctx.noBackupFilesDir.usableSpace / (1024 * 1024)
        } catch (e: Exception) {
            -1L
        }
        val jniSupport = try {
            File(ctx.applicationInfo.nativeLibraryDir, "libandroid-support.so").exists()
        } catch (e: Exception) {
            false
        }
        return "engine-check: support=$support pythonSo=$pySo soFiles=$soCount " +
            "marker=$marker binZip=$binZip freeMB=$freeMb jniSupport=$jniSupport " +
            "ytdlp=${ytdlpPresent(ctx)}"
    }

    /**
     * Kharab haalat ki khud-marammat: agar files gayab hon (masalan purani
     * heal ne folder saaf kar diya ho) to library ke version-nishan bhi saaf
     * kar do — warna library samajhti hai files maujood hain aur dobara
     * nikaalti hi nahi, engine hamesha ke liye ruk jata hai.
     */
    private fun repairIfBroken(ctx: Context) {
        if (engineFilesPresent(ctx)) return
        try {
            File(ctx.noBackupFilesDir, "youtubedl-android").deleteRecursively()
        } catch (_: Exception) { }
        try {
            libPrefs(ctx).edit().clear().apply()
        } catch (_: Exception) { }
    }

    /** Engine (yt-dlp) ko takreeban roz ek dafa background me khud update karta hai. */
    suspend fun updateIfDue(ctx: Context) {
        val prefs = ctx.getSharedPreferences("hdd_settings", Context.MODE_PRIVATE)
        val last = prefs.getLong("engine_auto_update_at", 0L)
        if (System.currentTimeMillis() - last < 24 * 60 * 60 * 1000L) return
        try {
            withContext(Dispatchers.IO) {
                YoutubeDL.getInstance().updateYoutubeDL(ctx)
            }
            prefs.edit()
                .putLong("engine_auto_update_at", System.currentTimeMillis())
                .apply()
        } catch (_: Exception) { }
    }

    /** Engine ki file kharab ho jaye to data saaf karke fresh engine nikaalta hai. */
    suspend fun heal(ctx: Context) {
        mutex.withLock {
            ready = false
            try {
                File(ctx.noBackupFilesDir, "youtubedl-android").deleteRecursively()
            } catch (_: Exception) { }
            try {
                libPrefs(ctx).edit().clear().apply()
            } catch (_: Exception) { }
            YoutubeDL.getInstance().init(ctx)
            FFmpeg.getInstance().init(ctx)
            if (!engineFilesPresent(ctx)) manualExtract(ctx)
            // Library ki init is process me dobara nahi chalegi (one-shot),
            // is liye yt-dlp aur version-nishan khud wapas lagana lazmi hai —
            // warna python files theek hone ke bawajood engine nahi chalta.
            if (!ytdlpPresent(ctx)) restoreYtdlp(ctx)
            if (engineFilesPresent(ctx) && ytdlpPresent(ctx)) stampEngineMarker(ctx)
            ready = true
            ctx.getSharedPreferences("hdd_settings", Context.MODE_PRIVATE)
                .edit()
                .putLong("engine_auto_update_at", System.currentTimeMillis())
                .apply()
        }
    }

    fun destroy(pid: String) {
        try {
            YoutubeDL.getInstance().destroyProcessById(pid)
        } catch (_: Exception) { }
    }

    /** Blocking — IO thread par chalana hai. */
    fun getInfo(ctx: Context, url: String): EngineInfo {
        val req = YoutubeDLRequest(url)
        req.addOption("--no-playlist")
        req.addOption("--socket-timeout", "15")
        if (isYoutube(url)) {
            req.addOption("--extractor-args", "youtube:player_client=android")
        }
        val info = YoutubeDL.getInstance().getInfo(req)
        return EngineInfo(
            title = info.title ?: "Video",
            description = info.description?.trim().orEmpty(),
            durationSec = (info.duration ?: 0).toInt(),
            totalBytesAuto = computeTotalBytes(info)
        )
    }

    /** Blocking — yt-dlp se download karke file wapas karta hai (save caller karega). */
    fun downloadToFile(
        ctx: Context,
        url: String,
        opt: QualityOption,
        pid: String,
        tmpDir: File,
        totalBytes: Long?,
        uiProgress: (Int, String) -> Unit
    ): File {
        val req = YoutubeDLRequest(url)
        req.addOption("--no-playlist")
        req.addOption("-f", opt.selector)
        req.addOption("-o", "${tmpDir.absolutePath}/%(title).80B [%(id)s].%(ext)s")
        req.addOption("--windows-filenames")
        req.addOption("--no-mtime")

        // Speed: parallel fragments + bare chunks
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
            req.addOption("-S", "res,ext:mp4:m4a")
            req.addOption("--merge-output-format", "mp4/mkv")
        }

        YoutubeDL.getInstance().execute(req, pid) { progress, eta, line ->
            val pct = progress.toInt().coerceIn(0, 100)
            val text = buildProgressText(pct, eta.toInt(), line, totalBytes)
            if (progress >= 0) uiProgress(pct, text) else uiProgress(-1, text)
            DownloadNotifier.update(ctx, text, if (progress >= 0) pct else null)
        }

        return tmpDir.listFiles()
            ?.filter {
                it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") &&
                    !it.name.endsWith(".temp")
            }
            ?.maxByOrNull { it.length() }
            ?: throw IllegalStateException("Downloaded file not found")
    }

    private fun isYoutube(url: String) =
        url.contains("youtube.com") || url.contains("youtu.be")

    // ---------------------------------------------------------------- helpers

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

    private fun fmtBytes(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 100) "${mb.toInt()} MB" else "%.1f MB".format(mb)
    }

    private fun formatDuration(total: Int): String {
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    /** Auto quality (best) ka total size formats se. */
    private fun computeTotalBytes(info: VideoInfo?): Long? {
        val formats = info?.formats.orEmpty()
        if (formats.isEmpty()) return null
        val vids = formats.filter { (it.vcodec ?: "none") != "none" }
        if (vids.isEmpty()) return null
        val v = vids.maxWithOrNull(
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
}
