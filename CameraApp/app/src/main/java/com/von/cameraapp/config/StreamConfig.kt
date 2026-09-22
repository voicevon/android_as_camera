package com.von.cameraapp.config

import android.content.Context
import android.provider.Settings

/**
 * 全局配置：分辨率、端口、MQTT 参数、移动侦测灵敏度。
 * SharedPreferences 持久化，UI 与各模块共用。
 */
class StreamConfig(context: Context) {

    private val prefs = context.getSharedPreferences("stream_config", Context.MODE_PRIVATE)

    val deviceId: String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"

    val cmdTopic get() = "camera/$deviceId/cmd"
    val stateTopic get() = "camera/$deviceId/state"
    val eventTopic get() = "camera/$deviceId/event"

    companion object {
        /** 可选推流分辨率 */
        val RESOLUTIONS = listOf(640 to 480, 1280 to 720, 1920 to 1080)
        const val DEFAULT_RTSP_PORT = 8554
        const val FPS = 30
    }

    var resolutionIndex: Int
        get() = prefs.getInt("resolution_index", 1)
        set(v) = prefs.edit().putInt("resolution_index", v.coerceIn(0, RESOLUTIONS.lastIndex)).apply()

    fun resolution(): Pair<Int, Int> = RESOLUTIONS[resolutionIndex]

    var rtspPort: Int
        get() = prefs.getInt("rtsp_port", DEFAULT_RTSP_PORT)
        set(v) = prefs.edit().putInt("rtsp_port", v).apply()

    var mqttHost: String
        get() = prefs.getString("mqtt_host", "voicevon.vicp.io")!!
        set(v) = prefs.edit().putString("mqtt_host", v).apply()

    var mqttPort: Int
        get() = prefs.getInt("mqtt_port", 1883)
        set(v) = prefs.edit().putInt("mqtt_port", v).apply()

    var mqttUser: String
        get() = prefs.getString("mqtt_user", "von")!!
        set(v) = prefs.edit().putString("mqtt_user", v).apply()

    var mqttPassword: String
        get() = prefs.getString("mqtt_pass", "von123456-")!!
        set(v) = prefs.edit().putString("mqtt_pass", v).apply()

    /** 移动侦测灵敏度 1~5，5 最灵敏 */
    var motionSensitivity: Int
        get() = prefs.getInt("motion_sensitivity", 3)
        set(v) = prefs.edit().putInt("motion_sensitivity", v.coerceIn(1, 5)).apply()

    var motionEnabled: Boolean
        get() = prefs.getBoolean("motion_enabled", false)
        set(v) = prefs.edit().putBoolean("motion_enabled", v).apply()

    var frontCamera: Boolean
        get() = prefs.getBoolean("front_camera", false)
        set(v) = prefs.edit().putBoolean("front_camera", v).apply()
}
