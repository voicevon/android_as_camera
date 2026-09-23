package com.von.cameraapp.ui

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.von.cameraapp.config.StreamConfig

/**
 * 设置弹窗：查看/修改网络与侦测配置。
 * 包含 RTSP 地址（IP 自动获取，只读展示）与端口、MQTT 连接参数、分辨率、
 * 移动侦测开关与灵敏度。
 * 保存写入 StreamConfig（SharedPreferences），并经回调触发设置立即生效。
 */
class SettingsDialog(
    private val context: Context,
    private val config: StreamConfig,
    private val currentRtspUrl: String,
    private val onSaved: (motionEnabled: Boolean) -> Unit
) {

    private lateinit var etRtspPort: EditText
    private lateinit var etMqttHost: EditText
    private lateinit var etMqttPort: EditText
    private lateinit var etMqttUser: EditText
    private lateinit var etMqttPass: EditText
    private lateinit var swMotion: Switch
    private lateinit var spResolution: Spinner
    private lateinit var spSensitivity: Spinner

    fun show() {
        val scroll = ScrollView(context)
        scroll.addView(buildForm())
        AlertDialog.Builder(context)
            .setTitle("设置")
            .setView(scroll)
            .setPositiveButton("保存") { _, _ -> save() }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---------- 表单构建 ----------

    private fun buildForm(): LinearLayout {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), 0)
        }

        box.addView(label("RTSP 地址"))
        box.addView(hint(currentRtspUrl.ifEmpty { "（无网络连接）" }))
        etRtspPort = editNum(config.rtspPort.toString())
        box.addView(etRtspPort)

        box.addView(label("MQTT 主机"))
        etMqttHost = edit("如 voicevon.vicp.io", config.mqttHost)
        box.addView(etMqttHost)
        box.addView(label("MQTT 端口"))
        etMqttPort = editNum(config.mqttPort.toString())
        box.addView(etMqttPort)
        box.addView(label("MQTT 用户名"))
        etMqttUser = edit("", config.mqttUser)
        box.addView(etMqttUser)
        box.addView(label("MQTT 密码"))
        etMqttPass = edit("", config.mqttPassword)
        box.addView(etMqttPass)

        box.addView(label("分辨率"))
        val resLabels = StreamConfig.RESOLUTIONS.map { "${it.first}x${it.second}" }
        spResolution = spinner(resLabels, config.resolutionIndex)
        box.addView(spResolution)

        box.addView(label("移动侦测"))
        val motionRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        motionRow.addView(TextView(context).apply {
            text = "启用移动侦测"
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        swMotion = Switch(context)
        swMotion.isChecked = config.motionEnabled
        motionRow.addView(swMotion)
        box.addView(motionRow)

        box.addView(label("移动侦测灵敏度"))
        val senLabels = (1..5).map {
            when (it) {
                1 -> "$it（最不灵敏）"
                5 -> "$it（最灵敏）"
                else -> "$it"
            }
        }
        spSensitivity = spinner(senLabels, config.motionSensitivity - 1)
        box.addView(spSensitivity)

        return box
    }

    private fun save() {
        val rtspPort = etRtspPort.text.toString().toIntOrNull()
        val mqttPort = etMqttPort.text.toString().toIntOrNull()
        if (rtspPort == null || rtspPort !in 1024..65535 || mqttPort == null || mqttPort !in 1024..65535) {
            Toast.makeText(context, "端口无效（1024~65535），未保存", Toast.LENGTH_SHORT).show()
            return
        }
        config.rtspPort = rtspPort
        config.mqttPort = mqttPort
        val host = etMqttHost.text.toString().trim()
        if (host.isNotEmpty()) config.mqttHost = host
        val user = etMqttUser.text.toString().trim()
        if (user.isNotEmpty()) config.mqttUser = user
        config.mqttPassword = etMqttPass.text.toString()
        config.resolutionIndex = spResolution.selectedItemPosition
        config.motionSensitivity = spSensitivity.selectedItemPosition + 1
        config.motionEnabled = swMotion.isChecked
        onSaved(swMotion.isChecked)
        Toast.makeText(context, "设置已保存", Toast.LENGTH_SHORT).show()
    }

    // ---------- 控件辅助 ----------

    private fun label(text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 14f
        setPadding(0, dp(12), 0, dp(4))
    }

    private fun hint(text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 12f
        setPadding(0, 0, 0, dp(4))
    }

    private fun edit(hintText: String, value: String): EditText = EditText(context).apply {
        this.hint = hintText
        setText(value)
        setSingleLine(true)
    }

    private fun editNum(value: String): EditText = edit("", value).apply {
        inputType = InputType.TYPE_CLASS_NUMBER
    }

    private fun spinner(items: List<String>, selection: Int): Spinner = Spinner(context).apply {
        adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, items)
        setSelection(selection)
    }

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()
}
