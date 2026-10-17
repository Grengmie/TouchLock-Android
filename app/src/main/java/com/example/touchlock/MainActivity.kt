package com.example.touchlock

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.touchlock.databinding.ActivityMainBinding
import com.google.android.material.color.MaterialColors

/**
 * 应用入口界面。
 *
 * 职责：
 * 1. 显示 TouchLock 无障碍服务当前是否已开启；
 * 2. 提供按钮跳转到系统「无障碍」设置页；
 * 3. 从设置页返回应用时（onResume）刷新状态，避免显示过期信息；
 * 4. 展示简单使用方法。
 */
class MainActivity : AppCompatActivity() {

    /** 视图绑定对象，避免使用 findViewById */
    private lateinit var binding: ActivityMainBinding

    /** 应用设置存取（遮罩显示时长等） */
    private lateinit var settings: TouchLockSettings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        settings = TouchLockSettings(this)

        // 加载布局并设置内容视图
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 点击按钮跳转到系统无障碍设置页面
        binding.btnOpenAccessibilitySettings.setOnClickListener {
            openAccessibilitySettings()
        }

        // 保存「遮罩显示时长」等设置
        binding.btnSaveOverlayDuration.setOnClickListener {
            saveOverlaySettings()
        }

        // 把已保存的设置回填到界面
        loadOverlaySettingsIntoUi()

        // 如果上次运行发生过崩溃，把堆栈追加到说明文字后面，方便直接截图反馈
        showLastCrashIfAny()
    }

    /**
     * 把已保存的设置回填到界面控件上。
     */
    private fun loadOverlaySettingsIntoUi() {
        binding.etOverlayDurationMs.setText(settings.overlayVisibleDurationMs.toString())
        binding.cbKeepLockedAfterHide.isChecked = settings.keepLockedAfterOverlayHidden
    }

    /**
     * 读取界面上的设置并保存。
     *
     * 时长会被 [TouchLockSettings] 限制在 0 ~ 10000 毫秒之间，保存后把实际生效的值回填到输入框，
     * 让用户看到被修正后的结果。保存立即生效——服务端每次加锁时都会重新读取设置。
     */
    private fun saveOverlaySettings() {
        val inputText = binding.etOverlayDurationMs.text?.toString()?.trim().orEmpty()
        val inputValue = inputText.toLongOrNull()
        if (inputValue == null) {
            Toast.makeText(this, R.string.settings_invalid_duration, Toast.LENGTH_LONG).show()
            return
        }

        settings.overlayVisibleDurationMs = inputValue
        settings.keepLockedAfterOverlayHidden = binding.cbKeepLockedAfterHide.isChecked

        // 回填被限制后的实际值，并给出保存成功提示
        binding.etOverlayDurationMs.setText(settings.overlayVisibleDurationMs.toString())
        Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
    }

    /**
     * 把最近一次崩溃的堆栈显示在界面上（没有崩溃记录时什么都不做）。
     *
     * 手机上不一定方便连 adb 查看 logcat，这里相当于把崩溃日志直接呈现给用户；
     * 日志文件同时保存在应用内部与外部私有目录：Android/data/com.example.touchlock/files/touchlock_crash.txt
     */
    private fun showLastCrashIfAny() {
        val crashText = TouchLockApp.readLastCrash(filesDir) ?: return
        val originalText = getString(R.string.main_description)
        val crashTitle = getString(R.string.crash_report_title)
        binding.tvDescription.text = "$originalText\n\n$crashTitle\n$crashText"
    }

    /**
     * 每次回到前台都刷新一次无障碍服务状态。
     * 用户很可能是刚从系统设置页返回，开关状态可能已经变化。
     */
    override fun onResume() {
        super.onResume()
        refreshAccessibilityStatus()
    }

    /**
     * 刷新界面上显示的无障碍服务状态。
     */
    private fun refreshAccessibilityStatus() {
        val isEnabled = isAccessibilityServiceEnabled()

        // 状态文案：已开启 / 未开启
        binding.tvServiceStatus.setText(
            if (isEnabled) {
                R.string.accessibility_status_enabled
            } else {
                R.string.accessibility_status_disabled
            }
        )

        // 未开启时用主题的 error 色强调，提示用户还需要手动开启；
        // 已开启时恢复为普通的正文颜色（两者都取自当前主题，日夜模式自动适配）
        binding.tvServiceStatus.setTextColor(
            if (isEnabled) {
                MaterialColors.getColor(binding.tvServiceStatus, android.R.attr.textColorPrimary)
            } else {
                MaterialColors.getColor(
                    binding.tvServiceStatus,
                    com.google.android.material.R.attr.colorError
                )
            }
        )

        // 只有未开启时才显示「请去开启」的操作提示
        binding.tvServiceStatusHint.visibility = if (isEnabled) View.GONE else View.VISIBLE
    }

    /**
     * 判断 TouchLock 的无障碍服务当前是否已开启。
     *
     * 读取 Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES，
     * 该设置项的值是若干个组件名用 ':' 连接成的字符串，
     * 例如 com.example.touchlock/com.example.touchlock.TouchLockAccessibilityService。
     * 逐个反解析成 ComponentName 再与目标组件比较，可同时兼容
     * 「包名/类全名」与「包名/.类名」两种写法。
     */
    private fun isAccessibilityServiceEnabled(): Boolean {
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
        if (enabledServices.isNullOrEmpty()) {
            return false
        }

        // 目标组件：本应用的 TouchLockAccessibilityService
        val expectedComponent = ComponentName(this, TouchLockAccessibilityService::class.java)

        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabledServices)
        while (splitter.hasNext()) {
            val componentName = ComponentName.unflattenFromString(splitter.next())
            if (componentName == expectedComponent) {
                return true
            }
        }
        return false
    }

    /**
     * 跳转到系统无障碍设置页面，供用户手动开启 TouchLock 服务。
     */
    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            // 极少数定制系统没有该设置页，给出提示而不是直接崩溃
            Toast.makeText(
                this,
                R.string.accessibility_settings_not_found,
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
