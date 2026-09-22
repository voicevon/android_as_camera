package com.von.cameraapp.mqtt

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * 周期状态上报：每 5 秒通过 MQTT 发布设备状态（在线、推流、IP、电量等）。
 * 状态内容由 provider 闭包在每次上报时实时采集。
 */
class StatusReporter(
    private val context: Context,
    private val mqtt: MqttControl,
    private val provider: () -> JSONObject
) {

    private var executor: ScheduledExecutorService? = null

    fun start(intervalSeconds: Long = 5) {
        if (executor != null) return
        executor = Executors.newSingleThreadScheduledExecutor().also {
            it.scheduleWithFixedDelay({ tick() }, 0, intervalSeconds, TimeUnit.SECONDS)
        }
    }

    fun stop() {
        executor?.shutdownNow()
        executor = null
    }

    private fun tick() {
        try {
            val json = provider()
            json.put("battery", batteryLevel())
            json.put("ip", localIp() ?: "")
            mqtt.publishState(json)
        } catch (e: Exception) {
            Log.w(TAG, "tick", e)
        }
    }

    private fun batteryLevel(): Int {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level >= 0 && scale > 0) level * 100 / scale else -1
    }

    companion object {
        private const val TAG = "StatusReporter"

        /** 取本机局域网 IPv4 地址 */
        fun localIp(): String? {
            try {
                val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
                while (interfaces.hasMoreElements()) {
                    val n = interfaces.nextElement()
                    val addresses = java.util.Collections.list(n.inetAddresses)
                    for (addr in addresses) {
                        if (!addr.isLoopbackAddress && addr is Inet4Address && addr.isSiteLocalAddress) {
                            return addr.hostAddress
                        }
                    }
                }
            } catch (_: Exception) {
            }
            return null
        }
    }
}
