package com.von.cameraapp.record

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 录像：消费 H.264 编码码流（含起始码），等关键帧后用 MediaMuxer 合成 mp4。
 * 与 RTSP 推流共用同一路编码输出。
 */
class RecordingManager(
    private val dir: File,
    private val width: Int,
    private val height: Int,
    private val listener: (String?) -> Unit
) {
    private val lock = Any()
    private var muxer: MediaMuxer? = null
    private var track = -1
    private var recording = false
    private var waitingKey = false
    private var path: String? = null
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null

    val isRecording: Boolean
        get() = synchronized(lock) { recording }

    fun onSpsPps(s: ByteArray, p: ByteArray) {
        synchronized(lock) {
            sps = s
            pps = p
        }
    }

    /** 开始录像（等待下一个关键帧真正落盘）。已在录像中返回 false。 */
    fun start(): Boolean = synchronized(lock) {
        if (recording) return false
        recording = true
        waitingKey = true
        true
    }

    /** 停止录像并回调文件路径（null 表示失败或无数据） */
    fun stop() = synchronized(lock) {
        if (!recording) {
            listener(null)
            return
        }
        recording = false
        waitingKey = false
        val m = muxer
        muxer = null
        val result = path
        path = null
        if (m != null) {
            try {
                m.stop()
                m.release()
                listener(result)
                return
            } catch (e: Exception) {
                Log.w(TAG, "muxer stop", e)
                try {
                    m.release()
                } catch (ignored: Exception) {
                }
            }
        }
        listener(null)
    }

    fun onFrame(frame: ByteArray, keyFrame: Boolean, ptsUs: Long) {
        synchronized(lock) {
            if (!recording) return
            if (waitingKey) {
                if (!keyFrame) return
                val s = sps ?: return
                val p = pps ?: return
                try {
                    val name = "REC_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.mp4"
                    dir.mkdirs()
                    val file = File(dir, name)
                    path = file.absolutePath
                    val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                        setByteBuffer("csd-0", ByteBuffer.wrap(s)) // 含起始码
                        setByteBuffer("csd-1", ByteBuffer.wrap(p))
                    }
                    val m = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                    track = m.addTrack(format)
                    m.start()
                    muxer = m
                    waitingKey = false
                    Log.i(TAG, "recording started: $path")
                } catch (e: Exception) {
                    Log.w(TAG, "muxer start", e)
                    recording = false
                    listener(null)
                    return
                }
            }
            val m = muxer ?: return
            try {
                val info = MediaCodec.BufferInfo()
                info.offset = 0
                info.size = frame.size
                info.presentationTimeUs = ptsUs
                info.flags = if (keyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                m.writeSampleData(track, ByteBuffer.wrap(frame), info)
            } catch (e: Exception) {
                Log.w(TAG, "writeSampleData", e)
            }
        }
    }

    companion object {
        private const val TAG = "RecordingManager"
    }
}
