package com.walltodo.app

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.media.ExifInterface
import android.os.Bundle
import android.util.AttributeSet
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 原壁纸裁剪页：用户选定一张图片后，在此拖动/缩放选定区域，
 * 裁剪框比例锁定为屏幕物理比例，输出按物理分辨率的位图，
 * 保存为持久的手动原壁纸（manual_wallpaper.webp）。
 *
 * 该功能用于绕开"读不到系统壁纸"的限制（超级壁纸/动态壁纸/未授权）：
 * 用户提供原图，还原桌面时直接使用，不再依赖系统壁纸读取。
 */
class CropActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "WallTodo.Crop"
        /** 待裁剪图片的本地缓存文件路径（已拷贝到 App 私有目录，无 URI 权限问题） */
        const val EXTRA_PATH = "path"
        /** 解码时允许的最大长边，防止超大图撑爆内存 */
        private const val MAX_DECODE_EDGE = 4096
    }

    private lateinit var cropView: CropView
    private var targetW = 1080
    private var targetH = 2400
    private var srcPath: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_crop)

        val path = intent.getStringExtra(EXTRA_PATH)
        if (path == null || !java.io.File(path).exists()) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }
        srcPath = path

        // 输出目标 = 显示面板物理分辨率（裁剪框比例与之一致）
        val (w, h) = physicalScreenSize()
        targetW = w.coerceAtLeast(720)
        targetH = h.coerceAtLeast(1280)

        cropView = findViewById(R.id.cropView)
        cropView.setFrameAspect(targetW.toFloat() / targetH.toFloat())

        val btnApply = findViewById<android.widget.Button>(R.id.btnApply)
        val btnCancel = findViewById<android.widget.Button>(R.id.btnCancel)
        val textLoading = findViewById<android.widget.TextView>(R.id.textCropLoading)

        btnApply.setOnClickListener {
            val bitmap = cropView.crop(targetW, targetH)
            if (bitmap == null) {
                android.widget.Toast.makeText(this, R.string.crop_too_small, android.widget.Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            btnApply.isEnabled = false
            btnCancel.isEnabled = false
            btnApply.text = getString(R.string.crop_saving)
            Thread {
                val ok = saveManual(bitmap)
                runOnUiThread {
                    if (ok) {
                        setResult(RESULT_OK)
                        finish()
                    } else {
                        btnApply.isEnabled = true
                        btnCancel.isEnabled = true
                        btnApply.text = getString(R.string.crop_apply)
                        android.widget.Toast.makeText(this, R.string.crop_save_failed, android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }.start()
        }
        btnCancel.setOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }

        // 后台加载图片（含降采样与 EXIF 旋转）
        Thread {
            val bmp = loadBitmap(path)
            runOnUiThread {
                if (bmp == null) {
                    android.widget.Toast.makeText(this, R.string.crop_load_failed, android.widget.Toast.LENGTH_SHORT).show()
                    setResult(RESULT_CANCELED)
                    finish()
                } else {
                    textLoading.visibility = View.GONE
                    cropView.setBitmap(bmp)
                }
            }
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        // 清理选图临时缓存文件（裁剪结果已另存为 manual_wallpaper.webp）
        srcPath?.let { java.io.File(it).delete() }
        srcPath = null
    }

    /**
     * 显示面板物理分辨率。
     * 用 DisplayManager + Display.Mode（API 23+，无废弃、不受窗口模式影响），
     * 折叠屏取所有模式中像素数最大的形态。
     */
    private fun physicalScreenSize(): Pair<Int, Int> {
        try {
            val dm = getSystemService(DisplayManager::class.java)
            val display = dm.getDisplay(android.view.Display.DEFAULT_DISPLAY)
                ?: return fallbackScreenSize()
            val best = display.supportedModes.maxByOrNull {
                it.physicalWidth.toLong() * it.physicalHeight
            }
            return Pair(best?.physicalWidth ?: display.mode.physicalWidth,
                best?.physicalHeight ?: display.mode.physicalHeight)
        } catch (e: Exception) {
            Log.w(TAG, "获取物理分辨率失败，回退窗口尺寸：${e.message}")
            return fallbackScreenSize()
        }
    }

    private fun fallbackScreenSize(): Pair<Int, Int> {
        val metrics = resources.displayMetrics
        return Pair(metrics.widthPixels, metrics.heightPixels)
    }

    /** 降采样解码 + 按 EXIF 方向转正（读本地文件，无 URI 权限问题） */
    @Suppress("DEPRECATION")
    private fun loadBitmap(path: String): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / sample > MAX_DECODE_EDGE) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val decoded = BitmapFactory.decodeFile(path, opts) ?: return null

            // 读取 EXIF 方向并转正
            val degrees = try {
                when (ExifInterface(path).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
                )) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } catch (e: Exception) {
                0f
            }
            if (degrees == 0f) return decoded
            val m = Matrix().apply { postRotate(degrees) }
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true)
        } catch (e: Exception) {
            Log.w(TAG, "加载图片失败：${e.message}")
            null
        }
    }

    /**
     * 保存裁剪结果为持久手动原壁纸。
     * 必须经 WallpaperHelper 保存——它同时会把"手动壁纸已设置"标记写入
     * SharedPreferences，主界面的缩略图与还原流程都依赖这个标记。
     */
    private fun saveManual(bitmap: Bitmap): Boolean =
        WallpaperHelper(this).saveManualWallpaper(bitmap)

    /**
     * 裁剪视图：图片可拖动/缩放，中央固定裁剪框（比例=屏幕物理比例），
     * 框外区域半透明遮罩。所见即所得：框内内容即还原后桌面内容。
     * 注意：普通嵌套类（非 inner），XML 才能实例化。
     */
    class CropView(context: android.content.Context, attrs: AttributeSet? = null) : View(context, attrs) {

        private var bmp: Bitmap? = null
        private val frame = RectF()
        private var frameAspect = 9f / 19.5f

        private var baseScale = 1f      // 覆盖裁剪框所需的最小缩放
        private var scale = 1f         // 当前总缩放
        private var transX = 0f        // 图片绘制左上角（view 坐标）
        private var transY = 0f

        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val maskPaint = Paint().apply { color = 0x99000000.toInt() }
        private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }

        fun setFrameAspect(aspect: Float) {
            frameAspect = aspect
            if (bmp != null) computeBase()
        }

        fun setBitmap(bitmap: Bitmap) {
            bmp = bitmap
            computeBase()
            invalidate()
        }

        /** 计算初始 cover 状态：图片恰好覆盖裁剪框并居中 */
        private fun computeBase() {
            val b = bmp ?: return
            if (width == 0 || height == 0) return
            val frameW = min(width * 0.9f, height * 0.9f * frameAspect)
            val frameH = frameW / frameAspect
            frame.set(
                (width - frameW) / 2f, (height - frameH) / 2f,
                (width + frameW) / 2f, (height + frameH) / 2f
            )
            baseScale = max(frame.width() / b.width, frame.height() / b.height)
            scale = baseScale
            transX = frame.left + (frame.width() - b.width * scale) / 2f
            transY = frame.top + (frame.height() - b.height * scale) / 2f
            clamp()
            invalidate()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            if (bmp != null) computeBase()
        }

        /** 约束：图片始终完整覆盖裁剪框，且缩放不小于基础覆盖缩放 */
        private fun clamp() {
            val b = bmp ?: return
            scale = max(scale, baseScale)
            val w = b.width * scale
            val h = b.height * scale
            transX = if (w > frame.width()) {
                transX.coerceIn(frame.left - (w - frame.width()), frame.left)
            } else frame.left + (frame.width() - w) / 2f
            transY = if (h > frame.height()) {
                transY.coerceIn(frame.top - (h - frame.height()), frame.top)
            } else frame.top + (frame.height() - h) / 2f
        }

        private val scaleDetector = ScaleGestureDetector(context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val b = bmp ?: return false
                    val old = scale
                    scale *= detector.scaleFactor
                    scale = max(scale, baseScale)
                    // 以双指焦点为锚缩放
                    val f = scale / old
                    transX = detector.focusX - (detector.focusX - transX) * f
                    transY = detector.focusY - (detector.focusY - transY) * f
                    clamp()
                    invalidate()
                    return true
                }
            })

        private val gestureDetector = GestureDetector(context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true

                override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                    if (bmp == null) return false
                    transX -= dx
                    transY -= dy
                    clamp()
                    invalidate()
                    return true
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    if (bmp != null) {
                        computeBase()
                        invalidate()
                    }
                    return true
                }
            })

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (bmp == null) return false
            scaleDetector.onTouchEvent(event)
            if (!scaleDetector.isInProgress) gestureDetector.onTouchEvent(event)
            return true
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawColor(Color.WHITE)
            val b = bmp ?: return
            val dst = RectF(transX, transY, transX + b.width * scale, transY + b.height * scale)
            canvas.drawBitmap(b, null, dst, paint)

            // 框外遮罩（四块）
            canvas.drawRect(0f, 0f, width.toFloat(), frame.top, maskPaint)
            canvas.drawRect(0f, frame.bottom, width.toFloat(), height.toFloat(), maskPaint)
            canvas.drawRect(0f, frame.top, frame.left, frame.bottom, maskPaint)
            canvas.drawRect(frame.right, frame.top, width.toFloat(), frame.bottom, maskPaint)

            canvas.drawRect(frame, framePaint)
        }

        /** 按当前视图状态裁剪，并缩放到目标分辨率；图未就绪或区域无效返回 null */
        fun crop(targetW: Int, targetH: Int): Bitmap? {
            val b = bmp ?: return null
            if (frame.isEmpty) return null
            val sx = ((frame.left - transX) / scale).roundToInt()
            val sy = ((frame.top - transY) / scale).roundToInt()
            val sw = (frame.width() / scale).roundToInt()
            val sh = (frame.height() / scale).roundToInt()
            val x = sx.coerceIn(0, b.width - 1)
            val y = sy.coerceIn(0, b.height - 1)
            val w = sw.coerceIn(1, b.width - x)
            val h = sh.coerceIn(1, b.height - y)
            if (w < 8 || h < 8) return null
            val cropped = Bitmap.createBitmap(b, x, y, w, h)
            return if (w == targetW && h == targetH) cropped
            else Bitmap.createScaledBitmap(cropped, targetW, targetH, true)
        }
    }
}
