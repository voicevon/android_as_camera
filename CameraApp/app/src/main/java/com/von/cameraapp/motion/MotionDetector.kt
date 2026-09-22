package com.von.cameraapp.motion

import android.graphics.ImageFormat
import android.media.Image
import android.os.SystemClock
import android.util.Log
import com.von.cameraapp.config.StreamConfig
import kotlin.math.abs

/**
 * 移动侦测：对 YUV 帧的 Y 平面做分块亮度差分。
 * 变化块数超阈值且冷却时间已过时触发回调（回调在相机帧线程）。
 */
class MotionDetector(
    private val config: StreamConfig,
    private val listener: (Float) -> Unit
) {
    @Volatile var enabled: Boolean = config.motionEnabled

    private var prev: IntArray? = null
    private var lastEvent = 0L

    fun process(image: Image) {
        if (!enabled) return
        if (image.format != ImageFormat.YUV_420_888) return
        try {
            val plane = image.planes[0]
            val w = image.width
            val h = image.height
            val buf = plane.buffer
            val rowStride = plane.rowStride
            val pixStride = plane.pixelStride

            val cur = IntArray(GRID_W * GRID_H)
            for (gy in 0 until GRID_H) {
                for (gx in 0 until GRID_W) {
                    val x0 = gx * w / GRID_W
                    val y0 = gy * h / GRID_H
                    val x1 = (gx + 1) * w / GRID_W
                    val y1 = (gy + 1) * h / GRID_H
                    val stepX = maxOf(1, (x1 - x0) / 8)
                    val stepY = maxOf(1, (y1 - y0) / 8)
                    var sum = 0L
                    var cnt = 0L
                    var y = y0
                    while (y < y1) {
                        val off = y * rowStride
                        var x = x0
                        while (x < x1) {
                            sum += buf.get(off + x * pixStride).toInt() and 0xFF
                            cnt++
                            x += stepX
                        }
                        y += stepY
                    }
                    cur[gy * GRID_W + gx] = if (cnt > 0) (sum / cnt).toInt() else 0
                }
            }

            val prevArr = prev
            prev = cur
            if (prevArr == null || prevArr.size != cur.size) return

            var changed = 0
            val pixelThresh = pixelThreshold()
            for (i in cur.indices) {
                if (abs(cur[i] - prevArr[i]) > pixelThresh) changed++
            }
            val now = SystemClock.elapsedRealtime()
            if (changed >= changedThreshold() && now - lastEvent > COOLDOWN_MS) {
                lastEvent = now
                listener(changed * 100f / cur.size)
            }
        } catch (e: Exception) {
            Log.w(TAG, "process", e)
        }
    }

    /** 灵敏度 1~5 映射到像素差阈值，越小越灵敏 */
    private fun pixelThreshold(): Int = when (config.motionSensitivity) {
        1 -> 60
        2 -> 45
        3 -> 32
        4 -> 22
        else -> 15
    }

    /** 触发所需的最小变化块数 */
    private fun changedThreshold(): Int =
        GRID_W * GRID_H * when (config.motionSensitivity) {
            1 -> 5
            2 -> 4
            3 -> 3
            4 -> 2
            else -> 1
        } / 100

    companion object {
        private const val TAG = "MotionDetector"
        private const val GRID_W = 16
        private const val GRID_H = 12
        private const val COOLDOWN_MS = 5000L
    }
}
