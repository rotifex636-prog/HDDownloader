package com.hddownloader.app

import android.content.Context
import java.io.File

/**
 * LITE version: backup engine (yt-dlp/Python) hai hi nahi — is liye size
 * ~15MB. YouTube NewPipe se aur Facebook apne parser se chalta hai (jo hamesha
 * pehla rasta hain). Engine wala kaam manga jaye to saaf mana kar deta hai.
 */
object YtEngine {

    private const val MSG =
        "Ye video backup engine mangti hai — Lite version me engine nahi hai, " +
            "bari wali Expert Solutions Downloader app use karo is ke liye"

    suspend fun ensureInit(ctx: Context) { /* lite: engine nahi, kuch nahi karna */ }

    suspend fun updateIfDue(ctx: Context) { /* lite: engine nahi */ }

    suspend fun heal(ctx: Context) { /* lite: engine nahi */ }

    fun destroy(pid: String) { /* lite: koi process nahi */ }

    /** Self Care > Health Check (lite): engine hai hi nahi — ye normal hai. */
    fun healthChecks(ctx: Context): List<HealthItem> {
        val freeMb = try {
            ctx.noBackupFilesDir.usableSpace / (1024 * 1024)
        } catch (e: Exception) {
            -1L
        }
        return listOf(
            HealthItem(
                "Backup engine", true,
                "Lite version me engine nahi hota — ye normal hai"
            ),
            HealthItem(
                "Storage space", freeMb >= 150,
                if (freeMb >= 0) "$freeMb MB free" else "maloom nahi"
            )
        )
    }

    /** Self Care > Repair Engine (lite): engine nahi, kuch nahi karna. */
    suspend fun repair(ctx: Context) { /* lite: engine nahi */ }

    /** Self Care > Fresh Start (lite): sirf cache saaf. */
    suspend fun freshStart(ctx: Context) {
        try {
            ctx.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
        } catch (_: Exception) { }
    }

    fun diagnostics(ctx: Context): String = ""

    fun getInfo(ctx: Context, url: String): EngineInfo {
        throw EngineUnavailableException(MSG)
    }

    fun downloadToFile(
        ctx: Context,
        url: String,
        opt: QualityOption,
        pid: String,
        tmpDir: File,
        totalBytes: Long?,
        uiProgress: (Int, String) -> Unit
    ): File {
        throw EngineUnavailableException(MSG)
    }
}
