package com.hddownloader.app

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaMuxer
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Android ke built-in MediaMuxer se video file + audio file ko ek mp4 me
 * jorta hai — koi re-encode nahi (tez, battery halki, phone garam nahi).
 * Green Hole bhi yahi tareeqa use karta hai.
 */
object Muxer {

    @Throws(IOException::class)
    fun merge(videoFile: File, audioFile: File, outFile: File) {
        val vEx = MediaExtractor()
        val aEx = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            vEx.setDataSource(videoFile.absolutePath)
            aEx.setDataSource(audioFile.absolutePath)

            val vTrack = findTrack(vEx, "video/")
                ?: throw IOException("Video track not found")
            val aTrack = findTrack(aEx, "audio/")
                ?: throw IOException("Audio track not found")

            muxer = MediaMuxer(
                outFile.absolutePath,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            )
            val vOut = muxer.addTrack(vEx.getTrackFormat(vTrack))
            val aOut = muxer.addTrack(aEx.getTrackFormat(aTrack))
            muxer.start()
            copySamples(vEx, vTrack, muxer, vOut)
            copySamples(aEx, aTrack, muxer, aOut)
            muxer.stop()
        } finally {
            try { muxer?.release() } catch (_: Exception) { }
            try { vEx.release() } catch (_: Exception) { }
            try { aEx.release() } catch (_: Exception) { }
        }
    }

    private fun findTrack(ex: MediaExtractor, prefix: String): Int? {
        for (i in 0 until ex.trackCount) {
            val mime = ex.getTrackFormat(i).getString(android.media.MediaFormat.KEY_MIME)
            if (mime != null && mime.startsWith(prefix)) return i
        }
        return null
    }

    private fun copySamples(
        ex: MediaExtractor,
        track: Int,
        muxer: MediaMuxer,
        outTrack: Int
    ) {
        ex.selectTrack(track)
        val buffer = ByteBuffer.allocate(1024 * 1024)
        val info = MediaCodec.BufferInfo()
        while (true) {
            val size = ex.readSampleData(buffer, 0)
            if (size < 0) break
            info.offset = 0
            info.size = size
            info.presentationTimeUs = if (ex.sampleTime >= 0) ex.sampleTime else 0
            info.flags =
                if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0)
                    MediaCodec.BUFFER_FLAG_KEY_FRAME
                else 0
            muxer.writeSampleData(outTrack, buffer, info)
            ex.advance()
        }
        ex.unselectTrack(track)
    }
}
