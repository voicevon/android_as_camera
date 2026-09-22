package com.von.cameraapp.mqtt

import android.util.Log
import com.von.cameraapp.config.StreamConfig
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * MQTT 控制：连接/自动重连 Broker；订阅 cmd 命令并分发；发布 state/event。
 * state 消息 retain，观看端上线即可获取最近状态。
 */
class MqttControl(
    private val config: StreamConfig,
    private val listener: Listener
) : MqttCallbackExtended {

    interface Listener {
        /** 收到控制命令（在 MQTT 线程回调，调用方自行切线程） */
        fun onCommand(action: String, params: JSONObject)

        fun onMqttConnected(connected: Boolean)
    }

    private var client: MqttClient? = null
    @Volatile private var started = false
    private var executor: ScheduledExecutorService? = null

    fun start() {
        if (started) return
        started = true
        executor = Executors.newSingleThreadScheduledExecutor()
        scheduleConnect(0)
    }

    fun stop() {
        started = false
        executor?.shutdownNow()
        executor = null
        try {
            client?.disconnect(0)
        } catch (_: Exception) {
        }
        try {
            client?.close()
        } catch (_: Exception) {
        }
        client = null
    }

    private fun scheduleConnect(delayMs: Long) {
        if (!started) return
        executor?.schedule({ connect() }, delayMs, TimeUnit.MILLISECONDS)
    }

    private fun connect() {
        if (!started) return
        try {
            val uri = "tcp://${config.mqttHost}:${config.mqttPort}"
            val c = MqttClient(uri, "cam_${config.deviceId}", MemoryPersistence())
            val opts = MqttConnectOptions().apply {
                userName = config.mqttUser
                password = config.mqttPassword.toCharArray()
                isCleanSession = true
                isAutomaticReconnect = true
                keepAliveInterval = 30
                connectionTimeout = 10
            }
            c.setCallback(this)
            client = c
            c.connect(opts)
            Log.i(TAG, "mqtt connected: $uri")
        } catch (e: Exception) {
            Log.w(TAG, "mqtt connect failed: ${e.message}")
            listener.onMqttConnected(false)
            scheduleConnect(5000)
        }
    }

    override fun connectComplete(reconnect: Boolean, serverURI: String?) {
        try {
            client?.subscribe(config.cmdTopic, 1)
        } catch (e: Exception) {
            Log.w(TAG, "subscribe failed", e)
        }
        listener.onMqttConnected(true)
    }

    override fun connectionLost(cause: Throwable?) {
        // automaticReconnect=true 时由 Paho 自动重连，成功后回调 connectComplete
        listener.onMqttConnected(false)
    }

    override fun messageArrived(topic: String?, message: MqttMessage?) {
        try {
            val payload = message?.payload?.decodeToString() ?: return
            val json = JSONObject(payload)
            val action = json.optString("action")
            if (action.isNotEmpty()) listener.onCommand(action, json)
        } catch (e: Exception) {
            Log.w(TAG, "bad command payload", e)
        }
    }

    override fun deliveryComplete(token: IMqttDeliveryToken?) {
    }

    fun publishState(json: JSONObject) = publish(config.stateTopic, json.toString(), true)

    fun publishEvent(type: String, payload: JSONObject = JSONObject()) {
        payload.put("type", type)
        publish(config.eventTopic, payload.toString(), false)
    }

    private fun publish(topic: String, text: String, retain: Boolean) {
        val c = client ?: return
        try {
            val m = MqttMessage(text.toByteArray())
            m.qos = 1
            m.isRetained = retain
            c.publish(topic, m)
        } catch (e: Exception) {
            Log.w(TAG, "publish failed: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "MqttControl"
    }
}
