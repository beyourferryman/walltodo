package com.walltodo.app

import android.Manifest
import android.app.WallpaperManager
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.MediaStore
import androidx.core.content.ContextCompat
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 壁纸操作核心类：
 * - 原壁纸 = 持久资产（original_wallpaper.webp）：自动获取或用户手动选图，
 *   还原后不清除，仅用户主动更换（手动选图 / 重新获取当前桌面）时变化
 * - 待办设置：文本渲染为黑底白字图片并设为桌面+锁屏壁纸
 * - 还原：恢复为原壁纸（同时处理锁屏跟随/单独两种情况）
 *
 * 说明：Android 14+ 起，普通 App 无法读取真实壁纸，只有持有
 * MANAGE_EXTERNAL_STORAGE（所有文件访问权限）的 App 才能通过 getWallpaperFile
 * 获取真实壁纸原图。该权限需用户在系统设置中手动授予。
 */
class WallpaperHelper(private val context: Context) {

    companion object {
        private const val TAG = "WallTodo"
        private const val PREFS = "walltodo_prefs"
        // —— 原壁纸（持久资产）——
        private const val KEY_HAS_ORIGINAL = "has_original_wallpaper"
        private const val KEY_ORIGINAL_MANUAL = "original_is_manual"
        private const val ORIGINAL_FILE = "original_wallpaper.webp"
        private const val ORIGINAL_LOCK_FILE = "original_wallpaper_lock.png"
        private const val KEY_TODO_ACTIVE = "todo_active"
        private const val KEY_LOCK_FOLLOW_SYSTEM = "lock_follow_system"
        // —— 旧版本（v1.2~v1.6）存储，仅用于迁移识别 ——
        private const val KEY_HAS_BACKUP = "has_backup"
        private const val KEY_HAS_MANUAL = "has_manual_wallpaper"
        private const val BACKUP_SYSTEM = "wallpaper_backup_system.png"
        private const val BACKUP_LOCK = "wallpaper_backup_lock.png"
        private const val MANUAL_FILE = "manual_wallpaper.webp"
        // WallpaperManager 隐藏 flag：1=桌面 2=锁屏
        private const val FLAG_SYSTEM = 1
        private const val FLAG_LOCK = 2
        private const val MIN_FONT_SIZE = 28f
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val wm = WallpaperManager.getInstance(context)

    /** 旧数据 → 统一原壁纸结构的一次性迁移 */
    private var migrated = false
    private fun ensureMigrated() {
        if (migrated) return
        migrated = true
        if (prefs.getBoolean(KEY_HAS_ORIGINAL, false) && originalFile().exists()) return

        val manual = File(context.filesDir, MANUAL_FILE)
        val oldSystem = File(context.filesDir, BACKUP_SYSTEM)
        val oldLock = File(context.filesDir, BACKUP_LOCK)

        when {
            // 手动图优先（用户主动设置的）
            prefs.getBoolean(KEY_HAS_MANUAL, false) && manual.exists() -> {
                manual.copyTo(originalFile(), overwrite = true)
                prefs.edit()
                    .putBoolean(KEY_HAS_ORIGINAL, true)
                    .putBoolean(KEY_ORIGINAL_MANUAL, true)
                    .putBoolean(KEY_LOCK_FOLLOW_SYSTEM, true)   // 手动单图：锁屏跟随
                    .apply()
            }
            // 自动备份（升级时若备份存在，说明正处待办周期中）
            prefs.getBoolean(KEY_HAS_BACKUP, false) && oldSystem.exists() -> {
                oldSystem.copyTo(originalFile(), overwrite = true)
                if (oldLock.exists()) {
                    oldLock.copyTo(originalLockFile(), overwrite = true)
                    prefs.edit().putBoolean(KEY_LOCK_FOLLOW_SYSTEM, false).apply()
                } else {
                    prefs.edit().putBoolean(KEY_LOCK_FOLLOW_SYSTEM, true).apply()
                }
                prefs.edit()
                    .putBoolean(KEY_HAS_ORIGINAL, true)
                    .putBoolean(KEY_ORIGINAL_MANUAL, false)
                    .putBoolean(KEY_TODO_ACTIVE, true)
                    .apply()
            }
        }
        // 清理旧结构与旧标记
        manual.delete()
        oldSystem.delete()
        oldLock.delete()
        prefs.edit().remove(KEY_HAS_BACKUP).remove(KEY_HAS_MANUAL).apply()
    }

    // ================= 原壁纸（持久资产） =================

    /** 原壁纸是否存在。还原不清除它，仅用户主动更换时变化。 */
    fun hasOriginalWallpaper(): Boolean {
        ensureMigrated()
        return prefs.getBoolean(KEY_HAS_ORIGINAL, false) && originalFile().exists()
    }

    /** 兼容旧命名 */
    fun hasBackup(): Boolean = hasOriginalWallpaper()

    /** 原壁纸来源是否为用户手动选图 */
    fun isOriginalManual(): Boolean {
        ensureMigrated()
        return hasOriginalWallpaper() && prefs.getBoolean(KEY_ORIGINAL_MANUAL, false)
    }

    fun originalFile(): File = File(context.filesDir, ORIGINAL_FILE)

    private fun originalLockFile(): File = File(context.filesDir, ORIGINAL_LOCK_FILE)

    /**
     * 保存用户手动选择并裁剪好的原壁纸（用户主动更换）。
     * 手动图为单张：锁屏跟随桌面。
     */
    @Suppress("DEPRECATION")
    fun saveManualWallpaper(bitmap: Bitmap): Boolean {
        ensureMigrated()
        val ok = try {
            FileOutputStream(originalFile()).use { out ->
                bitmap.compress(Bitmap.CompressFormat.WEBP, 90, out)
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "保存手动壁纸失败：${e.message}")
            false
        }
        if (ok) {
            originalLockFile().delete()   // 手动单图，不再保留锁屏单独备份
            prefs.edit()
                .putBoolean(KEY_HAS_ORIGINAL, true)
                .putBoolean(KEY_ORIGINAL_MANUAL, true)
                .putBoolean(KEY_LOCK_FOLLOW_SYSTEM, true)
                .apply()
        }
        return ok
    }

    /**
     * 检查是否具备读取壁纸所需的权限：
     * - Android 11+（API 30+）："所有文件访问权限"（用户在系统设置手动开启）
     * - Android 10 及以下：READ_EXTERNAL_STORAGE 运行时权限
     */
    fun hasStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    /** 当前桌面是否为动态壁纸（live wallpaper，含小米超级壁纸） */
    private fun isLiveWallpaper(): Boolean = try {
        wm.wallpaperInfo != null
    } catch (e: Exception) {
        Log.w(TAG, "wallpaperInfo 异常：${e.message}")
        false
    }

    /**
     * 自动获取当前桌面为原壁纸（首次设置待办时调用）。
     * 已有原壁纸时不动——还原后原壁纸保持不变，仅用户主动更换时才更新。
     */
    fun captureCurrentWallpaper() {
        ensureMigrated()
        if (hasOriginalWallpaper()) return
        doCapture()
    }

    /** 用户主动重新获取：把原壁纸强制更新为当前桌面 */
    fun recaptureCurrentWallpaper() {
        ensureMigrated()
        doCapture()
    }

    private fun doCapture() {
        // 动态壁纸（含小米超级壁纸）没有静态原图可读，提前给出明确的操作指引
        if (isLiveWallpaper()) {
            throw IllegalStateException(
                "当前桌面是动态壁纸/超级壁纸，读不到原壁纸。请到 设置→壁纸 把桌面换成一张普通静态图片后重试"
            )
        }

        val systemBitmap = readWallpaperBitmap(WallpaperManager.FLAG_SYSTEM)
            ?: throw IllegalStateException(
                "无法读取当前桌面壁纸（${lastReadDiagnose ?: "未知原因"}）。" +
                        "请尝试：1. 换成静态图片壁纸；2. 重启手机后重试"
            )
        @Suppress("DEPRECATION")
        FileOutputStream(originalFile()).use { out ->
            systemBitmap.compress(Bitmap.CompressFormat.WEBP, 90, out)
        }

        // 锁屏：单独设置则一并备份，未单独设置则跟随桌面
        val lockBitmap = readWallpaperBitmap(WallpaperManager.FLAG_LOCK)
        if (lockBitmap != null) {
            FileOutputStream(originalLockFile()).use { out ->
                lockBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            prefs.edit().putBoolean(KEY_LOCK_FOLLOW_SYSTEM, false).apply()
        } else {
            originalLockFile().delete()
            prefs.edit().putBoolean(KEY_LOCK_FOLLOW_SYSTEM, true).apply()
        }

        prefs.edit()
            .putBoolean(KEY_HAS_ORIGINAL, true)
            .putBoolean(KEY_ORIGINAL_MANUAL, false)
            .apply()
    }

    /** 把待办文本生成为黑底白字图片并设为桌面+锁屏壁纸 */
    fun setTodoWallpaper(text: String) {
        val size = getRealScreenSize()
        val bitmap = renderTodoBitmap(text, size.x, size.y)
        setWallpaperBoth(bitmap)
        prefs.edit().putBoolean(KEY_TODO_ACTIVE, true).apply()
    }

    /** 当前桌面是否处于待办提醒壁纸状态 */
    fun isTodoActive(): Boolean = prefs.getBoolean(KEY_TODO_ACTIVE, false)

    /**
     * 还原为原壁纸，返回结果描述。
     * 原壁纸是持久资产：还原不清除，之后的设置/还原周期继续使用同一张；
     * 仅当用户主动更换（手动选图 / 重新获取当前桌面）时才变化。
     */
    fun restoreWallpaper(): String {
        ensureMigrated()
        if (!hasOriginalWallpaper()) {
            throw IllegalStateException("没有备份的原桌面")
        }
        val bmp = BitmapFactory.decodeFile(originalFile().absolutePath)
            ?: throw IllegalStateException("无法读取原壁纸文件")

        setWallpaper(bmp, FLAG_SYSTEM)

        val lockFollowsSystem = prefs.getBoolean(KEY_LOCK_FOLLOW_SYSTEM, true)
        if (!lockFollowsSystem) {
            val lockFile = originalLockFile()
            val lockBitmap = if (lockFile.exists()) BitmapFactory.decodeFile(lockFile.absolutePath) else null
            if (lockBitmap != null) setWallpaper(lockBitmap, FLAG_LOCK)
            else setWallpaper(bmp, FLAG_LOCK)
        } else {
            setWallpaper(bmp, FLAG_LOCK)
        }

        prefs.edit().putBoolean(KEY_TODO_ACTIVE, false).apply()
        return if (isOriginalManual()) "已还原为你设置的默认壁纸" else "已还原原桌面"
    }

    /** 最近一次 readWallpaperBitmap 失败的分步诊断信息 */
    @Volatile
    private var lastReadDiagnose: String? = null

    /**
     * 读取指定类型壁纸的真实原图，失败时把各步骤原因记录到 lastReadDiagnose。
     * Android 14+ 需要 MANAGE_EXTERNAL_STORAGE 权限才能读到真实壁纸，
     * 否则 getWallpaperFile 会返回 null 或抛 SecurityException。
     * 注意：getDrawable 只能拿到桌面壁纸，仅用于 FLAG_SYSTEM 的回退，
     * 不能拿桌面图冒充锁屏备份。
     * @param which FLAG_SYSTEM 或 FLAG_LOCK
     * @return 壁纸原图 Bitmap，锁屏未单独设置时返回 null
     */
    private fun readWallpaperBitmap(which: Int): Bitmap? {
        val steps = mutableListOf<String>()

        // 优先用 getWallpaperFile 拿原图文件描述符
        try {
            val pfd: ParcelFileDescriptor? = wm.getWallpaperFile(which)
            if (pfd != null) {
                val bmp = pfd.use {
                    BitmapFactory.decodeFileDescriptor(it.fileDescriptor)
                }
                if (bmp != null) return bmp
                steps.add("getWallpaperFile解码失败")
            } else {
                steps.add("getWallpaperFile返回null")
            }
        } catch (e: Exception) {
            Log.w(TAG, "getWallpaperFile($which) 异常：${e.message}")
            steps.add("getWallpaperFile异常:${e.javaClass.simpleName}")
        }

        // 回退到 getDrawable（仅桌面壁纸；无 MANAGE_EXTERNAL_STORAGE 时会抛 SecurityException）
        if (which == WallpaperManager.FLAG_SYSTEM) {
            try {
                val drawable = wm.drawable
                if (drawable != null) {
                    val bmp = drawableToBitmap(drawable)
                    if (bmp != null) return bmp
                    steps.add("getDrawable转Bitmap失败")
                } else {
                    steps.add("getDrawable返回null")
                }
            } catch (e: Exception) {
                Log.w(TAG, "getDrawable 异常：${e.message}")
                steps.add("getDrawable异常:${e.javaClass.simpleName}")
            }
        }

        if (steps.isNotEmpty()) {
            lastReadDiagnose = steps.joinToString("；")
        }
        return null
    }

    /** 当前桌面壁纸位图（读原图优先，回退 getDrawable），供“保存到相册”使用 */
    fun readCurrentWallpaper(): Bitmap? = readWallpaperBitmap(WallpaperManager.FLAG_SYSTEM)

    /**
     * 保存当前壁纸到相册 Pictures/桌面待办。
     * Android 10+ 走 MediaStore（无需权限）；Android 9- 走传统路径（需 WRITE 权限）。
     * 返回结果描述，失败抛异常。
     */
    fun saveWallpaperToGallery(bitmap: Bitmap): String {
        val name = "walltodo_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".png"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/桌面待办")
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "桌面待办")
                if (!dir.exists() && !dir.mkdirs()) {
                    throw IllegalStateException("无法创建相册目录")
                }
                @Suppress("DEPRECATION")
                put(MediaStore.Images.Media.DATA, File(dir, name).absolutePath)
            }
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("无法写入相册")
        try {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            } ?: throw IllegalStateException("无法打开相册输出流")
        } catch (e: Exception) {
            context.contentResolver.delete(uri, null, null)
            throw e
        }
        return "已保存到相册：Pictures/桌面待办/$name"
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
