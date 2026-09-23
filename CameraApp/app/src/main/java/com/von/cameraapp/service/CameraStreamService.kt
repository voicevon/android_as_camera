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
import android.os.SystemClock
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
        val mqttConnected: Boolean = false,
        val rtspUrl: String = "",
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
    /** 用户意图：已请求推流（streaming 仅在相机成功打开后为 true） */
    @Volatile private var wantStream = false
    /** 相机打开失败重试计数 */
    private var openRetries = 0
    @Volatile private var mqttConnected = false
    @Volatile private var previewSurface: Surface? = null
    @Volatile private var facingBack: Boolean = false
    @Volatile private var currentRotation = 0
    @Volatile private var encW = 0
    @Volatile private var encH = 0

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 最近一次"有生命迹象"时间（帧到达或相机打开请求）；看门狗据此检测静默卡死 */
    @Volatile private var lastAliveAt = 0L

    /** 帧看门狗：推流意图存在但长时间无帧（HAL 静默卡死 / 打开无回调）时自动重开相机 */
    private val frameWatchdog = object : Runnable {
        override fun run() {
            if (!wantStream) return
            val idle = SystemClock.elapsedRealtime() - lastAliveAt
            if (idle > STALE_FRAME_MS) {
                Log.w(TAG, "no camera frames for ${idle}ms (streaming=$streaming), reopen camera")
                openCameraAndEncoder()
            }
            mainHandler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        config = StreamConfig(this)
        facingBack = !config.frontCamera

        mqtt = MqttControl(config, object : MqttControl.Listener {
            override fun onCommand(action: String, params: JSONObject) {
                mainHandler.post { handleCommand(action, params) }
            }

            override fun onMqttConnected(connected: Boolean) {
                // MQTT 回调在其内部线程，切到主线程再更新 UI 状态
                mainHandler.post {
                    mqttConnected = connected
                    notifyUi()
                }
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
        // 远程命令传入 null 时不清除本地已有预览面，否则本地画面会停在最后一帧
        if (preview != null) previewSurface = preview
        startForegroundCompat()
        wantStream = true
        if (rtspServer == null) rtspServer = RtspServer(config.rtspPort, this)
        rtspServer?.start()
        postMessage("正在打开摄像头…")
        mainHandler.removeCallbacks(frameWatchdog)
        mainHandler.postDelayed(frameWatchdog, WATCHDOG_INTERVAL_MS)
        openCameraAndEncoder()
    }

    fun stopStream() {
        wantStream = false
        openRetries = 0
        streaming = false
        mainHandler.removeCallbacks(frameWatchdog)
        closeCameraEncoder()
        clients.forEach { it.shutdown() }
        clients.clear()
        stopForeground(STOP_FOREGROUND_REMOVE)
        notifyUi()
        Log.i(TAG, "stream stopped")
    }

    /**
     * 切换摄像头。targetBack 为命令明确指定的目标（front/back）；
     * null 时退化为取反切换（兼容不带参数的旧命令）。
     * 目标与当前相同时为无操作，避免重复开关相机导致推流反复中断。
     */
    fun switchCamera(targetBack: Boolean? = null) {
        if (!streaming) {
            postMessage("未在推流")
            return
        }
        val next = targetBack ?: !facingBack
        if (next == facingBack) return
        facingBack = next
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

    /** 设置保存后调用：重启 MQTT 与 RTSP 服务器，使新网络参数立即生效 */
    fun applyNetworkSettings() {
        mqtt?.let {
            it.stop()
            it.start()
        }
        rtspServer?.stop()
        rtspServer = null
        if (streaming) {
            val preview = previewSurface
            stopStream()
            startStream(preview)
        }
        notifyUi()
        Log.i(TAG, "network settings applied")
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
        mqttConnected = mqttConnected,
        rtspUrl = rtspUrl(),
        message = ""
    )

    // ---------- 内部实现 ----------

    private fun handleCommand(action: String, params: JSONObject) {
        Log.i(TAG, "mqtt command: $action $params")
        postMessage("MQTT: $action $params")
        when (action) {
            "start_stream" -> startStream(null)
            "stop_stream" -> stopStream()
            "switch_camera" -> switchCamera(
                when (params.optString("camera")) {
                    "back" -> true
                    "front" -> false
                    else -> null
                }
            )
            "zoom" -> setZoom(params.optDouble("ratio", 1.0).toFloat())
            "capture" -> capture()
            "start_record" -> startRecord()
            "stop_record" -> stopRecord()
            "motion" -> setMotion(params.optBoolean("enabled", motion?.enabled ?: false), params.optInt("sensitivity", -1))
            else -> mqtt?.publishEvent("error", JSONObject().put("msg", "未知命令: $action"))
        }
    }

    private fun openCameraAndEncoder() {
        lastAliveAt = SystemClock.elapsedRealtime()
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
            Log.w(TAG, "camera onError: $msg")
            if (streaming) {
                streaming = false
                notifyUi()
            }
            // 相机关闭后立即重开偶发失败（HAL 未完全释放），受控重试
            if (wantStream && openRetries < MAX_OPEN_RETRIES) {
                openRetries++
                postMessage("摄像头打开失败，重试($openRetries/$MAX_OPEN_RETRIES)…")
                mainHandler.postDelayed({ if (wantStream) openCameraAndEncoder() }, RETRY_DELAY_MS)
            } else {
                postMessage(msg)
            }
        }

        override fun onOpened(facing: Int, rotation: Int, width: Int, height: Int) {
            openRetries = 0
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
            Log.i(TAG, "camera opened: ${encW}x$encH rotation=$rotation")
            notifyUi()
        }
    }

    private val encoderListener = object : H264Encoder.Listener {
        override fun onSpsPps(sps: ByteArray, pps: ByteArray) {
            storedSps = sps
            storedPps = pps
            Log.i(TAG, "sps/pps received: ${sps.size}/${pps.size} bytes")
            recorder?.onSpsPps(sps, pps)
        }

        override fun onFrame(frame: ByteArray, keyFrame: Boolean, ptsUs: Long) {
            recorder?.onFrame(frame, keyFrame, ptsUs)
            for (c in clients) c.send(frame, keyFrame, ptsUs)
        }
    }

    private fun dispatchFrame(image: Image) {
        lastAliveAt = SystemClock.elapsedRealtime()
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
        // 可能从相机线程等回调线程调用，统一切主线程再通知 UI
        mainHandler.post {
            uiListener?.onUiState(currentState().copy(message = msg))
        }
    }

    private fun notifyUi() {
        mainHandler.post {
            uiListener?.onUiState(currentState())
        }
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
        private const val MAX_OPEN_RETRIES = 3
        private const val RETRY_DELAY_MS = 500L
        private const val WATCHDOG_INTERVAL_MS = 2000L
        private const val STALE_FRAME_MS = 5000L
    }
}
