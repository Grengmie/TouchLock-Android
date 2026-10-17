package com.example.touchlock

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import com.example.touchlock.databinding.OverlayLockBinding

/**
 * 触摸锁定覆盖层管理类。
 *
 * 负责在屏幕上添加/移除一个全屏的透明遮罩窗口：
 * - 窗口类型使用 TYPE_ACCESSIBILITY_OVERLAY，只能由无障碍服务添加（Android 8.0 可用）；
 * - 窗口本身可触摸，并通过 OnTouchListener 返回 true 消费所有手势，避免误触下层应用；
 * - 绝对不能添加 FLAG_NOT_TOUCHABLE，否则触摸会直接穿透到下层，锁定就失效了。
 *
 * 除了整块遮罩的 [show] / [hide]，还提供只隐藏视觉内容的 [hideVisuals]：
 * 窗口保留、触摸继续被消费，但屏幕上不再有灰色遮罩，方便锁定期间看视频。
 *
 * 所有方法都要求在服务的主线程上调用（WindowManager 的视图只能在创建它的线程上操作）。
 *
 * @param context 必须是无障碍服务自身的 Context。TYPE_ACCESSIBILITY_OVERLAY 依赖
 *                服务连接对应的 WindowToken，换成 applicationContext 会导致 addView
 *                抛出 BadTokenException。
 */
class LockOverlayManager(private val context: Context) {

    companion object {
        /** 日志 TAG，与其它组件保持一致，便于统一过滤 */
        private const val TAG = "TouchLock"

        /** 窗口标题，仅用于 adb shell dumpsys window 排查，界面上不可见 */
        private const val WINDOW_TITLE = "TouchLockOverlay"
    }

    /**
     * 当前显示的覆盖层绑定对象；为 null 表示当前没有锁定。
     * 保存绑定对象而不是单纯的根视图，是为了在自动隐藏时能同时隐藏图标与文字。
     */
    private var overlayBinding: OverlayLockBinding? = null

    /** 当前显示的覆盖层根视图；为 null 表示当前没有锁定 */
    private val overlayView: View?
        get() = overlayBinding?.root

    /** 当前是否正在显示锁定覆盖层 */
    val isShowing: Boolean
        get() = overlayBinding != null

    /** 覆盖层使用的 WindowManager，来自服务 Context */
    private val windowManager: WindowManager?
        get() = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager

    /**
     * 显示锁定覆盖层。
     * 重复调用是安全的：已经在显示时直接忽略，避免同一个视图被 addView 两次而崩溃。
     */
    fun show() {
        if (overlayView != null) {
            Log.w(TAG, "锁定覆盖层已经在显示，忽略本次 show() 调用")
            return
        }

        val wm = windowManager
        if (wm == null) {
            Log.e(TAG, "无法获取 WindowManager，锁定覆盖层未能显示")
            return
        }

        // 加载布局：根视图是撑满全屏的 FrameLayout
        val binding = OverlayLockBinding.inflate(LayoutInflater.from(context))
        val view = binding.root

        // 消费所有触摸事件：返回 true 表示手势到此为止，不再传给下层窗口
        view.setOnTouchListener { _, _ -> true }

        val params = WindowManager.LayoutParams(
            // 宽高都占满屏幕
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            // 无障碍服务专用的覆盖层窗口类型（不要用 TYPE_APPLICATION_OVERLAY）
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // 不获取输入焦点，但保持可触摸；此处绝对不能加 FLAG_NOT_TOUCHABLE
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            // 半透明格式，配合布局里的半透明黑背景
            PixelFormat.TRANSLUCENT
        ).apply {
            // 左上角为原点，配合 FLAG_LAYOUT_NO_LIMITS 覆盖到状态栏/导航栏区域
            gravity = Gravity.TOP or Gravity.START
            // 只用于 dumpsys window 时识别本窗口，方便排查
            setTitle(WINDOW_TITLE)
        }

        try {
            wm.addView(view, params)
            overlayBinding = binding
            Log.i(TAG, "触摸锁定覆盖层已显示")
        } catch (e: Exception) {
            // 窗口 token 失效、系统限制等极端情况：不要让无障碍服务崩溃
            overlayBinding = null
            Log.e(TAG, "添加触摸锁定覆盖层失败", e)
        }
    }

    /**
     * 只隐藏覆盖层的视觉内容（半透明背景、锁图标、提示文字），窗口本身保留。
     *
     * 因为窗口还在，触摸事件依然会被消费，所以「触摸锁定」继续生效，
     * 只是屏幕上不再有灰色遮罩——锁定时看视频不会被遮挡。
     * 每次加锁都会重新加载布局，因此这里不需要恢复视觉内容。
     */
    fun hideVisuals() {
        val binding = overlayBinding
        if (binding == null) {
            // 当前没有覆盖层，直接返回（支持重复调用）
            return
        }

        try {
            // 背景设为全透明（不是给窗口设 alpha=0：窗口完全透明后系统可能不再把它算作可触摸窗口）
            binding.root.setBackgroundColor(Color.TRANSPARENT)
            // 图标与文字一起隐藏
            binding.ivLockIcon.visibility = View.INVISIBLE
            binding.tvLockHint.visibility = View.INVISIBLE
            Log.i(TAG, "覆盖层视觉部分已隐藏，触摸锁定仍然生效")
        } catch (e: Exception) {
            Log.e(TAG, "隐藏覆盖层视觉部分失败", e)
        }
    }

    /**
     * 移除锁定覆盖层。
     * 重复调用是安全的：未显示时直接返回；移除过程中出现异常也会先把引用清空，
     * 保证不会因为重复 removeView 而崩溃。
     */
    fun hide() {
        val binding = overlayBinding
        if (binding == null) {
            // 当前没有覆盖层，直接返回（支持重复调用）
            return
        }

        // 先清空引用：即使随后 removeView 抛异常，也不会留下失效引用导致重复移除
        overlayBinding = null

        val wm = windowManager
        if (wm == null) {
            Log.e(TAG, "无法获取 WindowManager，覆盖层视图未能移除")
            return
        }

        try {
            wm.removeView(binding.root)
            Log.i(TAG, "触摸锁定覆盖层已隐藏")
        } catch (e: IllegalArgumentException) {
            // 视图已经被系统移除（例如服务被销毁），这里忽略即可
            Log.w(TAG, "覆盖层视图已不存在，忽略本次移除", e)
        }
    }
}
