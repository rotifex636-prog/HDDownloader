package com.hddownloader.app


/** Download ke liye tayyar quality option (sab variants me common). */
data class QualityOption(
    val label: String,
    val selector: String,
    val audioOnly: Boolean = false,
    val height: Int = 0,
    val directUrl: String? = null,
    val mergeAudioUrl: String? = null
)

/** Backup engine (yt-dlp) se mili video ki maloomat — apni type taake
 *  lite variant ko engine ki classes ki zaroorat na pare. */
data class EngineInfo(
    val title: String,
    val description: String,
    val durationSec: Int,
    val totalBytesAuto: Long?
)

/** Lite version me jab backup engine wala kaam manga jaye. */
class EngineUnavailableException(message: String) : Exception(message)

/** Self Care > Health Check ki aik line: kya check hua, theek hai ya nahi,
 *  aur chhoti si tafseel. Dono variants (full/lite) yehi wapas karte hain. */
data class HealthItem(
    val label: String,
    val ok: Boolean,
    val detail: String
)
