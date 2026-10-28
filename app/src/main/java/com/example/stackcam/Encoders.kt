package com.example.stackcam

import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.ParcelFileDescriptor

/** Encodes camera YUV frames straight into an H.264 MP4, 30 fps playback. */
class TimelapseEncoder(
    private val pfd: ParcelFileDescriptor,
    private val w: Int,
    private val h: Int,
    rotation: Int,
) {
    private val codec: MediaCodec
    private val muxer: MediaMuxer
    private var track = -1
    private var muxStarted = false
    private val info = MediaCodec.BufferInfo()
    var frames = 0
        private set

    init {
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h)
        fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, 16_000_000)
        fmt.setInteger(MediaFormat.KEY_FRAME_RATE, 30)
        fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        muxer.setOrientationHint(rotation)
    }

    fun add(img: Image) {
        val idx = codec.dequeueInputBuffer(500_000)
        if (idx < 0) return
        val dst = codec.getInputImage(idx) ?: return
        copy(img, dst)
        val pts = frames.toLong() * 1_000_000L / 30L
        codec.queueInputBuffer(idx, 0, w * h * 3 / 2, pts, 0)
        frames++
        drain(false)
    }

    private fun copy(src: Image, dst: Image) {
        for (p in 0 until 3) {
            val sp = src.planes[p]
            val dp = dst.planes[p]
            val pw = if (p == 0) w else w / 2
            val ph = if (p == 0) h else h / 2
            val sb = sp.buffer
            val db = dp.buffer
            for (row in 0 until ph) {
                val so = row * sp.rowStride
                val d0 = row * dp.rowStride
                for (col in 0 until pw) {
                    db.put(d0 + col * dp.pixelStride, sb.get(so + col * sp.pixelStride))
                }
            }
        }
    }

    private fun drain(end: Boolean) {
        var idle = 0
        while (true) {
            val i = codec.dequeueOutputBuffer(info, if (end) 100_000L else 0L)
            if (i == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!end) return
                idle++
                if (idle > 50) return
            } else if (i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                track = muxer.addTrack(codec.outputFormat)
                muxer.start()
                muxStarted = true
            } else if (i >= 0) {
                val buf = codec.getOutputBuffer(i)
                if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0
                if (buf != null && info.size > 0 && muxStarted) {
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    muxer.writeSampleData(track, buf, info)
                }
                codec.releaseOutputBuffer(i, false)
                if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
            }
        }
    }

    fun finish() {
        try {
            val idx = codec.dequeueInputBuffer(1_000_000)
            if (idx >= 0) {
                codec.queueInputBuffer(idx, 0, 0, frames.toLong() * 1_000_000L / 30L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
            drain(true)
        } catch (_: Exception) {
        }
        try { codec.stop() } catch (_: Exception) {}
        try { codec.release() } catch (_: Exception) {}
        try { if (muxStarted) muxer.stop() } catch (_: Exception) {}
        try { muxer.release() } catch (_: Exception) {}
        try { pfd.close() } catch (_: Exception) {}
    }
}
