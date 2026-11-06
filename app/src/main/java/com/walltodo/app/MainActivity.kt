package com.walltodo.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.MotionEvent
import android.widget.EditText
import android.widget.ImageView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.walltodo.app.databinding.ActivityMainBinding
import java.util.concurrent.Executors

/**
 * 主界面：输入待办 → 设为桌面+锁屏壁纸；完成后点击还原。
 *
 * 原壁纸获取策略（v1.3）：
 * 1. 首次设置时先跳转授权页获取文件访问权限（Android 11+ 为"所有文件访问"，10- 为运行时权限），
 *    授权后自动备份当前壁纸；
 * 2. 授权后仍无法读取（超级壁纸/动态壁纸/部分 ROM 限制）→ 引导用户手动选择一张图片，
 *    经裁剪页保存为持久手动原壁纸，之后每次还原都使用它；
 * 3. 拒绝授权的用户也可随时通过手动选图完成设置。
 *
 * 另提供"保存当前壁纸到相册"工具：把当前桌面壁纸导出到 Pictures/桌面待办。
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val ACTION_SET = "set"      // 授权后继续：设置待办壁纸
        private const val ACTION_SAVE = "save"    // 授权后继续：保存当前壁纸到相册
        private const val ACTION_RECAPTURE = "recapture"  // 授权后继续：重新获取当前桌面为原壁纸
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var wallpaperHelper: WallpaperHelper
    private val executor = Executors.newSingleThreadExecutor()

    /** 暂存待办文本；授权/选图/裁剪流程走完后继续执行 */
    private var pendingTodoText: String? = null

    /** 暂存授权后要继续的动作 */
    private var pendingAction: String? = null

    /** 从裁剪页返回：手动原壁纸已保存，更新状态并继续挂起的待办流程 */
    private val cropLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val text = pendingTodoText
            if (text != null) {
                pendingTodoText = null
                binding.textStatus.text = getString(R.string.status_manual_then_set)
                runSetWallpaperTask(text)
            } else {
                binding.textStatus.text = getString(R.string.status_manual_saved)
            }
            updateUiState()
        }
    }

    /** 选图：系统选择器（无需任何存储权限）。
     *  拿到 Uri 后立即拷贝到 App 私有缓存——GetContent 的临时授权只在当前回调可靠，
     *  跨 Activity 传 Uri 在部分 ROM 上会 SecurityException，拷成文件后裁剪页随便读 */
    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            binding.textStatus.text = getString(R.string.status_reading_image)
            Thread {
                val tmp = java.io.File(cacheDir, "pick_wallpaper.tmp")
                val ok = try {
                    contentResolver.openInputStream(uri)?.use { ins ->
                        java.io.FileOutputStream(tmp).use { out -> ins.copyTo(out) }
                    } != null && tmp.length() > 0
                } catch (e: Exception) {
                    false
                }
                runOnUiThread {
                    if (ok) {
                        val intent = Intent(this, CropActivity::class.java)
                            .putExtra(CropActivity.EXTRA_PATH, tmp.absolutePath)
                        cropLauncher.launch(intent)
                    } else {
                        if (pendingTodoText != null) pendingTodoText = null
                        binding.textStatus.text = getString(R.string.status_pick_read_failed)
                    }
                }
            }.start()
        } else {
            if (pendingTodoText != null) {
                pendingTodoText = null
                binding.textStatus.text = getString(R.string.status_pick_canceled)
            }
        }
    }

    /** Android 11+："所有文件访问权限"设置页返回回调 */
    private val managePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val granted = wallpaperHelper.hasStoragePermission()
        val action = pendingAction
        val text = pendingTodoText
        pendingAction = null
        if (granted) {
            when (action) {
                ACTION_SET -> if (text != null) {
                    pendingTodoText = null
                    runSetWallpaperTask(text)
                }
                ACTION_SAVE -> runSaveGalleryTask()
                ACTION_RECAPTURE -> runRecaptureTask()
                else -> binding.textStatus.text = getString(R.string.status_manage_granted)
            }
        } else {
            when (action) {
                ACTION_SET -> showPickWallpaperDialog()   // 拒绝授权，给出手动选图出路
                else -> binding.textStatus.text = getString(R.string.status_permission_denied)
            }
        }
    }

    /** Android 10 及以下：READ/WRITE 运行时权限回调 */
    private val runtimePermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val granted = grants.values.all { it }
        val action = pendingAction
        val text = pendingTodoText
        pendingAction = null
        if (granted) {
            when (action) {
                ACTION_SET -> if (text != null) {
                    pendingTodoText = null
                    runSetWallpaperTask(text)
                }
                ACTION_SAVE -> runSaveGalleryTask()
                ACTION_RECAPTURE -> runRecaptureTask()
            }
        } else {
            pendingTodoText = null
            when (action) {
                ACTION_SET -> showPickWallpaperDialog()   // 拒绝授权，给出手动选图出路
                else -> binding.textStatus.text = getString(R.string.status_permission_denied)
            }
        }
    }

    /**
     * 点击输入框以外的任意区域收起输入法。
     * 用 dispatchTouchEvent 拦截 ACTION_DOWN——ScrollView 会消费普通触摸，
     * Activity.onTouchEvent 收不到，必须在分发阶段处理。
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_DOWN) {
            val v = currentFocus
            if (v is android.widget.EditText) {
                val loc = IntArray(2)
                v.getLocationOnScreen(loc)
                val inside = ev.rawX >= loc[0] && ev.rawX <= loc[0] + v.width &&
                        ev.rawY >= loc[1] && ev.rawY <= loc[1] + v.height
                if (!inside) hideKeyboard()
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    /** 收起输入法键盘（跨版本：AndroidX WindowInsetsController） */
    private fun hideKeyboard() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.ime())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        wallpaperHelper = WallpaperHelper(this)

        updateUiState()

        binding.btnSetWallpaper.setOnClickListener {
            hideKeyboard()
            val text = binding.editText.text.toString().trim()
            if (text.isEmpty()) {
                binding.textStatus.text = getString(R.string.status_empty)
                return@setOnClickListener
            }

            // 无备份且无读壁纸权限：先跳授权页，授权后自动备份；仍失败会引导选图
            if (!wallpaperHelper.hasBackup() && !wallpaperHelper.hasStoragePermission()) {
                pendingTodoText = text
                pendingAction = ACTION_SET
                binding.textStatus.text = getString(R.string.status_jump_permission)
                requestStoragePermission()
                return@setOnClickListener
            }

            runSetWallpaperTask(text)
        }

        binding.btnRestore.setOnClickListener {
            hideKeyboard()
            if (!wallpaperHelper.hasBackup()) {
                binding.textStatus.text = getString(R.string.status_no_backup)
                return@setOnClickListener
            }
            runTask { wallpaperHelper.restoreWallpaper() }
        }

        binding.btnSaveGallery.setOnClickListener {
            hideKeyboard()
            // 读当前壁纸需要读权限；Android 9- 写入相册还需 WRITE
            if (!galleryReady()) {
                pendingAction = ACTION_SAVE
                binding.textStatus.text = getString(R.string.status_jump_permission)
                requestStoragePermission()
                return@setOnClickListener
            }
            runSaveGalleryTask()
        }

        // 原壁纸卡片：仅用户主动更换时才改变原壁纸
        binding.cardManual.setOnClickListener {
            hideKeyboard()
            showWallpaperSourceDialog()
        }
    }

    /** 原壁纸更换方式：从相册选图 / 重新获取当前桌面 */
    private fun showWallpaperSourceDialog() {
        hideKeyboard()
        val options = arrayOf(
            getString(R.string.wall_pick_gallery),
            getString(R.string.wall_recapture)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.wallpaper_label)
            .setItems(options) { _, which ->
                if (which == 0) {
                    pickImageLauncher.launch("image/*")
                } else {
                    if (!wallpaperHelper.hasStoragePermission()) {
                        pendingAction = ACTION_RECAPTURE
                        binding.textStatus.text = getString(R.string.status_jump_permission)
                        requestStoragePermission()
                    } else {
                        runRecaptureTask()
                    }
                }
            }
            .setNegativeButton(R.string.dialog_pick_negative, null)
            .show()
    }

    /** 保存当前壁纸到相册所需的权限是否齐备 */
    private fun galleryReady(): Boolean {
        if (!wallpaperHelper.hasStoragePermission()) return false
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            return ContextCompat.checkSelfPermission(
                this, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
        return true
    }

    /** 申请读取壁纸所需权限：Android 11+ 跳"所有文件访问"设置页；Android 10- 弹运行时申请 */
    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            val perms = mutableListOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
            runtimePermissionsLauncher.launch(perms.toTypedArray())
            return
        }
        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
            data = Uri.parse("package:$packageName")
        }
        try {
            managePermissionLauncher.launch(intent)
        } catch (e: Exception) {
            // 某些机型不支持直达，退到通用存储设置
            try {
                managePermissionLauncher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            } catch (e2: Exception) {
                pendingAction = null
                pendingTodoText = null
                binding.textStatus.text = getString(R.string.status_permission_page_failed)
            }
        }
    }

    /**
     * 设置待办壁纸：
     * - 已有原壁纸 → 直接设置（原壁纸保持不变）
     * - 无 → 自动获取当前桌面为原壁纸；失败（授权后仍读不到）则引导手动选图
     */
    private fun runSetWallpaperTask(text: String) {
        disableButtons()
        binding.textStatus.text = "处理中…"
        executor.execute {
            var needManual = false
            var msg = ""
            try {
                if (!wallpaperHelper.hasBackup()) {
                    try {
                        wallpaperHelper.captureCurrentWallpaper()
                    } catch (e: IllegalStateException) {
                        needManual = true
                    }
                }
                if (!needManual) {
                    wallpaperHelper.setTodoWallpaper(text)
                    msg = getString(R.string.status_set_ok)
                }
            } catch (e: Exception) {
                msg = "操作失败：${e.message ?: e.javaClass.simpleName}"
            }
            val manual = needManual
            runOnUiThread {
                if (manual) {
                    pendingTodoText = text
                    showPickWallpaperDialog()
                    restoreButtons()
                } else {
                    binding.textStatus.text = msg
                    restoreButtons()
                    updateUiState()
                }
            }
        }
    }

    /** 保存当前桌面壁纸到相册 */
    private fun runSaveGalleryTask() {
        runTask {
            val bmp = wallpaperHelper.readCurrentWallpaper()
                ?: throw IllegalStateException("无法读取当前壁纸（可能是超级壁纸/动态壁纸，或未授予文件访问权限）")
            wallpaperHelper.saveWallpaperToGallery(bmp)
        }
    }

    /** 用户主动重新获取：把原壁纸强制更新为当前桌面（失败不影响现有原壁纸） */
    private fun runRecaptureTask() {
        runTask {
            wallpaperHelper.recaptureCurrentWallpaper()
            getString(R.string.status_recapture_ok)
        }
    }

    /** 引导用户手动选图作为原壁纸 */
    private fun showPickWallpaperDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_pick_title)
            .setMessage(R.string.dialog_pick_message)
            .setPositiveButton(R.string.dialog_pick_positive) { _, _ ->
                pickImageLauncher.launch("image/*")
            }
            .setNegativeButton(R.string.dialog_pick_negative) { _, _ ->
                pendingTodoText = null
                binding.textStatus.text = getString(R.string.status_pick_canceled)
            }
            .setOnCancelListener {
                pendingTodoText = null
                binding.textStatus.text = getString(R.string.status_pick_canceled)
            }
            .show()
    }

    /** 在后台线程执行耗时操作，完成后回主线程刷新 UI */
    private fun runTask(block: () -> String) {
        disableButtons()
        binding.textStatus.text = "处理中…"
        executor.execute {
            val msg = try {
                block()
            } catch (e: Exception) {
                "操作失败：${e.message ?: e.javaClass.simpleName}"
            }
            runOnUiThread {
                binding.textStatus.text = msg
                restoreButtons()
                updateUiState()
            }
        }
    }

    private fun disableButtons() {
        binding.btnSetWallpaper.isEnabled = false
        binding.btnRestore.isEnabled = false
        binding.btnSaveGallery.isEnabled = false
    }

    private fun restoreButtons() {
        binding.btnSetWallpaper.isEnabled = true
        binding.btnRestore.isEnabled = true
        binding.btnSaveGallery.isEnabled = true
    }

    private fun updateUiState() {
        binding.textHint.text = when {
            wallpaperHelper.isTodoActive() -> getString(R.string.hint_active)
            wallpaperHelper.hasStoragePermission() -> getString(R.string.hint_perm_ok)
            else -> getString(R.string.hint_default)
        }
        val hasOrig = wallpaperHelper.hasOriginalWallpaper()
        val manual = wallpaperHelper.isOriginalManual()
        binding.textWallpaperState.text = when {
            manual -> getString(R.string.wall_state_manual)
            hasOrig -> getString(R.string.wall_state_auto)
            else -> getString(R.string.wall_state_none)
        }
        binding.textWallpaperAction.text = when {
            hasOrig -> getString(R.string.wall_action_update)
            else -> getString(R.string.wall_action_none)
        }
        updateThumb()
    }

    /** 刷新原壁纸缩略图 */
    private fun updateThumb() {
        val file = if (wallpaperHelper.hasOriginalWallpaper()) wallpaperHelper.originalFile() else null
        val bmp = file?.let { decodeThumbnail(it) }
        if (bmp != null) {
            binding.thumbWallpaper.setImageBitmap(bmp)
        } else {
            binding.thumbWallpaper.setImageDrawable(null)
        }
    }

    /** 降采样解码小缩略图，避免大图卡主线程 */
    private fun decodeThumbnail(file: java.io.File): Bitmap? {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, opts)
            var sample = 1
            while (maxOf(opts.outWidth, opts.outHeight) / sample > 240) sample *= 2
            opts.inJustDecodeBounds = false
            opts.inSampleSize = sample
            BitmapFactory.decodeFile(file.absolutePath, opts)
        } catch (e: Exception) {
            null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdown()
    }
}
