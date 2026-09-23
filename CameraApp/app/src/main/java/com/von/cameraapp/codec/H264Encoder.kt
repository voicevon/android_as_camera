package com.von.cameraapp.codec

import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.util.Log

/**
 * H.264 编码器：相机 YUV_420_888 输入（支持旋转），MediaCodec 编码输出。
 *
 * 输入采用打包颜色格式（NV12/I420）直接写入输入 ByteBuffer：
 * 不使用 getInputImage/Image 拷贝（部分设备原生层元数据与实际映射不一致，会触发 SIGSEGV）。
 * 全程 Java 数组边界检查，天然安全。
 *
 * 输出回调：SPS/PPS（含起始码）与整帧访问单元（含起始码）。
 * 编码器忙时丢帧，不做排队，保证低延迟。
 */
class H264Encoder(
    streamWidth: Int,
    streamHeight: Int,
    fps: Int,
    bitrate: Int,
    private val listener: Listener
) {
    interface Listener {
        /** 编码器输出 SPS/PPS（含 00 00 00 01 起始码） */
        fun onSpsPps(sps: ByteArray, pps: ByteArray)

        /** 编码器输出一帧访问单元（含起始码），keyFrame 表示 IDR */
        fun onFrame(frame: ByteArray, keyFrame: Boolean, ptsUs: Long)
    }

    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    private val semiPlanar: Boolean
    private val streamW: Int = streamWidth
    private val streamH: Int = streamHeight

    // 打包帧缓冲：Y + (NV12 交错 UV | I420 分离 UV)
    private val ySize = streamWidth * streamHeight
    private val chromaSize = ySize / 4
    private val frameBuf = ByteArray(ySize + chromaSize * 2)

    init {
        semiPlanar = pickSemiPlanar()
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, streamWidth, streamHeight).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                if (semiPlanar) MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
                else MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            // 让每个 IDR 帧前携带 SPS/PPS，解码器中途加入即可出图（不支持的编码器会忽略）
            setInteger("prepend-sps-pps-to-idr-frames", 1)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    }

    /** 优先 NV12（SemiPlanar），其次 I420（Planar），默认 NV12 */
    private fun pickSemiPlanar(): Boolean {
        return try {
            val caps = codec.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
            val colors = caps.colorFormats
            when {
                colors.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) -> true
                colors.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) -> false
                else -> true
            }
        } catch (e: Exception) {
            true
        }
    }

    @Volatile
    private var running = false
    private var drainThread: Thread? = null

    fun start() {
        running = true
        codec.start()
        drainThread = Thread { drainLoop() }.also { it.start() }
    }

    fun stop() {
        running = false
        drainThread?.join(1000)
        drainThread = null
        try {
            codec.stop()
        } catch (e: Exception) {
            Log.w(TAG, "codec stop", e)
        }
        codec.release()
    }

    fun requestKeyFrame() {
        try {
            val b = Bundle()
            b.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            codec.setParameters(b)
        } catch (e: Exception) {
            Log.w(TAG, "requestKeyFrame", e)
        }
    }

    /**
     * 送入一帧相机图像。rotation 为 0/90/180/270，编码输出为旋转后方向。
     * 无空闲输入缓冲时丢帧返回 false。
     */
    fun offerFrame(src: Image, rotation: Int): Boolean {
        if (!running) return false
        val index = try {
            codec.dequeueInputBuffer(0)
        } catch (e: Exception) {
            return false
        }
        if (index < 0) return false
        try {
            convert(src, rotation)
            val ib = codec.getInputBuffer(index)
            if (ib == null || ib.capacity() < frameBuf.size) {
                codec.queueInputBuffer(index, 0, 0, 0, 0)
                return false
            }
            ib.clear()
            ib.put(frameBuf)
            codec.queueInputBuffer(index, 0, frameBuf.size, src.timestamp / 1000, 0)
            return true
        } catch (e: Exception) {
            Log.w(TAG, "offerFrame", e)
            try {
                codec.queueInputBuffer(index, 0, 0, 0, 0)
            } catch (ignored: Exception) {
            }
            return false
        }
    }

    /** 相机 YUV_420_888 → 打包 NV12/I420（带旋转），写入 frameBuf */
    private fun convert(src: Image, rotation: Int) {
        val srcW = src.width
        val srcH = src.height
        val planes = src.planes
        val yP = planes[0]
        val uP = planes[1]
        val vP = planes[2]
        val yBuf = yP.buffer
        val uBuf = uP.buffer
        val vBuf = vP.buffer
        val yRow = yP.rowStride
        val yPix = yP.pixelStride
        val uRow = uP.rowStride
        val uPix = uP.pixelStride
        val vRow = vP.rowStride
        val vPix = vP.pixelStride

        // Y 平面：dst (dx,dy) ← src (sx,sy)
        var dy = 0
        while (dy < streamH) {
            val outOff = dy * streamW
            var dx = 0
            while (dx < streamW) {
                var sx: Int
                var sy: Int
                when (rotation) {
                    90 -> {
                        sx = dy
                        sy = srcH - 1 - dx
                    }
                    180 -> {
                        sx = srcW - 1 - dx
                        sy = srcH - 1 - dy
                    }
                    270 -> {
                        sx = srcW - 1 - dy
                        sy = dx
                    }
                    else -> {
                        sx = dx
                        sy = dy
                    }
                }
                frameBuf[outOff + dx] = yBuf.get(sy * yRow + sx * yPix)
                dx++
            }
            dy++
        }

        // 色度：源色度尺寸 (srcW/2 x srcH/2) → 目标色度 (streamW/2 x streamH/2)
        val cw = streamW / 2
        val ch = streamH / 2
        val scw = srcW / 2
        val sch = srcH / 2
        var cy = 0
        while (cy < ch) {
            var cx = 0
            while (cx < cw) {
                var sx: Int
                var sy: Int
                when (rotation) {
                    90 -> {
                        sx = cy
                        sy = sch - 1 - cx
                    }
                    180 -> {
                        sx = scw - 1 - cx
                        sy = sch - 1 - cy
                    }
                    270 -> {
                        sx = scw - 1 - cy
                        sy = cx
                    }
                    else -> {
                        sx = cx
                        sy = cy
                    }
                }
                val off = sy * uRow + sx * uPix
                val u = uBuf.get(off)
                val v = vBuf.get(sy * vRow + sx * vPix)
                val dstIdx = cy * cw + cx
                if (semiPlanar) {
                    // NV12: UVUV 交错
                    frameBuf[ySize + dstIdx * 2] = u
                    frameBuf[ySize + dstIdx * 2 + 1] = v
                } else {
                    // I420: 先 U 块后 V 块
                    frameBuf[ySize + dstIdx] = u
                    frameBuf[ySize + chromaSize + dstIdx] = v
                }
                cx++
            }
            cy++
        }
    }

    private fun drainLoop() {
        val info = MediaCodec.BufferInfo()
        while (running) {
            val index = try {
                codec.dequeueOutputBuffer(info, 10_000)
            } catch (e: Exception) {
                break
            }
            when {
                // 部分编码器（如三星）仅在输出格式变化时给出 csd-0/csd-1，不走 CODEC_CONFIG 缓冲
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> collectCsdFromFormat()
                index < 0 -> {} // INFO_TRY_AGAIN_LATER
                else -> {
                    val buf = codec.getOutputBuffer(index)
                    if (buf == null) {
                        codec.releaseOutputBuffer(index, false)
                        continue
                    }
                    if (info.size > 0) {
                        val data = ByteArray(info.size)
                        buf.position(info.offset)
                        buf.get(data)
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            // 部分编码器（如三星）SPS/PPS 可能分多个缓冲输出
                            collectSpsPps(data)
                        } else {
                            val isKey = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                            // 部分编码器把 SPS/PPS 内嵌在 IDR 帧前，从这里补齐
                            if (isKey) collectSpsPps(data)
                            listener.onFrame(data, isKey, info.presentationTimeUs)
                        }
                    }
                    codec.releaseOutputBuffer(index, false)
                }
            }
        }
    }

    /** 从输出格式元数据提取 csd-0/csd-1（SPS/PPS） */
    private fun collectCsdFromFormat() {
        try {
            val fmt = codec.outputFormat
            for (key in listOf("csd-0", "csd-1")) {
                val bb = fmt.getByteBuffer(key) ?: continue
                val data = ByteArray(bb.remaining())
                bb.get(data)
                collectSpsPps(data)
            }
        } catch (e: Exception) {
            Log.w(TAG, "collectCsdFromFormat: ${e.message}")
        }
    }

    /** 跨缓冲累积 SPS/PPS，两者齐备时回调一次并清空 */
    private fun collectSpsPps(data: ByteArray) {
        for (nal in splitNals(data)) {
            // splitNals 返回的 NAL 保留起始码（3/4 字节），类型字节在起始码之后
            val hdr = when {
                nal.size > 4 && nal[0].toInt() == 0 && nal[1].toInt() == 0 &&
                    nal[2].toInt() == 0 && nal[3].toInt() == 1 -> 4
                nal.size > 3 && nal[0].toInt() == 0 && nal[1].toInt() == 0 &&
                    nal[2].toInt() == 1 -> 3
                else -> 0
            }
            if (hdr == 0 || nal.size <= hdr) continue
            when (nal[hdr].toInt() and 0x1F) {
                7 -> pendingSps = nal
                8 -> pendingPps = nal
            }
        }
        val s = pendingSps
        val p = pendingPps
        if (s != null && p != null) {
            listener.onSpsPps(s, p)
            pendingSps = null
            pendingPps = null
        }
    }

    private var pendingSps: ByteArray? = null
    private var pendingPps: ByteArray? = null

    companion object {
        private const val TAG = "H264Encoder"

        /** 按起始码（00 00 01 / 00 00 00 01）切分 NAL，返回的 NAL 保留起始码 */
        fun splitNals(data: ByteArray): List<ByteArray> {
            val result = ArrayList<ByteArray>()

            fun startCodeLen(pos: Int): Int? {
                if (pos + 3 < data.size && data[pos].toInt() == 0 && data[pos + 1].toInt() == 0 &&
                    data[pos + 2].toInt() == 0 && data[pos + 3].toInt() == 1
                ) return 4
                if (pos + 2 < data.size && data[pos].toInt() == 0 && data[pos + 1].toInt() == 0 &&
                    data[pos + 2].toInt() == 1
                ) return 3
                return null
            }

            var start = -1
            var pos = 0
            while (pos < data.size) {
                val sc = startCodeLen(pos)
                if (sc != null) {
                    if (start >= 0) result.add(data.copyOfRange(start, pos))
                    start = pos
                    pos += sc
                } else {
                    pos++
                }
            }
            if (start >= 0) result.add(data.copyOfRange(start, data.size))
            return result
        }

        /** 去掉 NAL 前的起始码 */
        fun stripStartCode(nal: ByteArray): ByteArray = when {
            nal.size > 4 && nal[0].toInt() == 0 && nal[1].toInt() == 0 && nal[2].toInt() == 0 && nal[3].toInt() == 1 ->
                nal.copyOfRange(4, nal.size)
            nal.size > 3 && nal[0].toInt() == 0 && nal[1].toInt() == 0 && nal[2].toInt() == 1 ->
                nal.copyOfRange(3, nal.size)
            else -> nal
        }
    }
}
