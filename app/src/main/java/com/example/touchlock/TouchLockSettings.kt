package com.example.touchlock

import android.content.Context
import android.content.SharedPreferences

/**
 * 应用设置存取（SharedPreferences 封装）。
 *
 * 目前只有两项设置，都跟「锁定覆盖层显示多久」有关：
 * 1. [overlayVisibleDurationMs]：遮罩显示时长（毫秒），到点后遮罩视觉部分自动隐藏；
 * 2. [keepLockedAfterOverlayHidden]：遮罩隐藏后是否继续保持触摸锁定。
 *
 * 服务端每次加锁时都会重新读取这些值，因此在首页改完设置后，
 * 下一次按组合键锁定就会生效，不需要重启服务或重装应用。
 */
class TouchLockSettings(context: Context) {

    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 遮罩显示时长（毫秒）。
     * 0 表示一出现就立即隐藏（实际效果是几乎看不到遮罩）；
     * 写入时会被限制在 [MIN_DURATION_MS] ~ [MAX_DURATION_MS] 之间，避免手抖输入极端值。
     */
    var overlayVisibleDurationMs: Long
        get() = preferences.getLong(KEY_OVERLAY_DURATION_MS, DEFAULT_DURATION_MS)
        set(value) {
            val clamped = value.coerceIn(MIN_DURATION_MS, MAX_DURATION_MS)
            preferences.edit().putLong(KEY_OVERLAY_DURATION_MS, clamped).apply()
        }

    /** 遮罩隐藏后是否继续保持触摸锁定（默认保持，这样锁定时可以正常看视频） */
    var keepLockedAfterOverlayHidden: Boolean
        get() = preferences.getBoolean(KEY_KEEP_LOCKED, true)
        set(value) {
            preferences.edit().putBoolean(KEY_KEEP_LOCKED, value).apply()
        }

    companion object {
        /** SharedPreferences 文件名 */
        private const val PREFS_NAME = "touchlock_settings"

        /** 键：遮罩显示时长（毫秒） */
        private const val KEY_OVERLAY_DURATION_MS = "overlay_visible_duration_ms"

        /** 键：遮罩隐藏后是否保持触摸锁定 */
        private const val KEY_KEEP_LOCKED = "keep_locked_after_overlay_hidden"

        /** 默认显示时长：0.3 秒 */
        const val DEFAULT_DURATION_MS = 300L

        /** 允许的最小值：0 毫秒 */
        const val MIN_DURATION_MS = 0L

        /** 允许的最大值：10 秒 */
        const val MAX_DURATION_MS = 10_000L
    }
}
