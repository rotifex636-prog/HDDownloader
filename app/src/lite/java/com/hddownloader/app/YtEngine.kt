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
