package com.walltodo.app

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import java.io.File
import java.io.FileOutputStream

/**
 * 壁纸操作核心类：
 * - 通过 getWallpaperFile + MANAGE_EXTERNAL_STORAGE 权限分别备份桌面和锁屏的真实原图
 * - 把文本渲染成黑底白字图片并设为桌面+锁屏壁纸
 * - 还原时分别恢复桌面和锁屏的原壁纸
 *
 * 说明：Android 14+ 起，普通 App 无法读取真实壁纸，只有持有
 * MANAGE_EXTERNAL_STORAGE（所有文件访问权限）的 App 才能通过 getWallpaperFile
 * 获取真实壁纸原图。该权限需用户在系统设置中手动授予。
 */
class WallpaperHelper(private val context: Context) {

    companion object {
        private const val TAG = "WallTodo"
        private const val PREFS = "walltodo_prefs"
        private const val KEY_HAS_BACKUP = "has_backup"
        private const val KEY_LOCK_FOLLOW_SYSTEM = "lock_follow_system"
        private const val BACKUP_SYSTEM = "wallpaper_backup_system.png"
        private const val BACKUP_LOCK = "wallpaper_backup_lock.png"
        // WallpaperManager 隐藏 flag：1=桌面 2=锁屏
        private const val FLAG_SYSTEM = 1
        private const val FLAG_LOCK = 2
        private const val MIN_FONT_SIZE = 28f
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val wm = WallpaperManager.getInstance(context)

    fun hasBackup(): Boolean = prefs.getBoolean(KEY_HAS_BACKUP, false)

    /** 检查是否已授予"所有文件访问权限" */
    fun hasStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }
    }

    private fun backupFile(name: String): File = File(context.filesDir, name)

    /**
     * 备份当前桌面和锁屏的真实壁纸原图。
     * 前置条件：已授予 MANAGE_EXTERNAL_STORAGE（hasStoragePermission() == true）。
     * 返回成功备份的类型描述，失败抛异常。
     */
    fun saveCurrentWallpaper() {
        if (hasBackup()) return

        // 备份桌面壁纸
        val systemBitmap = readWallpaperBitmap(WallpaperManager.FLAG_SYSTEM)
            ?: throw IllegalStateException("无法读取当前桌面壁纸（请确认已授予所有文件访问权限）")
        saveBitmap(systemBitmap, BACKUP_SYSTEM)

        // 备份锁屏壁纸；若返回 null 表示锁屏未单独设置、跟随桌面
        val lockBitmap = readWallpaperBitmap(WallpaperManager.FLAG_LOCK)
        if (lockBitmap != null) {
            saveBitmap(lockBitmap, BACKUP_LOCK)
            prefs.edit().putBoolean(KEY_LOCK_FOLLOW_SYSTEM, false).apply()
        } else {
            prefs.edit().putBoolean(KEY_LOCK_FOLLOW_SYSTEM, true).apply()
        }

        prefs.edit().putBoolean(KEY_HAS_BACKUP, true).apply()
    }

    /** 把待办文本生成为黑底白字图片并设为桌面+锁屏壁纸 */
    fun setTodoWallpaper(text: String) {
        val size = getRealScreenSize()
        val bitmap = renderTodoBitmap(text, size.x, size.y)
        setWallpaperBoth(bitmap)
    }

    /** 还原为备份的原壁纸 */
    fun restoreWallpaper() {
        if (!hasBackup()) {
            throw IllegalStateException("没有备份的原桌面")
        }
        val lockFollowsSystem = prefs.getBoolean(KEY_LOCK_FOLLOW_SYSTEM, true)

        // 还原桌面
        val systemBitmap = BitmapFactory.decodeFile(backupFile(BACKUP_SYSTEM).absolutePath)
            ?: throw IllegalStateException("无法读取桌面备份文件")
        setWallpaper(systemBitmap, FLAG_SYSTEM)

        // 还原锁屏
        if (!lockFollowsSystem) {
            val lockFile = backupFile(BACKUP_LOCK)
            if (lockFile.exists()) {
                val lockBitmap = BitmapFactory.decodeFile(lockFile.absolutePath)
                if (lockBitmap != null) {
                    setWallpaper(lockBitmap, FLAG_LOCK)
                }
            }
        } else {
            // 锁屏原本跟随桌面，把桌面图也设给锁屏
            setWallpaper(systemBitmap, FLAG_LOCK)
        }

        // 清理备份
        backupFile(BACKUP_SYSTEM).delete()
        backupFile(BACKUP_LOCK).delete()
        prefs.edit()
            .putBoolean(KEY_HAS_BACKUP, false)
            .putBoolean(KEY_LOCK_FOLLOW_SYSTEM, false)
            .apply()
    }

    /**
     * 读取指定类型壁纸的真实原图。
     * Android 14+ 需要 MANAGE_EXTERNAL_STORAGE 权限才能读到真实壁纸，
     * 否则 getWallpaperFile 会返回 null 或抛 SecurityException。
     * @param which FLAG_SYSTEM 或 FLAG_LOCK
     * @return 壁纸原图 Bitmap，锁屏未单独设置时返回 null
     */
    private fun readWallpaperBitmap(which: Int): Bitmap? {
        // 优先用 getWallpaperFile 拿原图文件描述符
        try {
            val pfd: ParcelFileDescriptor? = wm.getWallpaperFile(which)
            if (pfd != null) {
                val bmp = pfd.use {
                    BitmapFactory.decodeFileDescriptor(it.fileDescriptor)
                }
                if (bmp != null) return bmp
            }
        } catch (e: Exception) {
            Log.w(TAG, "getWallpaperFile($which) 异常：${e.message}")
        }

        // 回退到 getDrawable（Android 14+ 无 MANAGE_EXTERNAL_STORAGE 时会抛 SecurityException）
        return try {
            val drawable = wm.drawable ?: return null
            drawableToBitmap(drawable)
        } catch (e: Exception) {
            Log.w(TAG, "getDrawable 异常：${e.message}")
            null
        }
    }

    private fun saveBitmap(bitmap: Bitmap, name: String) {
        FileOutputStream(backupFile(name)).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
    }

    /**
     * 同时设置桌面和锁屏壁纸。
     * API 24+ 通过反射调用隐藏的 setBitmap(bitmap, crop, allowBackup, which) 同时设置两者；
     * 反射失败则回退到公开 API（仅设桌面壁纸，锁屏多数会跟随）。
     */
    private fun setWallpaperBoth(bitmap: Bitmap) {
        setWallpaper(bitmap, FLAG_SYSTEM or FLAG_LOCK)
    }

    /** 设置指定类型的壁纸 */
    private fun setWallpaper(bitmap: Bitmap, which: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                val method = WallpaperManager::class.java.getMethod(
                    "setBitmap",
                    Bitmap::class.java,
                    Rect::class.java,
                    Boolean::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType
                )
                method.invoke(wm, bitmap, null, true, which)
                return
            } catch (e: Exception) {
                Log.w(TAG, "反射 setBitmap(which=$which) 失败，回退公开 API：${e.message}")
            }
        }
        // 公开 API 只能设桌面
        wm.setBitmap(bitmap)
    }

    private fun drawableToBitmap(drawable: android.graphics.drawable.Drawable): Bitmap? {
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            return drawable.bitmap
        }
        val w = drawable.intrinsicWidth.coerceAtLeast(1)
        val h = drawable.intrinsicHeight.coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }

    @Suppress("DEPRECATION")
    private fun getRealScreenSize(): Point {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)
        val w = metrics.widthPixels.coerceAtLeast(720)
        val h = metrics.heightPixels.coerceAtLeast(1280)
        return Point(w, h)
    }

    /** 把文本渲染成黑底白字、自动换行、字号自适应的图片 */
    private fun renderTodoBitmap(text: String, width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.BLACK)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }

        val horizontalPadding = (width * 0.1f).coerceAtLeast(60f)
        val maxWidth = width - horizontalPadding * 2
        val maxTotalHeight = height * 0.72f

        var fontSize = height * 0.11f
        var lines: List<String>
        while (true) {
            paint.textSize = fontSize
            lines = wrapText(text, paint, maxWidth)
            val fm = paint.fontMetrics
            val lineHeight = (fm.descent - fm.ascent) * 1.25f
            if (lineHeight * lines.size <= maxTotalHeight || fontSize <= MIN_FONT_SIZE) break
            fontSize = (fontSize * 0.9f).coerceAtLeast(MIN_FONT_SIZE)
        }

        val fm = paint.fontMetrics
        val lineHeight = (fm.descent - fm.ascent) * 1.25f
        val totalHeight = lineHeight * lines.size
        var y = (height - totalHeight) / 2f - fm.ascent
        for (line in lines) {
            canvas.drawText(line, width / 2f, y, paint)
            y += lineHeight
        }
        return bitmap
    }

    /** 逐字符测量换行，对中英文都友好 */
    private fun wrapText(text: String, paint: Paint, maxWidth: Float): List<String> {
        val result = mutableListOf<String>()
        for (paragraph in text.split("\n")) {
            if (paragraph.isEmpty()) {
                result.add("")
                continue
            }
            val current = StringBuilder()
            for (ch in paragraph.toCharArray()) {
                current.append(ch)
                if (paint.measureText(current.toString()) > maxWidth) {
                    current.deleteCharAt(current.length - 1)
                    result.add(current.toString())
                    current.clear()
                    current.append(ch)
                }
            }
            if (current.isNotEmpty()) result.add(current.toString())
        }
        return result
    }
}
