package com.von.cameraapp.rtsp

import android.util.Log
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import java.util.concurrent.ConcurrentLinkedQueue

/** 视频源控制接口，由 Service 实现 */
interface StreamControl {
    fun streamActive(): Boolean
    fun sps(): ByteArray?
    fun pps(): ByteArray?
    fun videoInfo(): Triple<Int, Int, Int> // width, height, fps
    fun addClient(client: ClientSink)
    fun removeClient(client: ClientSink)
    fun requestKeyFrame()
}

/** 编码帧的消费者（一个 RTSP 客户端对应一个） */
interface ClientSink {
    fun send(frame: ByteArray, keyFrame: Boolean, ptsUs: Long)
    fun shutdown()
}

/**
 * 轻量 RTSP 服务器：仅支持 RTP/AVP/TCP（interleaved）单视频流。
 * 支持 OPTIONS / DESCRIBE / SETUP / PLAY / PAUSE / TEARDOWN / GET_PARAMETER / SET_PARAMETER。
 */
class RtspServer(private val port: Int, private val control: StreamControl) {

    @Volatile private var running = false
    private var serverSocket: ServerSocket? = null
    private val workers = ConcurrentLinkedQueue<Thread>()

    fun start() {
        if (running) return
        running = true
        Thread({ acceptLoop() }, "rtsp-accept").start()
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        workers.forEach { it.interrupt() }
        workers.clear()
    }

    private fun acceptLoop() {
        try {
            val ss = ServerSocket(port)
            serverSocket = ss
            Log.i(TAG, "RTSP server listening on $port")
            while (running) {
                val socket = try {
                    ss.accept()
                } catch (e: Exception) {
                    break
                }
                socket.tcpNoDelay = true
                val t = Thread({ handleSocket(socket) }, "rtsp-conn-${socket.port}")
                workers.add(t)
                t.start()
            }
        } catch (e: Exception) {
            if (running) Log.w(TAG, "acceptLoop", e)
        }
    }

    private fun handleSocket(socket: Socket) {
        var session: RtspSession? = null
        try {
            socket.soTimeout = 30_000
            val out = BufferedOutputStream(socket.getOutputStream())
            val reader = RtspReader(socket.getInputStream().buffered())
            var sessionId = ""
            while (running) {
                val msg = reader.nextMessage() ?: break
                val req = msg as? RtspReader.Request ?: continue
                val cSeq = req.cSeq
                when (req.method) {
                    "OPTIONS" -> respond(out, cSeq, "200 OK", listOf(
                        "Public: OPTIONS, DESCRIBE, SETUP, TEARDOWN, PLAY, PAUSE, GET_PARAMETER, SET_PARAMETER"))

                    "DESCRIBE" -> {
                        val sps = control.sps()
                        val pps = control.pps()
                        if (!control.streamActive() || sps == null || pps == null) {
                            respond(out, cSeq, "503 Service Unavailable", body = "stream not active")
                        } else {
                            val (w, h, fps) = control.videoInfo()
                            respond(out, cSeq, "200 OK",
                                headers = listOf("Content-Type: application/sdp"),
                                body = buildSdp(w, h, fps, sps, pps))
                        }
                    }

                    "SETUP" -> {
                        sessionId = java.lang.Long.toHexString(System.currentTimeMillis())
                        respond(out, cSeq, "200 OK", listOf(
                            "Session: $sessionId",
                            "Transport: RTP/AVP/TCP;interleaved=0-1"))
                    }

                    "PLAY" -> {
                        if (session == null) {
                            val s = RtspSession(out, control) { closed -> control.removeClient(closed) }
                            session = s
                            control.addClient(s)
                        }
                        control.requestKeyFrame()
                        respond(out, cSeq, "200 OK", listOf(
                            "Session: $sessionId",
                            "RTP-Info: seq=0;rtptime=0"))
                    }

                    "PAUSE", "GET_PARAMETER", "SET_PARAMETER" -> respond(out, cSeq, "200 OK")

                    "TEARDOWN" -> {
                        respond(out, cSeq, "200 OK", listOf("Session: $sessionId"))
                        break
                    }

                    else -> respond(out, cSeq, "405 Method Not Allowed")
                }
            }
        } catch (e: Exception) {
            // 客户端断开或读取超时，正常结束
        } finally {
            session?.let { control.removeClient(it) }
            try {
                socket.close()
            } catch (_: Exception) {
            }
            Log.i(TAG, "connection closed")
        }
    }

    private fun buildSdp(w: Int, h: Int, fps: Int, sps: ByteArray, pps: ByteArray): String {
        val sNal = com.von.cameraapp.codec.H264Encoder.stripStartCode(sps)
        val pNal = com.von.cameraapp.codec.H264Encoder.stripStartCode(pps)
        val b64sps = Base64.getEncoder().encodeToString(sNal)
        val b64pps = Base64.getEncoder().encodeToString(pNal)
        val profile = String.format("%02X%02X%02X", sNal[0], sNal[1], sNal[2])
        val sdp = """
            v=0
            o=- 0 0 IN IP4 127.0.0.1
            s=AndroidNetworkCamera
            i=live
            t=0 0
            a=tool:AndroidNetworkCamera
            a=type:broadcast
            a=control:*
            a=range:npt=0-
            m=video 0 RTP/AVP 96
            a=rtpmap:96 H264/90000
            a=fmtp:96 packetization-mode=1;profile-level-id=$profile;sprop-parameter-sets=$b64sps,$b64pps
            a=control:streamid=0
            a=framerate=$fps
            a=x-dimensions:$w,$h
        """.trimIndent()
        return sdp.replace("\n", "\r\n") + "\r\n"
    }

    private fun respond(
        out: OutputStream,
        cSeq: String,
        code: String,
        headers: List<String> = emptyList(),
        body: String? = null
    ) {
        val sb = StringBuilder()
        sb.append("RTSP/1.0 ").append(code).append("\r\n")
        sb.append("CSeq: ").append(cSeq).append("\r\n")
        sb.append("Server: AndroidNetworkCamera\r\n")
        headers.forEach { sb.append(it).append("\r\n") }
        if (body != null) {
            sb.append("Content-Length: ").append(body.toByteArray().size).append("\r\n")
        }
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.US_ASCII))
        if (body != null) out.write(body.toByteArray(Charsets.US_ASCII))
        out.flush()
    }

    /** 从流中读取 RTSP 文本请求，自动跳过二进制交错帧（客户端 RTCP） */
    class RtspReader(private val input: InputStream) {

        class Request(
            val method: String,
            val url: String,
            val headers: Map<String, String>,
            val cSeq: String
        )

        fun nextMessage(): Request? {
            while (true) {
                val first = input.read()
                if (first == -1) return null
                if (first == '$'.code) {
                    // 二进制交错帧：$ channel(1) length(2) data
                    val ch = input.read()
                    val hi = input.read()
                    val lo = input.read()
                    if (ch == -1 || hi == -1 || lo == -1) return null
                    var len = (hi shl 8) or lo
                    while (len > 0) {
                        val n = input.skip(len.toLong())
                        if (n <= 0) {
                            if (input.read() == -1) return null
                            len--
                        } else {
                            len -= n.toInt()
                        }
                    }
                    continue
                }
                // 文本请求
                var requestLine: String? = null
                val headers = HashMap<String, String>()
                var line = readLine(first.toChar())
                while (line != null) {
                    if (line.isEmpty()) break
                    if (requestLine == null) {
                        requestLine = line
                    } else {
                        val idx = line.indexOf(':')
                        if (idx > 0) {
                            headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
                        }
                    }
                    line = readLine()
                }
                if (requestLine == null) return null
                val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
                var remaining = contentLength
                while (remaining > 0) {
                    val b = input.read()
                    if (b == -1) return null
                    remaining--
                }
                val parts = requestLine.split(" ")
                if (parts.size < 3) return null
                return Request(parts[0].uppercase(), parts[1], headers, headers["cseq"] ?: "0")
            }
        }

        private fun readLine(firstChar: Char? = null): String? {
            val sb = StringBuilder()
            if (firstChar != null) sb.append(firstChar)
            while (true) {
                val b = input.read()
                if (b == -1) return null
                if (b == '\n'.code) break
                if (b != '\r'.code) sb.append(b.toChar())
            }
            return sb.toString()
        }
    }

    companion object {
        private const val TAG = "RtspServer"
    }
}
