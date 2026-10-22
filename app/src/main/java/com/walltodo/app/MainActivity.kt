package com.walltodo.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.walltodo.app.databinding.ActivityMainBinding
import java.util.concurrent.Executors

/**
 * 主界面：输入待办 → 设为桌面+锁屏壁纸；完成后点击还原。
 *
 * 原壁纸备份方案：通过 MANAGE_EXTERNAL_STORAGE（所有文件访问权限）+
 * WallpaperManager.getWallpaperFile() 读取当前桌面和锁屏的真实原图并备份。
 * Android 14+ 读取真实壁纸必须用此权限，需用户在系统设置中手动开启。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var wallpaperHelper: WallpaperHelper
    private val executor = Executors.newSingleThreadExecutor()

    /** 暂存用户输入的待办文本，授权返回后继续执行 */
    private var pendingTodoText: String? = null

    /** 从"所有文件访问权限"设置页返回的回调 */
    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val text = pendingTodoText
        if (wallpaperHelper.hasStoragePermission()) {
            // 已授权，继续执行待办设置流程
            if (text != null) {
                pendingTodoText = null
                runSetWallpaperTask(text)
            } else {
                binding.textStatus.text = "已授予所有文件访问权限"
                restoreButtons()
            }
        } else {
            pendingTodoText = null
            binding.textStatus.text = getString(R.string.status_permission_denied)
            restoreButtons()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        wallpaperHelper = WallpaperHelper(this)

        updateUiState()

        binding.btnSetWallpaper.setOnClickListener {
            val text = binding.editText.text.toString().trim()
            if (text.isEmpty()) {
                binding.textStatus.text = getString(R.string.status_empty)
                return@setOnClickListener
            }

            if (!wallpaperHelper.hasStoragePermission()) {
                // 未授权：先引导授权，授权后继续设置
                pendingTodoText = text
                binding.textStatus.text = getString(R.string.status_request_permission)
                disableButtons()
                requestStoragePermission()
                return@setOnClickListener
            }

            runSetWallpaperTask(text)
        }

        binding.btnRestore.setOnClickListener {
            if (!wallpaperHelper.hasBackup()) {
                binding.textStatus.text = getString(R.string.status_no_backup)
                return@setOnClickListener
            }
            runTask {
                wallpaperHelper.restoreWallpaper()
                getString(R.string.status_restore_ok)
            }
        }
    }

    /** 设置待办壁纸：无备份先备份，再渲染设置 */
    private fun runSetWallpaperTask(text: String) {
        runTask {
            if (!wallpaperHelper.hasBackup()) {
                wallpaperHelper.saveCurrentWallpaper()
            }
            wallpaperHelper.setTodoWallpaper(text)
            getString(R.string.status_set_ok)
        }
    }

    /** 跳转到系统"所有文件访问权限"设置页 */
    private fun requestStoragePermission() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:$packageName")
            }
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            }
        }
        try {
            storagePermissionLauncher.launch(intent)
        } catch (e: Exception) {
            // 某些机型不支持直达，退到通用存储设置
            val fallback = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
            try {
                storagePermissionLauncher.launch(fallback)
            } catch (e2: Exception) {
                pendingTodoText = null
                binding.textStatus.text = "无法打开权限设置页，请到系统设置手动开启"
                restoreButtons()
            }
        }
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
    }

    private fun restoreButtons() {
        binding.btnSetWallpaper.isEnabled = true
        binding.btnRestore.isEnabled = true
    }

    private fun updateUiState() {
        val hasBackup = wallpaperHelper.hasBackup()
        val hasPermission = wallpaperHelper.hasStoragePermission()
        binding.textHint.text = when {
            hasBackup -> getString(R.string.hint_active)
            hasPermission -> getString(R.string.hint_no_backup_perm_ok)
            else -> getString(R.string.hint_default)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdown()
    }
}
