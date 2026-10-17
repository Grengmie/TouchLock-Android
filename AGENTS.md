# AGENTS.md — TouchLock 项目

## 0. 项目目标
TouchLock 是一个 Android 8.0 (API 26) 可用的个人辅助应用。
用户同时按下「音量上 + 音量下」组合键，切换触摸锁定状态：
- 锁定：显示全屏透明覆盖层，消费所有触摸事件，防止误触。
- 解锁：再次按组合键解锁，或熄屏自动解锁，或超时自动解锁。
- 不做 Root，不锁屏，不修改系统设置。

## 1. 平台与环境
- 最低系统：Android 8.0 (API 26)
- 语言：Kotlin
- UI：AndroidX，View 体系，不使用 Compose
- 构建：Gradle Kotlin DSL
- compileSdk 34，minSdk 26，targetSdk 26
- 包名：com.example.touchlock
- 应用名：TouchLock
- 建议 AGP / JDK 与 Android Studio 新建项目一致；compileSdk 34 通常需要 JDK 17。

## 2. 核心方案
1. AccessibilityService 接收全局按键
   - XML 中设置 android:canRequestFilterKeyEvents="true"
   - XML 中 accessibilityFlags 包含 flagRequestFilterKeyEvents
   - onServiceConnected 中再次设置：
     AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
   - 然后调用 setServiceInfo(serviceInfo)

2. 组合键检测
   - 检测 KEYCODE_VOLUME_UP 和 KEYCODE_VOLUME_DOWN
   - 两个键在 500ms 内都按下时，触发 TOGGLE
   - 触发后必须等待两键抬起并重置，防止长按重复触发
   - 忽略 repeatCount > 0 的重复按键事件

3. 触摸锁定覆盖层
   - 使用 WindowManager
   - 窗口类型：WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
   - 宽高：MATCH_PARENT
   - flags：
     FLAG_NOT_FOCUSABLE or FLAG_LAYOUT_IN_SCREEN or FLAG_LAYOUT_NO_LIMITS
   - 禁止添加 FLAG_NOT_TOUCHABLE
   - setOnTouchListener 返回 true，消费所有触摸事件
   - 布局：半透明黑色背景 + 锁图标 + 提示文字

4. 防锁死
   - 监听 Intent.ACTION_SCREEN_OFF，熄屏自动解锁
   - 锁定 10 分钟后自动解锁
   - 再次按音量上 + 下组合键解锁

5. 设置引导
   - MainActivity 检查无障碍服务是否开启
   - 提供按钮跳转 Settings.ACTION_ACCESSIBILITY_SETTINGS
   - 返回应用时刷新无障碍服务状态

## 3. 关键 API 与禁止项
必须使用：
- AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
- XML：android:canRequestFilterKeyEvents="true"
- WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
- WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
- WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
- WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

禁止：
- 禁止使用 TYPE_APPLICATION_OVERLAY
- 禁止给覆盖层添加 FLAG_NOT_TOUCHABLE
- 禁止使用 API 27+ 专属 API，除非做版本判断
- 禁止输出省略 import 的代码
- 禁止使用 ... 省略代码
- 禁止假设已有代码，每个文件都输出完整版

## 4. 目录结构
app/src/main/java/com/example/touchlock/
  MainActivity.kt
  TouchLockAccessibilityService.kt
  KeyComboDetector.kt
  LockOverlayManager.kt

app/src/main/res/layout/
  activity_main.xml
  overlay_lock.xml

app/src/main/res/xml/
  accessibility_service_config.xml

app/src/main/res/values/
  strings.xml
  themes.xml

app/src/main/AndroidManifest.xml

项目根目录：
  settings.gradle.kts
  build.gradle.kts
  app/build.gradle.kts
  README.md

## 5. 构建与测试
- 使用 Android Studio 打开项目，连接 Android 8 真机
- 构建 Debug：
  ./gradlew assembleDebug
- 安装 Debug：
  adb install -r app/build/outputs/apk/debug/app-debug.apk
- 开启无障碍：
  设置 -> 无障碍 -> TouchLock
- 查看日志：
  adb logcat -v time | grep -i touchlock
  Windows 可使用：
  adb logcat -v time | findstr /i touchlock

## 6. 验收标准
1. 同时按音量上 + 下，出现锁定覆盖层，触摸无效
2. 再次同时按音量上 + 下，覆盖层消失，触摸恢复
3. 锁定时按电源键熄屏，再亮屏应自动解锁
4. 长按组合键不应反复触发
5. 锁定时通知栏显示状态
6. 无障碍服务关闭时，MainActivity 应提示用户开启

## 7. 给 AI 代理的工作规则
- 每次只完成一个步骤
- 先说明将修改哪些文件，再给完整文件内容
- 不要假设已有代码，每个文件都给完整版
- 如果 API 行为不确定，给出在 Android 8 上的验证方法
- 遇到权限问题，优先使用无障碍服务能力，不要求 Root
- 注释用中文，变量名用英文