package com.example.touchlock

import android.os.SystemClock
import android.view.KeyEvent
import kotlin.math.abs

/**
 * 「音量上 + 音量下」组合键检测器。
 *
 * 判定规则：
 * 1. 只关心 KEYCODE_VOLUME_UP 与 KEYCODE_VOLUME_DOWN；
 * 2. 两个键必须在 [DEFAULT_COMBO_WINDOW_MS]（500ms）内都处于按下状态，才算触发一次；
 * 3. 忽略 repeatCount > 0 的重复事件（长按产生的连发事件）；
 * 4. 触发后进入冷却：必须先等到两个键都抬起，才允许下一次触发，避免长按反复触发。
 *
 * 该类为纯状态机，不持有 Context，也不做任何 UI 操作，方便后续单独测试。
 *
 * @param comboWindowMs 两键按下的最大时间差，默认 500ms
 * @param elapsedTimeMs 时间来源，默认使用单调递增的 SystemClock.uptimeMillis()
 *                      （不受用户修改系统时间影响），预留以便测试时替换
 */
class KeyComboDetector(
    private val comboWindowMs: Long = DEFAULT_COMBO_WINDOW_MS,
    private val elapsedTimeMs: () -> Long = { SystemClock.uptimeMillis() }
) {

    /** 按键事件的处理结果 */
    enum class Action {
        /** 不是组合键，无需处理 */
        NONE,

        /** 两个音量键已在时间窗口内同时按下，需要切换触摸锁定状态 */
        TOGGLE
    }

    companion object {
        /** 组合键判定窗口：500ms */
        const val DEFAULT_COMBO_WINDOW_MS = 500L
    }

    /** 音量上键当前是否按下 */
    private var isVolumeUpPressed = false

    /** 音量下键当前是否按下 */
    private var isVolumeDownPressed = false

    /** 音量上键本次按下的时间戳（毫秒） */
    private var volumeUpPressedAtMs = 0L

    /** 音量下键本次按下的时间戳（毫秒） */
    private var volumeDownPressedAtMs = 0L

    /**
     * 冷却标记。
     * 触发过一次 TOGGLE 后置为 true，直到两个键都抬起才复位。
     */
    private var isWaitingForRelease = false

    /**
     * 处理一个按键事件。
     *
     * @return [Action.TOGGLE] 表示本次事件构成了组合键触发；否则返回 [Action.NONE]。
     */
    fun onKeyEvent(event: KeyEvent): Action {
        // 只处理两个音量键，其它按键直接忽略，且不改变任何状态
        val isVolumeUp = event.keyCode == KeyEvent.KEYCODE_VOLUME_UP
        val isVolumeDown = event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        if (!isVolumeUp && !isVolumeDown) {
            return Action.NONE
        }

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                // 长按产生的重复事件（repeatCount > 0）直接忽略
                if (event.repeatCount > 0) {
                    return Action.NONE
                }

                // 冷却期内忽略新的按下，必须等两个键都抬起
                if (isWaitingForRelease) {
                    return Action.NONE
                }

                // 记录本次按下
                val now = elapsedTimeMs()
                if (isVolumeUp) {
                    isVolumeUpPressed = true
                    volumeUpPressedAtMs = now
                } else {
                    isVolumeDownPressed = true
                    volumeDownPressedAtMs = now
                }

                // 两个键都按下后，判断按下时间差是否在 500ms 窗口内
                if (isVolumeUpPressed && isVolumeDownPressed &&
                    abs(volumeUpPressedAtMs - volumeDownPressedAtMs) <= comboWindowMs
                ) {
                    // 进入冷却：两键都抬起后才能再次触发
                    isWaitingForRelease = true
                    return Action.TOGGLE
                }

                return Action.NONE
            }

            KeyEvent.ACTION_UP -> {
                // 松开按键，清除对应的按下状态
                if (isVolumeUp) {
                    isVolumeUpPressed = false
                } else {
                    isVolumeDownPressed = false
                }

                // 两个键都抬起后解除冷却，允许下一次触发
                if (isWaitingForRelease && !isVolumeUpPressed && !isVolumeDownPressed) {
                    isWaitingForRelease = false
                }

                return Action.NONE
            }

            else -> {
                // ACTION_MULTIPLE 等其它动作不参与判定
                return Action.NONE
            }
        }
    }

    /**
     * 重置全部状态。
     *
     * 服务销毁、被系统中断或重新连接时调用，
     * 避免上一次遗留的「按下中 / 冷却中」状态影响后续判定。
     */
    fun reset() {
        isVolumeUpPressed = false
        isVolumeDownPressed = false
        volumeUpPressedAtMs = 0L
        volumeDownPressedAtMs = 0L
        isWaitingForRelease = false
    }
}
