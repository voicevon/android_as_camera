package com.von.cameraapp.rtsp

import com.von.cameraapp.codec.H264Encoder
import java.io.IOException
import java.io.OutputStream

/**
 * 单个 RTSP 客户端会话：H.264 码流按 RFC 6184 打包（单 NAL / FU-A），
 * 通过 TCP 交错方式（'$' + channel + length）发送 RTP。
 * 仅在编码器输出线程调用 send，线程内自行同步。
 */
class RtspSession(
    private val out: OutputStream,
    private val control: StreamControl,
    private val onClosed: (RtspSession) -> Unit
) : ClientSink {

    private var seq: Int = (0..0xFFFF).random()
    private val ssrc: Int = (1..Int.MAX_VALUE).random()
    @Volatile private var closed = false
    private var firstSend = true

    override fun send(frame: ByteArray, keyFrame: Boolean, ptsUs: Long) {
        if (closed) return
        try {
            val ts = ((ptsUs * 9L) / 100L).toInt() // 90kHz 时钟
            val nals = ArrayList<ByteArray>()
            if (keyFrame || firstSend) {
                val sps = control.sps()
                val pps = control.pps()
                if (sps != null && pps != null) {
                    nals.add(H264Encoder.stripStartCode(sps))
                    nals.add(H264Encoder.stripStartCode(pps))
                }
            }
            for (nal in H264Encoder.splitNals(frame)) {
                val pure = H264Encoder.stripStartCode(nal)
                if (pure.isEmpty()) continue
                val type = pure[0].toInt() and 0x1F
                if (type == 7 || type == 8 || type == 9) continue // SPS/PPS/AUD 不重复发送
                nals.add(pure)
            }
            if (nals.isEmpty()) return
            firstSend = false
            for (i in nals.indices) {
                packetize(nals[i], ts, i == nals.lastIndex)
            }
            out.flush()
        } catch (e: IOException) {
            closed = true
            onClosed(this)
        } catch (e: Exception) {
            closed = true
            onClosed(this)
        }
    }

    override fun shutdown() {
        closed = true
    }

    /** 单 NAL 或 FU-A 分片，写入交错通道 0 */
    private fun packetize(nal: ByteArray, ts: Int, marker: Boolean) {
        if (nal.size <= MAX_PAYLOAD) {
            val pkt = ByteArray(12 + nal.size)
            fillHeader(pkt, ts, marker)
            System.arraycopy(nal, 0, pkt, 12, nal.size)
            writeInterleaved(pkt)
        } else {
            val indicator = ((nal[0].toInt() and 0x60) or 28).toByte() // FU-A: type=28
            val nalType = nal[0].toInt() and 0x1F
            var offset = 1
            var first = true
            while (offset < nal.size) {
                val chunk = minOf(MAX_PAYLOAD - 2, nal.size - offset)
                val last = offset + chunk >= nal.size
                val fuHeader = (((if (first) 0x80 else 0) or (if (last) 0x40 else 0)) or nalType).toByte()
                val pkt = ByteArray(14 + chunk)
                fillHeader(pkt, ts, marker && last)
                pkt[12] = indicator
                pkt[13] = fuHeader
                System.arraycopy(nal, offset, pkt, 14, chunk)
                writeInterleaved(pkt)
                offset += chunk
                first = false
            }
        }
    }

    private fun fillHeader(pkt: ByteArray, ts: Int, marker: Boolean) {
        pkt[0] = 0x80.toByte() // V=2
        pkt[1] = (96 or (if (marker) 0x80 else 0)).toByte() // PT=96, marker
        pkt[2] = ((seq ushr 8) and 0xFF).toByte()
        pkt[3] = (seq and 0xFF).toByte()
        seq = (seq + 1) and 0xFFFF
        pkt[4] = ((ts ushr 24) and 0xFF).toByte()
        pkt[5] = ((ts ushr 16) and 0xFF).toByte()
        pkt[6] = ((ts ushr 8) and 0xFF).toByte()
        pkt[7] = (ts and 0xFF).toByte()
        pkt[8] = ((ssrc ushr 24) and 0xFF).toByte()
        pkt[9] = ((ssrc ushr 16) and 0xFF).toByte()
        pkt[10] = ((ssrc ushr 8) and 0xFF).toByte()
        pkt[11] = (ssrc and 0xFF).toByte()
    }

    private fun writeInterleaved(rtp: ByteArray) {
        out.write(0x24) // '$'
        out.write(0) // channel 0 (RTP)
        out.write((rtp.size ushr 8) and 0xFF)
        out.write(rtp.size and 0xFF)
        out.write(rtp)
    }

    companion object {
        private const val MAX_PAYLOAD = 1400
    }
}
