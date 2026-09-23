package com.von.cameraapp

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Menu
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.Button
import android.widget.PopupMenu
import android.widget.TextView
import com.von.cameraapp.config.StreamConfig
import com.von.cameraapp.service.CameraStreamService
import com.von.cameraapp.ui.CameraViewModel
import com.von.cameraapp.ui.SettingsDialog

/**
 * 主界面：预览 + 状态指示（MQTT / 推流，红绿双色）。
 * 推流启停、切摄像头、拍照、录像、移动侦测全部由远程 MQTT 控制；
 * 本地仅保留右上角 "⋮" 设置入口；应用启动后自动开始推流。
 */
class MainActivity : Activity() {

    private val vm = CameraViewModel()
    private var service: CameraStreamService? = null
    private var bound = false

    /** 本次进程是否已自动启动推流（回前台时不覆盖远程的停止操作） */
    private var autoStarted = false

    private val msgHandler = Handler(Looper.getMainLooper())
    private var hideMsgRunnable: Runnable? = null

    private lateinit var preview: TextureView
    private lateinit var tvMqtt: TextView
    private lateinit var tvStream: TextView
    private lateinit var tvMessage: TextView
    private lateinit var btnMenu: Button

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val s = (binder as CameraStreamService.LocalBinder).service
            service = s
            s.uiListener = CameraStreamService.UiListener { state -> vm.onState(state) }
            preview.surfaceTexture?.let { s.setPreviewSurface(Surface(it)) }
            vm.onState(s.currentState())
            autoStartIfNeeded()
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
        tvMqtt = findViewById(R.id.tvMqtt)
        tvStream = findViewById(R.id.tvStream)
        tvMessage = findViewById(R.id.tvMessage)
        btnMenu = findViewById(R.id.btnMenu)

        requestNeededPermissions()

        btnMenu.setOnClickListener { anchor -> showMenu(anchor) }

        preview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                service?.setPreviewSurface(Surface(st))
                applyPreviewMatrix()
            }

            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
                applyPreviewMatrix()
            }

            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                service?.setPreviewSurface(null)
                return true
            }

            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
        // 布局变化（旋转/首帧）时保持画面宽高比
        preview.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyPreviewMatrix() }

        vm.observe { render(it) }
    }

    /**
     * 等比缩放预览（信箱模式），避免拉伸变形。
     * 相机缓冲为横向分辨率（宽>高），竖屏显示内容的宽高比 = 高:宽。
     */
    private fun applyPreviewMatrix() {
        if (!preview.isAvailable || preview.width == 0 || preview.height == 0) return
        val (cw, ch) = StreamConfig(this).resolution()
        val bufW = ch.toFloat()
        val bufH = cw.toFloat()
        val vw = preview.width.toFloat()
        val vh = preview.height.toFloat()
        val scale = minOf(vw / bufW, vh / bufH)
        val matrix = Matrix().apply {
            setScale(scale, scale)
            postTranslate((vw - bufW * scale) / 2f, (vh - bufH * scale) / 2f)
        }
        preview.setTransform(matrix)
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

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERM && grantResults.isNotEmpty() &&
            grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        ) {
            autoStartIfNeeded()
        }
    }

    /** 自动开始推流：每次进程仅一次；相机权限未就绪时等授权回调再启动 */
    private fun autoStartIfNeeded() {
        val s = service ?: return
        if (autoStarted || s.streaming) return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        autoStarted = true
        val st = preview.surfaceTexture
        s.startStream(if (st != null) Surface(st) else null)
    }

    private fun render(state: CameraStreamService.UiState) {
        tvMqtt.text = if (state.mqttConnected) "● MQTT已连接" else "● MQTT未连接"
        tvMqtt.setTextColor(if (state.mqttConnected) COLOR_OK else COLOR_BAD)
        tvStream.text = if (state.streaming) "● 推流中" else "● 未推流"
        tvStream.setTextColor(if (state.streaming) COLOR_OK else COLOR_BAD)
        if (state.message.isEmpty()) {
            tvMessage.visibility = View.GONE
        } else {
            tvMessage.text = state.message
            tvMessage.visibility = View.VISIBLE
            // 提示消息 4 秒后自动隐藏
            hideMsgRunnable?.let { msgHandler.removeCallbacks(it) }
            hideMsgRunnable = Runnable { tvMessage.visibility = View.GONE }.also {
                msgHandler.postDelayed(it, MSG_AUTO_HIDE_MS)
            }
        }
    }

    /** 右上角 "⋮" 菜单 */
    private fun showMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(Menu.NONE, IDM_SETTINGS, Menu.NONE, "设置")
        popup.setOnMenuItemClickListener { item ->
            if (item.itemId == IDM_SETTINGS) {
                showSettings()
                true
            } else {
                false
            }
        }
        popup.show()
    }

    /** 打开设置弹窗，保存后：移动侦测立即生效，网络参数立即生效 */
    private fun showSettings() {
        val s = service
        val url = s?.currentState()?.rtspUrl ?: ""
        SettingsDialog(this, StreamConfig(this), url) { motionOn ->
            s?.setMotion(motionOn, -1)
            s?.applyNetworkSettings()
        }.show()
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
        private const val IDM_SETTINGS = 1
        private const val MSG_AUTO_HIDE_MS = 6000L
        private val COLOR_OK = Color.parseColor("#4CAF50")
        private val COLOR_BAD = Color.parseColor("#F44336")
    }
}
