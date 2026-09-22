package com.von.cameraapp.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.Image
import android.os.Binder
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.util.Size
import android.view.Surface
import com.von.cameraapp.R
import com.von.cameraapp.camera.CameraController
import com.von.cameraapp.codec.H264Encoder
import com.von.cameraapp.config.StreamConfig
import com.von.cameraapp.motion.MotionDetector
import com.von.cameraapp.mqtt.MqttControl
import com.von.cameraapp.mqtt.StatusReporter
import com.von.cameraapp.photo.PhotoTaker
import com.von.cameraapp.record.RecordingManager
import com.von.cameraapp.rtsp.ClientSink
import com.von.cameraapp.rtsp.RtspServer
import com.von.cameraapp.rtsp.StreamControl
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 前台服务：承载推流全生命周期，是各模块的总装配点。
 * 本地 UI 通过 Binder 调用；远程通过 MQTT 命令调用，两条路径汇合到同一组公开方法。
 */
class CameraStreamService : Service(), StreamControl {

    data class UiState(
        val streaming: Boolean = false,
        val recording: Boolean = false,
        val motionEnabled: Boolean = false,
        val mqttConnected: Boolean = false,
        val rtspUrl: String = "",
        val camera: String = "",
        val message: String = ""
    )

    inner class LocalBinder : Binder() {
        val service: CameraStreamService
            get() = this@CameraStreamService
    }

    fun interface UiListener {
        fun onUiState(state: UiState)
    }

    var uiListener: UiListener? = null

    private lateinit var config: StreamConfig
    private var controller: CameraController? = null
    private var encoder: H264Encoder? = null
    private var rtspServer: RtspServer? = null
    private var mqtt: MqttControl? = null
    private var reporter: StatusReporter? = null
    private var motion: MotionDetector? = null
    private var photo: PhotoTaker? = null
    private var recorder: RecordingManager? = null

    private val clients = ConcurrentLinkedQueue<ClientSink>()
    @Volatile private var storedSps: ByteArray? = null
    @Volatile private var storedPps: ByteArray? = null
    @Volatile var streaming = false
        private set
    @Volatile private var mqttConnected = false
    @Volatile private var previewSurface: Surface? = null
    @Volatile private var facingBack: Boolean = false
    @Volatile private var currentRotation = 0
    @Volatile private var encW = 0
    @Volatile private var encH = 0

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        config = StreamConfig(this)
        facingBack = !config.frontCamera

        mqtt = MqttControl(config, object : MqttControl.Listener {
            override fun onCommand(action: String, params: JSONObject) {
                mainHandler.post { handleCommand(action, params) }
            }

            override fun onMqttConnected(connected: Boolean) {
                mqttConnected = connected
                notifyUi()
            }
        }).also { it.start() }

        motion = MotionDetector(config) { score ->
            mqtt?.publishEvent("motion", JSONObject().put("score", score.toInt()))
        }

        photo = PhotoTaker(this) { path ->
            if (path != null) {
                mqtt?.publishEvent("photo", JSONObject().put("path", path))
            } else {
                mqtt?.publishEvent("error", JSONObject().put("msg", "拍照失败"))
            }
        }

        val movieDir = getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: filesDir
        recorder = RecordingManager(movieDir, 0, 0) { path ->
            if (path != null) {
                mqtt?.publishEvent("record", JSONObject().put("path", path).put("status", "stopped"))
            }
        }

        reporter = StatusReporter(this, mqtt!!) {
            JSONObject().apply {
                put("streaming", streaming)
                put("recording", recorder?.isRecording ?: false)
                put("motion", motion?.enabled ?: false)
                put("camera", if (facingBack) "back" else "front")
                put("resolution", "${encW}x${encH}")
                put("rtsp", rtspUrl())
                put("device", config.deviceId)
            }
        }.also { it.start() }

        Log.i(TAG, "service created, deviceId=${config.deviceId}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder = LocalBinder()

    override fun onDestroy() {
        closeCameraEncoder()
        rtspServer?.stop()
        reporter?.stop()
        mqtt?.stop()
        uiListener = null
        Log.i(TAG, "service destroyed")
        super.onDestroy()
    }

    // ---------- 供 UI / MQTT 调用的公开方法 ----------

    fun startStream(preview: Surface?) {
        if (streaming) return
        if (checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            postMessage("缺少相机权限")
            return
        }
        previewSurface = preview
        startForegroundCompat()
        if (rtspServer == null) rtspServer = RtspServer(config.rtspPort, this)
        rtspServer?.start()
        postMessage("正在打开摄像头…")
        openCameraAndEncoder()
    }

    fun stopStream() {
        streaming = false
        closeCameraEncoder()
        clients.forEach { it.shutdown() }
        clients.clear()
        stopForeground(STOP_FOREGROUND_REMOVE)
        notifyUi()
        Log.i(TAG, "stream stopped")
    }

    fun switchCamera() {
        if (!streaming) {
            postMessage("未在推流")
            return
        }
        facingBack = !facingBack
        config.frontCamera = !facingBack
        openCameraAndEncoder()
    }

    fun setZoom(ratio: Float) {
        controller?.setZoom(ratio)
    }

    fun capture() {
        if (!streaming) {
            postMessage("未在推流，无法拍照")
            return
        }
        photo?.requestCapture()
    }

    fun startRecord() {
        if (!streaming) {
            postMessage("未在推流，无法录像")
            return
        }
        val rec = recorder ?: return
        if (rec.start()) notifyUi()
    }

    fun stopRecord() {
        recorder?.stop()
    }

    fun setMotion(enabled: Boolean, sensitivity: Int) {
        motion?.enabled = enabled
        config.motionEnabled = enabled
        if (sensitivity in 1..5) config.motionSensitivity = sensitivity
        notifyUi()
    }

    /** UI 预览面就绪后注入；推流中则重建会话以带上预览 */
    fun setPreviewSurface(surface: Surface?) {
        val changed = surface != previewSurface
        previewSurface = surface
        if (changed && streaming && surface != null) {
            openCameraAndEncoder()
        }
    }

    fun currentState(): UiState = UiState(
        streaming = streaming,
        recording = recorder?.isRecording ?: false,
        motionEnabled = motion?.enabled ?: false,
        mqttConnected = mqttConnected,
        rtspUrl = rtspUrl(),
        camera = if (facingBack) "后置" else "前置",
        message = ""
    )

    // ---------- 内部实现 ----------

    private fun handleCommand(action: String, params: JSONObject) {
        Log.i(TAG, "mqtt command: $action $params")
        when (action) {
            "start_stream" -> startStream(null)
            "stop_stream" -> stopStream()
            "switch_camera" -> switchCamera()
            "zoom" -> setZoom(params.optDouble("ratio", 1.0).toFloat())
            "capture" -> capture()
            "start_record" -> startRecord()
            "stop_record" -> stopRecord()
            "motion" -> setMotion(params.optBoolean("enabled", motion?.enabled ?: false), params.optInt("sensitivity", -1))
            else -> mqtt?.publishEvent("error", JSONObject().put("msg", "未知命令: $action"))
        }
    }

    private fun openCameraAndEncoder() {
        closeCameraEncoder()
        val desired = config.resolution()
        val controller = CameraController(this, Size(desired.first, desired.second), frameListener)
        this.controller = controller
        controller.open(
            if (facingBack) android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK
            else android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT,
            previewSurface
        )
    }

    private val frameListener = object : CameraController.Listener {
        override fun onFrame(image: Image) = dispatchFrame(image)

        override fun onError(msg: String) {
            postMessage(msg)
            if (streaming) {
                streaming = false
                notifyUi()
            }
        }

        override fun onOpened(facing: Int, rotation: Int, width: Int, height: Int) {
            currentRotation = rotation
            // 旋转 90/270 时编码分辨率宽高对调
            if (rotation == 90 || rotation == 270) {
                encW = height
                encH = width
            } else {
                encW = width
                encH = height
            }
            recorder = RecordingManager(
                getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: filesDir,
                encW, encH
            ) { path ->
                if (path != null) {
                    mqtt?.publishEvent("record", JSONObject().put("path", path).put("status", "stopped"))
                }
            }
            val enc = H264Encoder(encW, encH, StreamConfig.FPS, bitrateFor(encW, encH), encoderListener)
            encoder = enc
            enc.start()
            streaming = true
            postMessage("推流中")
            Log.i(TAG, "camera opened: ${encW}x$encH rotation=$rotation")
        }
    }

    private val encoderListener = object : H264Encoder.Listener {
        override fun onSpsPps(sps: ByteArray, pps: ByteArray) {
            storedSps = sps
            storedPps = pps
            recorder?.onSpsPps(sps, pps)
        }

        override fun onFrame(frame: ByteArray, keyFrame: Boolean, ptsUs: Long) {
            recorder?.onFrame(frame, keyFrame, ptsUs)
            for (c in clients) c.send(frame, keyFrame, ptsUs)
        }
    }

    private fun dispatchFrame(image: Image) {
        photo?.maybeCapture(image, currentRotation)
        val enc = encoder
        if (enc != null && streaming) {
            enc.offerFrame(image, currentRotation)
        }
        motion?.process(image)
    }

    private fun closeCameraEncoder() {
        try {
            encoder?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "encoder stop", e)
        }
        encoder = null
        controller?.close()
        controller = null
        storedSps = null
        storedPps = null
        encW = 0
        encH = 0
    }

    private fun bitrateFor(w: Int, h: Int): Int = when {
        w * h >= 1920 * 1080 -> 8_000_000
        w * h >= 1280 * 720 -> 4_000_000
        else -> 2_000_000
    }

    private fun rtspUrl(): String {
        val ip = StatusReporter.localIp()
        return if (ip != null) "rtsp://$ip:${config.rtspPort}/live" else ""
    }

    private fun postMessage(msg: String) {
        uiListener?.onUiState(currentState().copy(message = msg))
    }

    private fun notifyUi() {
        uiListener?.onUiState(currentState())
    }

    private fun startForegroundCompat() {
        val channelId = "stream"
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(channelId, "推流服务", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = Notification.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("网络摄像机")
            .setContentText("推流服务运行中")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTI_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(NOTI_ID, notification)
        }
    }

    // ---------- StreamControl（RTSP 服务器回调） ----------

    override fun streamActive(): Boolean = streaming && storedSps != null

    override fun sps(): ByteArray? = storedSps

    override fun pps(): ByteArray? = storedPps

    override fun videoInfo(): Triple<Int, Int, Int> = Triple(encW, encH, StreamConfig.FPS)

    override fun addClient(client: ClientSink) {
        clients.add(client)
        Log.i(TAG, "client added, total=${clients.size}")
    }

    override fun removeClient(client: ClientSink) {
        clients.remove(client)
        Log.i(TAG, "client removed, total=${clients.size}")
    }

    override fun requestKeyFrame() {
        encoder?.requestKeyFrame()
    }

    companion object {
        private const val TAG = "CameraStreamService"
        private const val NOTI_ID = 1
    }
}
