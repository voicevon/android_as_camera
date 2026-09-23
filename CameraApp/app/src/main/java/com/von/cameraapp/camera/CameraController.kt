package com.von.cameraapp.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import kotlin.math.abs

/**
 * Camera2 封装：打开/关闭相机、前后摄切换、变焦、预览输出。
 * YUV 帧通过 onFrame 回调分发（回调线程为相机线程，控制器负责 close）。
 */
class CameraController(
    context: Context,
    private val desiredSize: Size,
    private val listener: Listener
) {
    interface Listener {
        /** ImageReader 回调线程，Image 处理完后由控制器关闭 */
        fun onFrame(image: Image)

        fun onError(msg: String)

        /** 相机会话就绪，rotation 为传感器方向（编码旋转角） */
        fun onOpened(facing: Int, rotation: Int, width: Int, height: Int)
    }

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var cameraDevice: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var requestBuilder: CaptureRequest.Builder? = null
    private var previewSurface: Surface? = null
    private var zoom = 1f
    @Volatile private var closed = false
    private var retryWithoutPreview = false

    @Volatile var facing: Int = CameraCharacteristics.LENS_FACING_BACK
        private set
    @Volatile var rotation: Int = 0
        private set
    @Volatile var frameWidth: Int = 0
        private set
    @Volatile var frameHeight: Int = 0
        private set

    private val thread = HandlerThread("camera-thread").apply { start() }
    private val handler = Handler(thread.looper)

    fun open(targetFacing: Int, preview: Surface?) {
        closed = false
        previewSurface = preview
        facing = targetFacing
        try {
            val id = findCamera(targetFacing)
            if (id == null) {
                listener.onError("未找到可用摄像头")
                return
            }
            val chars = cameraManager.getCameraCharacteristics(id)
            rotation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            val size = pickSize(chars)
            frameWidth = size.width
            frameHeight = size.height
            openCamera(id, size, preview)
        } catch (e: Exception) {
            Log.w(TAG, "open exception: ${e.message}")
            listener.onError("打开摄像头异常: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun openCamera(id: String, size: Size, preview: Surface?) {
        cameraManager.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                if (closed) {
                    device.close()
                    return
                }
                cameraDevice = device
                createSession(device, size, preview)
            }

            override fun onDisconnected(device: CameraDevice) {
                device.close()
            }

            override fun onError(device: CameraDevice, error: Int) {
                Log.w(TAG, "openCamera failed, error=$error")
                listener.onError("打开摄像头失败，错误码 $error")
            }
        }, handler)
    }

    private fun createSession(device: CameraDevice, size: Size, preview: Surface?) {
        val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 4)
        reader.setOnImageAvailableListener({ r ->
            val img = try {
                r.acquireLatestImage()
            } catch (e: Exception) {
                null
            }
            if (img != null) {
                try {
                    listener.onFrame(img)
                } catch (e: Exception) {
                    Log.w(TAG, "onFrame", e)
                } finally {
                    img.close()
                }
            }
        }, handler)
        imageReader = reader

        val outputs = listOfNotNull(preview, reader.surface)
        @Suppress("DEPRECATION")
        device.createCaptureSession(outputs, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                if (closed) {
                    s.close()
                    return
                }
                session = s
                try {
                    val b = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                    outputs.forEach { b.addTarget(it) }
                    b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                    b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                    applyZoom(b, zoom)
                    requestBuilder = b
                    s.setRepeatingRequest(b.build(), null, handler)
                    listener.onOpened(facing, rotation, size.width, size.height)
                } catch (e: Exception) {
                    Log.w(TAG, "setRepeatingRequest failed: ${e.message}")
                    listener.onError("启动采集失败: ${e.message}")
                }
            }

            override fun onConfigureFailed(s: CameraCaptureSession) {
                Log.w(TAG, "session configure failed")
                // 预览+YUV 组合可能超出设备能力，降级为仅 YUV 采集
                if (!retryWithoutPreview && preview != null) {
                    retryWithoutPreview = true
                    createSession(device, size, null)
                } else {
                    listener.onError("相机会话配置失败")
                }
            }
        }, handler)
    }

    /** 切换前后摄像头并重建会话 */
    fun switchCamera() {
        val next =
            if (facing == CameraCharacteristics.LENS_FACING_BACK) CameraCharacteristics.LENS_FACING_FRONT
            else CameraCharacteristics.LENS_FACING_BACK
        closeDevice()
        open(next, previewSurface)
    }

    fun setZoom(ratio: Float) {
        zoom = ratio
        val b = requestBuilder ?: return
        applyZoom(b, ratio)
        try {
            session?.setRepeatingRequest(b.build(), null, handler)
        } catch (e: Exception) {
            Log.w(TAG, "setZoom", e)
        }
    }

    private fun applyZoom(b: CaptureRequest.Builder, ratio: Float) {
        val id = cameraDevice?.id ?: return
        val chars = try {
            cameraManager.getCameraCharacteristics(id)
        } catch (e: Exception) {
            return
        }
        if (Build.VERSION.SDK_INT >= 30) {
            val range = chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            if (range != null) {
                b.set(CaptureRequest.CONTROL_ZOOM_RATIO, ratio.coerceIn(range.lower, range.upper))
                return
            }
        }
        val maxZoom = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: return
        val array = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
        val z = ratio.coerceIn(1f, maxZoom)
        val w = (array.width() / z).toInt()
        val h = (array.height() / z).toInt()
        val dx = (array.width() - w) / 2
        val dy = (array.height() - h) / 2
        b.set(CaptureRequest.SCALER_CROP_REGION, Rect(dx, dy, dx + w, dy + h))
    }

    private fun findCamera(target: Int): String? =
        cameraManager.cameraIdList.firstOrNull {
            cameraManager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == target
        } ?: cameraManager.cameraIdList.firstOrNull()

    /** 从设备支持的 YUV 尺寸中选最接近目标宽高比的尺寸 */
    private fun pickSize(chars: CameraCharacteristics): Size {
        val sizes = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(ImageFormat.YUV_420_888)
            ?: return Size(1280, 720)
        val targetAspect = desiredSize.width.toDouble() / desiredSize.height
        val candidates = sizes.filter {
            abs(it.width.toDouble() / it.height - targetAspect) < 0.05
        }.ifEmpty { sizes.toList() }
        return candidates.minByOrNull { abs(it.width * it.height - desiredSize.width * desiredSize.height) }
            ?: Size(1280, 720)
    }

    private fun closeDevice() {
        try {
            session?.close()
        } catch (_: Exception) {
        }
        try {
            imageReader?.close()
        } catch (_: Exception) {
        }
        try {
            cameraDevice?.close()
        } catch (_: Exception) {
        }
        session = null
        imageReader = null
        cameraDevice = null
        requestBuilder = null
    }

    /** 彻底关闭，停止相机线程 */
    fun close() {
        closed = true
        closeDevice()
        thread.quitSafely()
    }

    companion object {
        private const val TAG = "CameraController"
    }
}
