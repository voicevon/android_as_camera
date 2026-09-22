package com.von.cameraapp

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.Surface
import android.view.TextureView
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import com.von.cameraapp.service.CameraStreamService
import com.von.cameraapp.ui.CameraViewModel

/**
 * 主界面：预览 + 本地控制（推流开关、切换摄像头、拍照、录像、移动侦测开关）。
 * 服务通过 Binder 绑定，状态经 ViewModel 观察刷新。
 */
class MainActivity : Activity() {

    private val vm = CameraViewModel()
    private var service: CameraStreamService? = null
    private var bound = false

    private lateinit var preview: TextureView
    private lateinit var tvStatus: TextView
    private lateinit var btnStream: Button
    private lateinit var btnSwitch: Button
    private lateinit var btnCapture: Button
    private lateinit var btnRecord: Button
    private lateinit var swMotion: Switch

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val s = (binder as CameraStreamService.LocalBinder).service
            service = s
            s.uiListener = CameraStreamService.UiListener { state -> vm.onState(state) }
            preview.surfaceTexture?.let { s.setPreviewSurface(Surface(it)) }
            vm.onState(s.currentState())
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service?.uiListener = null
            service = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        preview = findViewById(R.id.preview)
        tvStatus = findViewById(R.id.tvStatus)
        btnStream = findViewById(R.id.btnStream)
        btnSwitch = findViewById(R.id.btnSwitch)
        btnCapture = findViewById(R.id.btnCapture)
        btnRecord = findViewById(R.id.btnRecord)
        swMotion = findViewById(R.id.swMotion)

        requestNeededPermissions()

        btnStream.setOnClickListener {
            val s = service ?: return@setOnClickListener
            if (s.streaming) {
                s.stopStream()
            } else {
                val st = preview.surfaceTexture
                s.startStream(if (st != null) Surface(st) else null)
            }
        }
        btnSwitch.setOnClickListener { service?.switchCamera() }
        btnCapture.setOnClickListener { service?.capture() }
        btnRecord.setOnClickListener {
            val s = service ?: return@setOnClickListener
            if (s.currentState().recording) s.stopRecord() else s.startRecord()
        }
        swMotion.setOnCheckedChangeListener { _, checked -> service?.setMotion(checked, -1) }

        preview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                service?.setPreviewSurface(Surface(st))
            }

            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                service?.setPreviewSurface(null)
                return true
            }

            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }

        vm.observe { render(it) }
    }

    override fun onStart() {
        super.onStart()
        val intent = Intent(this, CameraStreamService::class.java)
        // startService 让服务成为 started service：Activity 退后台解绑后仍常驻，MQTT 保持在线
        // Android 12+ 在极端时序下可能拒绝后台启动，忽略并退回仅绑定模式
        try {
            startService(intent)
        } catch (e: Exception) {
            android.util.Log.w("MainActivity", "startService denied: ${e.message}")
        }
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
        bound = true
    }

    override fun onStop() {
        if (bound) {
            unbindService(connection)
            bound = false
        }
        service?.uiListener = null
        service = null
        super.onStop()
    }

    private fun render(state: CameraStreamService.UiState) {
        btnStream.text = if (state.streaming) "停止推流" else "开始推流"
        btnRecord.text = if (state.recording) "停止录像" else "录像"
        if (swMotion.isChecked != state.motionEnabled) swMotion.isChecked = state.motionEnabled
        val mqtt = if (state.mqttConnected) "MQTT已连接" else "MQTT未连接"
        val parts = buildString {
            append(mqtt)
            if (state.rtspUrl.isNotEmpty()) append("  ").append(state.rtspUrl)
            if (state.camera.isNotEmpty()) append("  ").append(state.camera)
            if (state.message.isNotEmpty()) append("\n").append(state.message)
        }
        tvStatus.text = parts
    }

    private fun requestNeededPermissions() {
        val perms = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT <= 28) perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        val denied = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (denied.isNotEmpty()) {
            requestPermissions(denied.toTypedArray(), REQ_PERM)
        }
    }

    companion object {
        private const val REQ_PERM = 1
    }
}
