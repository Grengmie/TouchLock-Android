package com.example.touchlock

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * 全局按键接收服务。
 *
 * 职责：
 * 1. 通过无障碍服务的「过滤按键事件」能力接收物理按键（无需 Root、不修改系统设置）；
 * 2. 交给 KeyComboDetector 判定「音量上 + 音量下」组合键；
 * 3. 组合键命中时通过 LockOverlayManager 切换全屏触摸锁定覆盖层；
 * 4. 锁定期间显示常驻通知，并提供解锁入口；
 * 5. 防锁死：熄屏自动解锁、锁定 10 分钟后自动解锁。
 *
 * 说明：TYPE_ACCESSIBILITY_OVERLAY 类型的窗口只能由无障碍服务添加，
 * 因此触摸锁定覆盖层的生命周期由本服务持有。
 */
class TouchLockAccessibilityService : AccessibilityService() {

    companion object {
        /**
         * 日志 TAG，统一使用 TouchLock，
         * 便于按 AGENTS.md 中的说明执行 adb logcat -v time | grep -i touchlock 过滤。
         */
        private const val TAG = "TouchLock"

        /** 锁定超时时间：10 分钟，超时后自动解锁 */
        private const val AUTO_UNLOCK_TIMEOUT_MS = 10 * 60 * 1000L

        /** 通知渠道 ID：Android 8.0 起通知必须归属某个渠道 */
        private const val NOTIFICATION_CHANNEL_ID = "touchlock_lock_state"

        /** 锁定状态通知的固定 ID，用于后续取消 */
        private const val NOTIFICATION_ID = 1

        /** 点击通知解锁时使用的应用内广播动作，仅本应用使用 */
        private const val ACTION_UNLOCK = "com.example.touchlock.action.UNLOCK"
    }

    /** 组合键检测器（纯状态机，不持有 Context） */
    private val keyComboDetector = KeyComboDetector()

    /**
     * 触摸锁定覆盖层管理器。
     * 传入服务自身作为 Context：TYPE_ACCESSIBILITY_OVERLAY 依赖服务的 WindowToken。
     */
    private val lockOverlayManager = LockOverlayManager(this)

    /**
     * 当前锁定状态。
     * 这是调用 show()/hide()、发送/取消通知的唯一依据，保证不会重复执行。
     */
    private var isLocked = false

    /** 主线程 Handler，用于投递「锁定超时自动解锁」任务 */
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 设置读取（懒加载）。
     * 不能写成字段初始化的 `TouchLockSettings(this)`：服务 attach 之前还没有可用的
     * Context（取 SharedPreferences 需要它），lazy 保证第一次使用时才创建。
     */
    private val settings: TouchLockSettings by lazy { TouchLockSettings(this) }

    /** 超时自动解锁任务，锁定成功时投递，解锁/熄屏/服务销毁时取消 */
    private val autoUnlockRunnable = Runnable {
        Log.i(TAG, "锁定已超过 10 分钟，自动解除触摸锁定")
        unlock()
    }

    /**
     * 「遮罩自动隐藏」任务。
     *
     * 默认行为：只隐藏遮罩的视觉内容，窗口保留并继续消费触摸，
     * 这样锁定时屏幕不被灰色遮罩挡住，可以正常看视频；
     * 若用户在首页把「隐藏后继续保持触摸锁定」取消勾选，则到点直接解锁。
     */
    private val overlayHideRunnable = Runnable {
        try {
            if (settings.keepLockedAfterOverlayHidden) {
                lockOverlayManager.hideVisuals()
                Log.i(TAG, "遮罩已自动隐藏，触摸锁定继续生效")
            } else {
                Log.i(TAG, "遮罩显示时长已到，按设置自动解锁")
                unlock()
            }
        } catch (e: Throwable) {
            Log.e(TAG, "自动隐藏遮罩失败", e)
        }
    }

    /** 应用内广播是否已注册，避免重复注册/重复反注册 */
    private var isActionReceiverRegistered = false

    /**
     * 应用内广播接收器，处理两类解锁场景：
     * - ACTION_SCREEN_OFF：熄屏自动解锁（防锁死）；
     * - ACTION_UNLOCK：点击锁定通知上的解锁入口。
     */
    private val actionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    Log.i(TAG, "收到熄屏广播，自动解除触摸锁定")
                    unlock()
                }

                ACTION_UNLOCK -> {
                    Log.i(TAG, "收到通知点击事件，解除触摸锁定")
                    unlock()
                }
            }
        }
    }

    /**
     * 服务连接成功后的回调。
     *
     * 组件对象在字段初始化时就已创建（这样 onUnbind/onDestroy 等清理回调不会碰到未初始化状态），
     * 这里负责把它们复位到干净状态、创建通知渠道，并注册应用内广播。
     */
    override fun onServiceConnected() {
        super.onServiceConnected()

        // 整个初始化过程都包在 try/catch 里：
        // onServiceConnected 抛异常会让无障碍服务直接崩溃，而系统会反复重新绑定已启用的服务，
        // 用户看到的就是「TouchLock 屡次停止运行」。这里任何一步失败都只记录日志，保证服务存活。
        try {
            // 1) 再次显式请求过滤按键事件：
            // 部分 ROM 在服务重启后不会重新读取 XML 中的 accessibilityFlags，
            // 必须在代码里补上 FLAG_REQUEST_FILTER_KEY_EVENTS 再写回，否则 onKeyEvent 收不到音量键事件。
            // serviceInfo 理论上由系统在连接时提供；万一为空也不提前返回，
            // 因为后面的通知渠道与广播注册同样重要（按键过滤仍由 XML 配置保证）。
            val info = serviceInfo
            if (info != null) {
                info.flags = info.flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
                setServiceInfo(info)
            } else {
                Log.w(TAG, "serviceInfo 为空，跳过 flags 补写；按键过滤仍由 XML 配置生效")
            }

            // 2) 初始化/复位状态：清空按键状态、取消超时任务与残留通知、确保没有残留覆盖层
            keyComboDetector.reset()
            cancelAutoUnlockTask()
            cancelOverlayHideTask()
            cancelLockNotification()
            isLocked = false
            lockOverlayManager.hide()

            // 3) 创建通知渠道（Android 8.0 起发送通知前必须存在对应渠道）
            createNotificationChannel()

            // 4) 注册广播：熄屏自动解锁 + 点击通知解锁
            registerActionReceiver()

            Log.i(TAG, "无障碍服务已连接，当前 flags=${info?.flags}")
        } catch (e: Throwable) {
            Log.e(TAG, "无障碍服务初始化失败，已忽略以保证服务不崩溃", e)
        }
    }

    /**
     * 物理按键回调。
     *
     * 命中组合键时先切换锁定状态，再返回 true 消费这次按键；
     * 其余事件一律交回父类处理，保证单击音量键调节音量的行为不变。
     *
     * 说明：组合键的第一个按键事件在此前已经作为普通事件下传（无法追回），
     * 因此切换锁定时音量仍可能变化一格，这是无障碍按键过滤的固有限制。
     */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        Log.d(
            TAG,
            "onKeyEvent: action=${event.action}, keyCode=${event.keyCode}, repeatCount=${event.repeatCount}"
        )

        // 按键回调同样不能抛异常：无障碍服务的按键回调崩溃同样会导致服务被系统重启。
        try {
            if (keyComboDetector.onKeyEvent(event) == KeyComboDetector.Action.TOGGLE) {
                // 组合键命中：切换锁定状态，并消费本次按键（返回 true 表示事件不再下传）
                toggleLock()
                return true
            }
            return super.onKeyEvent(event)
        } catch (e: Throwable) {
            Log.e(TAG, "处理按键事件失败，已忽略本次事件", e)
            return false
        }
    }

    /**
     * 切换触摸锁定状态：未锁定则加锁，已锁定则解锁。
     */
    private fun toggleLock() {
        if (isLocked) {
            unlock()
        } else {
            lock()
        }
    }

    /**
     * 加锁：显示覆盖层与常驻通知，并投递 10 分钟超时任务。
     * 已经处于锁定状态时直接返回，避免重复 show()/重复通知。
     */
    private fun lock() {
        if (isLocked) {
            return
        }
        try {
            isLocked = true
            lockOverlayManager.show()
            showLockNotification()
            scheduleAutoUnlock()
            scheduleOverlayVisualHide()
            Log.i(
                TAG,
                "已开启触摸锁定：遮罩显示 ${settings.overlayVisibleDurationMs}ms，" +
                    "10 分钟后自动解锁"
            )
        } catch (e: Throwable) {
            // 加锁过程中出错时回滚状态，避免出现「状态已锁定但覆盖层没显示」的假死情况
            isLocked = false
            cancelAutoUnlockTask()
            cancelOverlayHideTask()
            lockOverlayManager.hide()
            Log.e(TAG, "开启触摸锁定失败", e)
        }
    }

    /**
     * 解锁：取消超时任务、移除覆盖层、取消通知。
     * 组合键解锁、熄屏解锁、超时解锁都走这里；本来没锁定时直接返回。
     */
    private fun unlock() {
        if (!isLocked) {
            return
        }
        try {
            isLocked = false
            // 先取消两个延时任务，避免解锁后残留的任务再次触发
            cancelAutoUnlockTask()
            cancelOverlayHideTask()
            lockOverlayManager.hide()
            cancelLockNotification()
            Log.i(TAG, "已解除触摸锁定")
        } catch (e: Throwable) {
            Log.e(TAG, "解除触摸锁定失败", e)
        }
    }

    /**
     * 投递「锁定超时自动解锁」任务。
     */
    private fun scheduleAutoUnlock() {
        // 先清掉可能存在的旧任务，避免重复投递
        cancelAutoUnlockTask()
        mainHandler.postDelayed(autoUnlockRunnable, AUTO_UNLOCK_TIMEOUT_MS)
    }

    /**
     * 取消「锁定超时自动解锁」任务。
     * 解锁、熄屏解锁、服务连接与销毁时都会调用，避免内存泄漏或误解锁。
     */
    private fun cancelAutoUnlockTask() {
        mainHandler.removeCallbacks(autoUnlockRunnable)
    }

    /**
     * 投递「遮罩自动隐藏」任务，时长由用户在首页设置（默认 300ms）。
     */
    private fun scheduleOverlayVisualHide() {
        // 先清掉可能存在的旧任务，避免重复投递
        cancelOverlayHideTask()
        mainHandler.postDelayed(overlayHideRunnable, settings.overlayVisibleDurationMs)
    }

    /**
     * 取消「遮罩自动隐藏」任务。
     * 解锁、熄屏解锁、服务连接与销毁时都会调用。
     */
    private fun cancelOverlayHideTask() {
        mainHandler.removeCallbacks(overlayHideRunnable)
    }

    /**
     * 创建通知渠道。
     * 本应用最低支持 Android 8.0（API 26），而 NotificationChannel 正是 API 26 引入的，
     * 因此这里不需要做版本判断。
     */
    private fun createNotificationChannel() {
        try {
            val notificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            if (notificationManager == null) {
                Log.w(TAG, "无法获取 NotificationManager，通知渠道未创建")
                return
            }

            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.notification_channel_name),
                // 低优先级：不发出声音、不弹提示，只在通知栏展示状态
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_description)
                // 状态类通知不需要角标
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        } catch (e: Exception) {
            // 通知渠道创建失败不影响触摸锁定本身，只记录日志
            Log.e(TAG, "创建通知渠道失败", e)
        }
    }

    /**
     * 显示锁定状态通知。
     * 通知为常驻（不可划掉），点击后通过应用内广播解除锁定。
     */
    private fun showLockNotification() {
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            // 小图标：直接复用系统内置的锁图标，避免额外新增 drawable 资源
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle(getString(R.string.notification_locked_title))
            .setContentText(getString(R.string.notification_locked_text))
            // 锁定期间常驻，避免用户误划掉后不知道当前处于锁定状态
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            // 点击通知即可解锁
            .setContentIntent(createUnlockPendingIntent())
            .build()

        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
            Log.i(TAG, "已显示锁定状态通知")
        } catch (e: Exception) {
            // 通知权限被系统限制、渠道不存在等情况都不影响锁定功能本身，只记录日志
            Log.w(TAG, "显示锁定状态通知失败，忽略", e)
        }
    }

    /**
     * 取消锁定状态通知，重复调用是安全的。
     */
    private fun cancelLockNotification() {
        try {
            NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID)
        } catch (e: Exception) {
            Log.w(TAG, "取消锁定状态通知失败，忽略", e)
        }
    }

    /**
     * 构造「点击通知解锁」的 PendingIntent。
     * 使用显式 Intent（带包名）指向本应用动态注册的接收器，其它应用无法触发。
     */
    private fun createUnlockPendingIntent(): PendingIntent {
        val intent = Intent(ACTION_UNLOCK).setPackage(packageName)
        return PendingIntent.getBroadcast(
            this,
            0,
            intent,
            // FLAG_IMMUTABLE 需要 API 23，本应用最低 API 26，可安全使用
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * 注册应用内广播（熄屏自动解锁、点击通知解锁）。
     *
     * Android 13（API 33）起动态注册广播必须显式声明导出状态，此时才使用
     * ContextCompat.RECEIVER_NOT_EXPORTED；
     * Android 13 以下没有这个限制，直接用系统最原始的注册方式。
     *
     * 注意：不能在 API 33 以下的系统上传入 RECEIVER_NOT_EXPORTED。
     * androidx.core 在低版本上会退化成「用 <包名>.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION
     * 这条签名级权限保护接收器」，一旦清单里没有声明该权限就会直接抛
     * RuntimeException（Permission ... is required by your application to receive broadcasts），
     * 这条异常在 onServiceConnected 中抛出会让无障碍服务崩溃并被系统反复重启。
     * 本应用的 targetSdk 是 26，低版本上动态接收器本来就只对本应用可见，无需额外标志。
     */
    private fun registerActionReceiver() {
        if (isActionReceiverRegistered) {
            return
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(ACTION_UNLOCK)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.registerReceiver(
                    this,
                    actionReceiver,
                    filter,
                    ContextCompat.RECEIVER_NOT_EXPORTED
                )
            } else {
                registerReceiver(actionReceiver, filter)
            }
            isActionReceiverRegistered = true
            Log.i(TAG, "已注册熄屏与通知解锁广播")
        } catch (e: Exception) {
            // 注册失败只影响熄屏解锁与通知点击解锁，锁定/组合键解锁仍然可用
            isActionReceiverRegistered = false
            Log.e(TAG, "注册应用内广播失败", e)
        }
    }

    /**
     * 反注册应用内广播，重复调用是安全的。
     */
    private fun unregisterActionReceiver() {
        if (!isActionReceiverRegistered) {
            return
        }
        try {
            unregisterReceiver(actionReceiver)
        } catch (e: IllegalArgumentException) {
            // 极端情况下（已被系统反注册）会抛该异常，忽略即可
            Log.w(TAG, "广播反注册失败，忽略", e)
        }
        isActionReceiverRegistered = false
    }

    /**
     * 无障碍事件回调。
     * 本应用只关心物理按键，不需要窗口内容变化等事件，因此留空实现。
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 暂无处理逻辑
    }

    /**
     * 服务被系统中断时的回调（例如用户关闭无障碍开关前的系统调用）。
     * 重置组合键状态，避免残留的按下/冷却状态影响下次判定。
     */
    override fun onInterrupt() {
        keyComboDetector.reset()
    }

    /**
     * 服务被系统解绑（例如用户关闭无障碍开关）时的回调。
     * 覆盖层窗口会随解绑失去归属，这里主动解锁（同时取消通知与超时任务）。
     */
    override fun onUnbind(intent: Intent?): Boolean {
        try {
            keyComboDetector.reset()
            unlock()
            // 双保险：即使锁定状态与实际窗口不一致，也确保覆盖层被移除
            lockOverlayManager.hide()
        } catch (e: Throwable) {
            Log.e(TAG, "服务解绑清理失败", e)
        }
        return super.onUnbind(intent)
    }

    /**
     * 服务销毁时的回调：取消超时任务、反注册广播、隐藏覆盖层、取消通知、重置组合键状态。
     */
    override fun onDestroy() {
        try {
            // 取消可能还在等待的延时任务，避免服务销毁后触发误解锁
            cancelAutoUnlockTask()
            cancelOverlayHideTask()
            // 反注册应用内广播
            unregisterActionReceiver()
            // 隐藏覆盖层、取消通知并复位锁定状态（destroy 路径不再走 isLocked 守卫）
            isLocked = false
            lockOverlayManager.hide()
            cancelLockNotification()
            // 重置组合键检测状态
            keyComboDetector.reset()
        } catch (e: Throwable) {
            Log.e(TAG, "服务销毁清理失败", e)
        }
        super.onDestroy()
    }
}
