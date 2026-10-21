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
                YoutubeDL.getInstance().init(ctx)
                FFmpeg.getInstance().init(ctx)
                ready = true
            }
        }
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
            YoutubeDL.getInstance().init(ctx)
            FFmpeg.getInstance().init(ctx)
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
