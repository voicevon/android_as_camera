package com.von.cameraapp.photo

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 拍照：置 pending 后在下一帧相机回调中抓拍。
 * YUV → NV21 → JPEG，按编码旋转角转正后保存（图库 / 公共 Pictures 目录）。
 */
class PhotoTaker(
    private val context: Context,
    private val listener: (String?) -> Unit
) {
    @Volatile private var pending = false

    fun requestCapture() {
        pending = true
    }

    /** 在相机帧线程调用 */
    fun maybeCapture(image: Image, rotation: Int) {
        if (!pending) return
        pending = false
        try {
            if (image.format != ImageFormat.YUV_420_888) {
                listener(null)
                return
            }
            val nv21 = yuv420ToNv21(image)
            val yuv = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
            val jpeg = ByteArrayOutputStream().also { stream ->
                yuv.compressToJpeg(Rect(0, 0, image.width, image.height), JPEG_QUALITY, stream)
            }.toByteArray()

            val rotated = if (rotation == 90 || rotation == 180 || rotation == 270) {
                rotateJpeg(jpeg, rotation)
            } else {
                jpeg
            }
            listener(save(rotated))
        } catch (e: Exception) {
            Log.w(TAG, "capture", e)
            listener(null)
        }
    }

    private fun yuv420ToNv21(image: Image): ByteArray {
        val w = image.width
        val h = image.height
        val ySize = w * h
        val out = ByteArray(ySize + 2 * (w / 2) * (h / 2))
        System.arraycopy(planeBytes(image.planes[0], w, h), 0, out, 0, ySize)
        val u = planeBytes(image.planes[1], w / 2, h / 2)
        val v = planeBytes(image.planes[2], w / 2, h / 2)
        var idx = ySize
        for (i in 0 until (w / 2) * (h / 2)) {
            out[idx++] = v[i] // NV21: VU 交错
            out[idx++] = u[i]
        }
        return out
    }

    private fun planeBytes(p: Image.Plane, w: Int, h: Int): ByteArray {
        val out = ByteArray(w * h)
        val buf = p.buffer
        for (row in 0 until h) {
            val off = row * p.rowStride
            if (p.pixelStride == 1) {
                for (x in 0 until w) out[row * w + x] = buf.get(off + x)
            } else {
                for (x in 0 until w) out[row * w + x] = buf.get(off + x * p.pixelStride)
            }
        }
        return out
    }

    private fun rotateJpeg(jpeg: ByteArray, rotation: Int): ByteArray {
        val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        val out = ByteArrayOutputStream()
        rotated.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        return out.toByteArray()
    }

    private fun save(jpeg: ByteArray): String? {
        val name = "IMG_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg"
        return if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/AndroidCamera")
            }
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return null
            context.contentResolver.openOutputStream(uri)?.use { it.write(jpeg) }
            "Pictures/AndroidCamera/$name"
        } else {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "AndroidCamera")
            dir.mkdirs()
            val file = File(dir, name)
            file.writeBytes(jpeg)
            file.absolutePath
        }
    }

    companion object {
        private const val TAG = "PhotoTaker"
        private const val JPEG_QUALITY = 85
    }
}
