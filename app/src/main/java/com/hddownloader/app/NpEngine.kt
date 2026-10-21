package com.hddownloader.app

import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.VideoStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * NewPipeExtractor wrapper — YouTube se foran (bina Python ke) title,
 * description aur direct stream links nikalta hai. Fail ho to caller
 * yt-dlp fallback par chala jata hai (Option A hybrid design).
 */
object NpEngine {

    data class Pick(val url: String, val height: Int, val suffix: String)

    data class Result(
        val title: String,
        val description: String,
        val durationSec: Int,
        val progressive: Pick?,
        val videoOnly: Pick?,
        val audio: Pick?
    )

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var inited = false

    private fun init() {
        if (inited) return
        synchronized(this) {
            if (!inited) {
                NewPipe.init(NpDownloader(http))
                inited = true
            }
        }
    }

    /** Blocking call — IO thread par chalana hai. Koi masla ho to exception. */
    fun extract(url: String): Result {
        init()
        val ex = ServiceList.YouTube.getStreamExtractor(url)
        ex.fetchPage()

        fun suffix(s: VideoStream): String =
            try { s.format?.suffix?.lowercase().orEmpty() } catch (_: Exception) { "" }

        fun heightOf(s: VideoStream): Int {
            val digits = s.resolution.orEmpty().takeWhile { it.isDigit() }
            return digits.toIntOrNull() ?: 0
        }

        // Progressive (video+awaz ek file) — mp4 pasand hai
        val progressive = ex.videoStreams
            .filter { suffix(it) == "mp4" }
            .maxByOrNull { heightOf(it) }
            ?.let { s -> s.url?.let { u -> Pick(u, heightOf(s), "mp4") } }

        // Sirf video (merge ke liye) — mp4 hi MediaMuxer me chalti hai
        val videoOnly = ex.videoOnlyStreams
            .filter { suffix(it) == "mp4" }
            .maxByOrNull { heightOf(it) }
            ?.let { s -> s.url?.let { u -> Pick(u, heightOf(s), "mp4") } }

        // Sirf audio — m4a hi mp4 container me mux hoti hai
        val audio = ex.audioStreams
            .filter {
                try { it.format?.suffix?.lowercase() == "m4a" } catch (_: Exception) { false }
            }
            .maxByOrNull { try { it.averageBitrate } catch (_: Exception) { 0 } }
            ?.let { s -> s.url?.let { u -> Pick(u, 0, "m4a") } }

        val desc = try {
            ex.description?.content.orEmpty()
        } catch (_: Exception) {
            ""
        }

        return Result(
            title = try { ex.name.orEmpty() } catch (_: Exception) { "" },
            description = desc,
            durationSec = try { ex.length.toInt() } catch (_: Exception) { 0 },
            progressive = progressive,
            videoOnly = videoOnly,
            audio = audio
        )
    }
}

/** NewPipe ko OkHttp ke zariye net par bhejne wala downloader. */
private class NpDownloader(private val client: OkHttpClient) : Downloader() {

    @Throws(IOException::class)
    override fun execute(request: Request): Response {
        val body = request.dataToSend()?.toRequestBody(null)
        val builder = okhttp3.Request.Builder()
            .url(request.url())
            .method(request.httpMethod(), body)
        request.headers().forEach { (name, values) ->
            values.forEach { builder.addHeader(name, it) }
        }
        client.newCall(builder.build()).execute().use { resp ->
            return Response(
                resp.code,
                resp.message,
                resp.headers.toMultimap(),
                resp.body?.string().orEmpty(),
                resp.request.url.toString()
            )
        }
    }
}
