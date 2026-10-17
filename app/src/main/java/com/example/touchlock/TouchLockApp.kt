package com.example.touchlock

import android.app.Application
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 应用入口类，在 AndroidManifest.xml 中通过 android:name=".TouchLockApp" 注册。
 *
 * 主要职责：安装全局未捕获异常处理器，把崩溃堆栈写入文件。
 *
 * 为什么需要它：本项目的出包流程是手工工具链（aapt2 + kotlinc + d8），
 * 测试时手机上不一定方便连 adb 看 logcat，一旦出现「屡次停止运行」就无法定位原因。
 * 有了这里的记录，MainActivity 启动时会把最近一次崩溃的堆栈直接显示在界面上，
 * 用截图就能反馈，同时文件也可以随时导出。
 */
class TouchLockApp : Application() {

    override fun onCreate() {
        super.onCreate()
        installCrashHandler()
    }

    /**
     * 安装全局未捕获异常处理器。
     * 记录完成后仍会交回系统原有的处理器，保证系统照常弹出崩溃提示并结束进程。
     */
    private fun installCrashHandler() {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                saveCrashLog(thread, throwable)
            } catch (e: Throwable) {
                // 记录失败也不能影响正常的崩溃流程
                Log.e(TAG, "保存崩溃日志失败", e)
            }
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    /**
     * 把异常堆栈写入应用内部文件，并额外写一份到外部私有目录（便于导出）。
     */
    private fun saveCrashLog(thread: Thread, throwable: Throwable) {
        val stringWriter = StringWriter()
        throwable.printStackTrace(PrintWriter(stringWriter))
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val content = "时间：$time\n线程：${thread.name}\n$stringWriter"

        Log.e(TAG, "捕获到未处理异常：\n$content")

        File(filesDir, CRASH_FILE_NAME).writeText(content)

        try {
            getExternalFilesDir(null)?.let { dir ->
                File(dir, CRASH_FILE_NAME).writeText(content)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "写入外部崩溃日志失败，忽略", e)
        }
    }

    companion object {
        /** 日志 TAG，与其它组件保持一致 */
        private const val TAG = "TouchLock"

        /** 崩溃日志文件名（内部存储与外部私有目录各写一份） */
        const val CRASH_FILE_NAME = "touchlock_crash.txt"

        /**
         * 读取最近一次崩溃日志，不存在或读取失败时返回 null。
         *
         * @param filesDir 应用的内部文件目录（Context.getFilesDir()）
         */
        fun readLastCrash(filesDir: File): String? {
            val file = File(filesDir, CRASH_FILE_NAME)
            if (!file.exists()) {
                return null
            }
            return try {
                file.readText()
            } catch (e: Throwable) {
                Log.w(TAG, "读取崩溃日志失败，忽略", e)
                null
            }
        }
    }
}
