# TouchLock 开发进度（process.md）

最后更新：步骤 0 ～ 步骤 6 已完成；1.0.1 修复了真机上的「开启无障碍后屡次停止运行」，
1.0.2 增加「遮罩显示时长可设置 + 到点自动隐藏」（见第 0、0.1 节）。

> **当前快照（1.0.2）**
> - 应用状态：真机可用（用户反馈 1.0.1 起「可以正常使用」）。
> - 待真机确认：遮罩自动隐藏后触摸是否仍被完全拦截；验收标准第 3、4、5 条单独复测。
> - 最新产物：`TouchLock-debug.apk`（versionCode 3 / versionName 1.0.2，SHA-256 `3d1f1c4acac4610793f026993d29c3b2120281e4cd7df8236a518030ed853fe3`）。
> - 出包方式：`bash tools/manual-build/build.sh`（沙箱内 Gradle / adb 不可用，见第 5、7 节）。

---

## 0. 1.0.2 变更：遮罩显示时长可设置、到点自动隐藏（为锁定期间看视频）

### 需求

锁定触摸时那块半透明灰幕会挡住视频画面。希望遮罩只显示一小段（默认 0.3 秒，可设置）就自动消失，
同时「触摸锁定」继续保持生效。

### 行为定义

1. 按组合键锁定后，遮罩（灰幕 + 锁图标 + 提示文字）照常出现，作为「已锁定」的视觉反馈；
2. 经过设定时长（默认 300ms）后，遮罩**视觉部分自动隐藏**：
   把根布局背景设为全透明、图标与文字设为 `INVISIBLE`，
   **窗口本身保留**（不加 `FLAG_NOT_TOUCHABLE`、不把窗口 alpha 设为 0），
   因此触摸依旧被消费，锁定继续生效，而屏幕完全不被遮挡，可以正常看视频；
3. 通知栏那条常驻通知继续存在，作为「当前处于锁定状态」的指示；
4. 再按组合键 / 熄屏 / 满 10 分钟仍然照常解锁；解锁时遮罩窗口被整体移除，
   下一次锁定会重新加载布局，所以视觉状态不需要手动恢复。

### 时长如何设置

- 主界面新增「遮罩显示时长（毫秒）」输入框 + 「遮罩隐藏后继续保持触摸锁定」勾选项 + 保存按钮；
- 设置存进 `SharedPreferences`（`touchlock_settings`），服务端**每次加锁时重新读取**，
  所以保存后下一次锁定即生效，不需要重启服务或重装；
- 取值范围 0 ~ 10000 毫秒，超出会被自动限制并回填显示；0 表示几乎不显示遮罩；
- 取消勾选「继续保持触摸锁定」时，到点直接解锁（相当于只闪一下提示后结束锁定）。

### 涉及文件

| 文件 | 改动 |
| --- | --- |
| `TouchLockSettings.kt`（新增） | `SharedPreferences` 封装：遮罩时长（默认 300ms）+ 隐藏后是否保持锁定 |
| `LockOverlayManager.kt` | 内部改存 `OverlayLockBinding`（原为根视图）；新增 `hideVisuals()` 只隐藏视觉内容、保留窗口 |
| `TouchLockAccessibilityService.kt` | 新增 `overlayHideRunnable` 与 `scheduleOverlayVisualHide()` / `cancelOverlayHideTask()`；加锁时投递、解锁/熄屏/服务重建/销毁时取消；`settings` 用 `by lazy` 延迟创建（服务 attach 前拿不到 Context） |
| `MainActivity.kt` | 回填/保存设置，保存后 Toast 提示并回显被限制后的时长 |
| `activity_main.xml` | 新增时长输入框（显式用 `AppCompatEditText`，避开 Material 主题把 `EditText` 换成 `MaterialAutoCompleteTextView`）、勾选框、保存按钮 |
| `strings.xml` | 新增设置相关文案；使用说明里补充「遮罩会自动隐藏、触摸仍锁定」 |
| `tools/manual-build/`（新增） | 手工出包流水线脚本 `build.sh` 与两个 ViewBinding 等价实现，之前只存在于临时目录，现纳入项目 |
| `app/build.gradle.kts` | `versionCode = 3`，`versionName = "1.0.2"` |

---

## 0.1 1.0.1 修复：开启无障碍服务后「屡次停止运行」

### 现象

安装上一版 APK 后，在手机上按引导开启「TouchLock」无障碍服务，应用反复崩溃，
系统弹「TouchLock 屡次停止运行」；关闭无障碍开关后应用恢复正常。

### 根因（已在 APK 内逐层核对，非猜测）

1. `TouchLockAccessibilityService.registerActionReceiver()` 使用
   `ContextCompat.registerReceiver(..., ContextCompat.RECEIVER_NOT_EXPORTED)`，
   而 `RECEIVER_NOT_EXPORTED` 是 **API 33（Android 13）** 才有的平台能力。
2. androidx.core 1.12 在 API 26～32 上的退路实现是
   `ContextCompat.obtainAndCheckReceiverPermission()`：它要求应用在清单里声明
   一条**签名级权限** `<包名>.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`，
   并用它来保护这个动态接收器。
   反编译 `core-1.12.0.aar` 的 `ContextCompat` 可见：校验不通过就
   `throw new RuntimeException("Permission ... is required by your application
   to receive broadcasts, please add it to your manifest")`。
3. 这条权限**不在本工程的 AndroidManifest.xml 里**，而是写在
   `androidx.core` 自己的 AndroidManifest.xml 中（`${applicationId}` 占位符），
   正常 Gradle 构建时由**清单合并**自动带入。本项目走的是手工出包流程
   （aapt2 + kotlinc + d8），只用了本工程的清单，**没有合并依赖库清单**，
   于是这条权限缺失。
4. 触发时机完全吻合用户现象：`registerActionReceiver()` 只在
   `onServiceConnected()` 里调用一次 → 只有开启无障碍服务后才会抛异常 →
   无障碍服务崩溃 → 系统反复重新绑定已启用的服务 → 「屡次停止运行」。
   在 Android 13+ 手机上复现不出来，因为那里的平台原生支持
   `RECEIVER_NOT_EXPORTED`，不会走 androidx 的权限退路。本工程 targetSdk/测试机型都是
   Android 8，所以必崩。

### 修复内容

| 文件 | 改动 |
| --- | --- |
| `app/src/main/AndroidManifest.xml` | 显式声明 `com.example.touchlock.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`（`signature` 级）与对应的 `<uses-permission>`，等价于清单合并的效果；并注册 `android:name=".TouchLockApp"` |
| `TouchLockAccessibilityService.kt` | 注册广播改为版本判断：API 33+ 才用 `ContextCompat.registerReceiver(..., RECEIVER_NOT_EXPORTED)`，API 33 以下用系统原生 `registerReceiver(receiver, filter)`（targetSdk 26 的动态接收器本就只对本应用可见，不需要该标志）；`onServiceConnected` / `onKeyEvent` / `lock` / `unlock` / `onUnbind` / `onDestroy` 全部加 try-catch，任何异常只记日志不再崩溃；`serviceInfo` 为空时不再提前返回，避免漏掉通知渠道与广播注册 |
| `TouchLockApp.kt`（新增） | 应用级未捕获异常处理器：把崩溃堆栈写入内部与外部私有目录（`touchlock_crash.txt`），再交回系统处理 |
| `MainActivity.kt` | 启动时若存在崩溃记录，把堆栈追加显示在说明文字后，方便无 adb 时截图反馈 |
| `strings.xml` | 新增 `crash_report_title` |
| `app/build.gradle.kts` | `versionCode = 2`，`versionName = "1.0.1"` |

### 出包与验证

- 仍用原手工流水线重新出包，未使用 Gradle（沙箱禁止创建 socket，Gradle/adb 都起不来）。
- 依赖集合比之前完整：补齐了 `constraintlayout-core`、`emoji2` 的 `jars/libs/repackaged.jar`、
  `concurrent-futures`、`listenablefuture`、`kotlinx-coroutines-core-jvm/-android` 等
  之前漏掉的传递依赖。静态「缺失类」检查从 60 个降到 4 个，且剩下 4 个全部是
  `kotlinx.coroutines.debug.AgentPremain`（JVM java-agent，Android 上永不加载）引用的
  `sun.misc.Signal` / `java.lang.instrument.*`，无影响。
- 签名沿用 `TouchLock-debug.keystore`：**新包签名与旧包完全一致**
  （SHA-256 `005a47da...9ad53`），可直接覆盖安装，不用先卸载。

## 1. 当前状态

| 项目 | 状态 |
| --- | --- |
| 步骤 0 基础工程（Gradle / Manifest / 主题 / 资源） | ✅ 完成 |
| 步骤 1 无障碍服务 + 服务配置 XML + 清单注册 | ✅ 完成 |
| 步骤 2 组合键检测 KeyComboDetector | ✅ 完成 |
| 步骤 3 触摸锁定覆盖层 LockOverlayManager + 布局 | ✅ 完成 |
| 步骤 4 服务集成：TOGGLE 切换锁定、消费按键、熄屏自动解锁 | ✅ 完成 |
| 步骤 5 锁定通知 + 通知渠道 + 10 分钟超时自动解锁 | ✅ 完成 |
| 步骤 6 MainActivity 服务状态检测（onResume 刷新） | ✅ 完成 |
| 步骤 7 真机运行：开启无障碍后「屡次停止运行」 | ✅ 已修复并重出包（1.0.1，见 0.1 节） |
| 步骤 8 崩溃自动记录（无 adb 也能拿到堆栈） | ✅ 完成（1.0.1 引入 `TouchLockApp`） |
| 步骤 9 遮罩显示时长可设置 + 到点自动隐藏（看视频不遮挡） | ✅ 完成（1.0.2，见第 0 节），待真机确认细节 |
| 真机验收（AGENTS.md 第 6 节 6 条） | 🟡 大体通过：用户反馈 1.0.1 起「可以正常使用」；第 3/4/5 条未逐条单独复测 |
| APK 产出 | ✅ `TouchLock-debug.apk`（versionCode 3 / versionName 1.0.2，手工工具链构建，见第 5 节） |

### AGENTS.md 验收标准对照

| 验收标准 | 实现位置 | 真机是否已验证 |
| --- | --- | --- |
| 1. 同按音量上+下出现锁定覆盖层，触摸无效 | `KeyComboDetector` + `LockOverlayManager` | ✅ 用户反馈 1.0.1 起可正常使用（1.0.2 起遮罩为短暂显示后自动隐藏，触摸仍锁定） |
| 2. 再次同按覆盖层消失，触摸恢复 | `TouchLockAccessibilityService.toggleLock()` | ✅ 同上 |
| 3. 锁定时熄屏再亮屏自动解锁 | `ACTION_SCREEN_OFF` 广播 → `unlock()` | ⏳ 未单独复测 |
| 4. 长按组合键不反复触发 | `KeyComboDetector` 冷却机制（桌面测试已覆盖） | ⏳ 未单独复测 |
| 5. 锁定时通知栏显示状态 | `showLockNotification()` + 通知渠道 | ⏳ 未单独复测（1.0.2 起通知也是遮罩自动隐藏后唯一的状态指示） |
| 6. 无障碍未开启时 MainActivity 提示 | `MainActivity.refreshAccessibilityStatus()` | ✅ 引导流程实际可用 |
| 7.（1.0.2 新增需求）锁定期间看视频不被灰幕遮挡 | `TouchLockSettings` + `LockOverlayManager.hideVisuals()` | ⏳ 待真机确认 |

---

## 2. 文件清单与职责

### 源码

| 文件 | 职责 |
| --- | --- |
| `app/src/main/java/com/example/touchlock/MainActivity.kt` | 入口界面：显示无障碍服务状态、跳转系统无障碍设置、`onResume` 刷新；遮罩时长的回填与保存；启动时把上次崩溃堆栈追加到说明文字后 |
| `app/src/main/java/com/example/touchlock/TouchLockAccessibilityService.kt` | 全局按键接收、组合键切换锁定、熄屏解锁、通知与超时管理、遮罩自动隐藏任务、生命周期清理；`onServiceConnected` / `onKeyEvent` / `lock` / `unlock` / `onUnbind` / `onDestroy` 均带 try-catch 兜底 |
| `app/src/main/java/com/example/touchlock/KeyComboDetector.kt` | 纯状态机：500ms 内两键按下 → `Action.TOGGLE`；忽略 `repeatCount > 0`；触发后冷却到两键抬起 |
| `app/src/main/java/com/example/touchlock/LockOverlayManager.kt` | 全屏触摸锁定覆盖层的 `show()` / `hide()` / `hideVisuals()`（只隐藏视觉内容、保留窗口），幂等、可重复调用 |
| `app/src/main/java/com/example/touchlock/TouchLockSettings.kt` | `SharedPreferences` 封装：遮罩显示时长（默认 300ms，0~10000ms）+ 遮罩隐藏后是否继续保持触摸锁定 |
| `app/src/main/java/com/example/touchlock/TouchLockApp.kt` | 应用入口（`android:name=".TouchLockApp"`）：安装全局未捕获异常处理器，把崩溃堆栈写入 `touchlock_crash.txt`（内部存储 + 外部私有目录） |

### 资源与清单

| 文件 | 职责 |
| --- | --- |
| `app/src/main/AndroidManifest.xml` | 应用声明（含 `TouchLockApp`）、签名级权限 `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`、入口 Activity、无障碍服务注册（权限 + intent-filter + meta-data） |
| `app/src/main/res/xml/accessibility_service_config.xml` | 无障碍服务配置：`canRequestFilterKeyEvents`、`flagRequestFilterKeyEvents`、`typeAllMask` 等 |
| `app/src/main/res/layout/activity_main.xml` | 主界面：标题、使用方法、服务状态、未开启提示、跳转按钮、遮罩时长输入框 + 保持锁定勾选项 + 保存按钮（外层 `ScrollView`） |
| `app/src/main/res/layout/overlay_lock.xml` | 覆盖层：半透明黑背景 + 锁图标 + 「触摸已锁定，按音量上+下解锁」 |
| `app/src/main/res/values/strings.xml` | 全部界面文案：使用说明（含遮罩自动隐藏说明）、无障碍服务描述、通知渠道与通知文案、设置相关文案、崩溃记录标题 |
| `app/src/main/res/values/themes.xml` | `Theme.TouchLock`（`Theme.Material3.DayNight.NoActionBar`） |

### 构建脚本

| 文件 | 说明 |
| --- | --- |
| `settings.gradle.kts` | 仓库配置、`rootProject.name = "TouchLock"`、`include(":app")` |
| `build.gradle.kts` | AGP 8.2.2 + Kotlin 1.9.22（仅声明版本） |
| `app/build.gradle.kts` | `namespace/applicationId = com.example.touchlock`、compileSdk 34、minSdk 26、targetSdk 26、JDK 17、viewBinding；`versionCode = 3` / `versionName = "1.0.2"` |
| `tools/manual-build/build.sh` | 手工出包流水线（aapt2 + javac + kotlinc + d8 + zipalign + apksigner），沙箱内出包用，脚本头部注释记录了 4 个必踩的坑 |
| `tools/manual-build/binding/*.java` | 两个 ViewBinding 等价实现（`ActivityMainBinding` / `OverlayLockBinding`），手工流水线需要，Gradle 构建则不需要 |

### 构建产物

| 文件 | 说明 |
| --- | --- |
| `TouchLock-debug.apk` | 可直接安装的 Debug APK（versionCode 3 / 1.0.2，5,523,212 字节） |
| `TouchLock-debug.keystore` | 本次签名用的密钥，口令均为 `android`；后续重新出包必须沿用，否则无法覆盖安装 |
| `TouchLock-debug.apk.idsig` | `apksigner` 顺带产出的 v4 签名文件，只有 `adb install --incremental` 才用得到，日常安装不需要，可以删 |

### 模板残留（不影响构建，可后续清理）

- `gradle/libs.versions.toml`：版本已改为在 `app/build.gradle.kts` 中显式声明，该文件当前未被引用。
- `app/src/main/res/values-night/themes.xml`：其中的 `Base.Theme.TouchLock` 没有被 `Theme.TouchLock` 继承。
- `app/src/main/res/xml/data_extraction_rules.xml`：`targetSdk 26` 未使用 `dataExtractionRules`，当前未被引用。
- `app/src/main/keepRules/rules.keep`、`app/src/test`、`app/src/androidTest`：Android Studio 模板自带。

---

## 3. 关键实现决策

1. **组合键判定**：`KeyComboDetector` 只关心音量上下键；两键按下时间差 ≤ 500ms 触发一次；触发后进入「等待两键抬起」的冷却状态，避免长按连发；时间源为 `SystemClock.uptimeMillis()`（单调递增，改系统时间不影响判定）。构造函数开放 `comboWindowMs` 与 `elapsedTimeMs` 两个带默认值的参数，方便测试替换。
2. **覆盖层窗口**：`TYPE_ACCESSIBILITY_OVERLAY`（2032）+ `MATCH_PARENT` + `FLAG_NOT_FOCUSABLE | FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_NO_LIMITS`(8/256/512) + `PixelFormat.TRANSLUCENT`；`setOnTouchListener { _, _ -> true }` 消费全部手势；**始终没有** `FLAG_NOT_TOUCHABLE`。窗口 `setTitle("TouchLockOverlay")`，便于 `dumpsys window` 排查。
3. **Context 选择**：`LockOverlayManager` 必须持有无障碍服务自身的 Context，用 `applicationContext` 会因缺少 WindowToken 抛 `BadTokenException`。
4. **幂等清理**：`hide()` 未显示时直接返回；移除前先把引用置空，并捕获 `IllegalArgumentException`，重复调用不会崩。`onUnbind` / `onDestroy` 中直接 `hide()` + 取消通知（不走 `isLocked` 守卫），保证不残留遮挡层和通知。
5. **按键消费**：`TOGGLE` 时 `onKeyEvent` 返回 `true` 消费该事件。**已知限制**：组合键的第一个按键事件在这之前已经作为普通事件下传，无法追回，所以每次切换锁定时音量仍可能变化一格。若要去掉，需要引入 `AudioManager` 反向回调音量。
6. **广播注册**：`ACTION_SCREEN_OFF` 与点击通知的 `com.example.touchlock.action.UNLOCK` 合并为一个接收器，用 `isActionReceiverRegistered` 防重复注册。注册方式按版本分支：API 33+ 用 `ContextCompat.registerReceiver(..., RECEIVER_NOT_EXPORTED)`，API 33 以下用系统原生 `registerReceiver(receiver, filter)`。
   **（1.0.1 修正）** 原实现对 Android 8 也传 `RECEIVER_NOT_EXPORTED`，而 androidx.core 在低版本上要求清单声明 `<包名>.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`，手工出包没合并库清单直接抛 `RuntimeException`，导致开启无障碍后服务崩溃，详见第 0 节。
7. **点击通知解锁**：`AccessibilityService` 无法被 `startService` 唤起，因此用 `PendingIntent.getBroadcast`（显式带包名）+ 应用内动态接收器实现。
8. **通知渠道**：`NotificationChannel(id, name, IMPORTANCE_LOW)` + `setShowBadge(false)`，在 `onServiceConnected` 中创建。因 minSdk 26 恰为 `NotificationChannel` 引入版本，未写 `Build.VERSION` 判断。通知小图标暂用框架资源 `android.R.drawable.ic_lock_lock`。
9. **超时自动解锁**：主线程 `Handler` + `postDelayed(10 分钟)`；解锁、熄屏解锁、`onServiceConnected`、`onDestroy` 都会 `removeCallbacks`。
10. **状态检测**：`Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` + `TextUtils.SimpleStringSplitter(':')` + `ComponentName.unflattenFromString()`，同时兼容「包名/类全名」与「包名/.类名」。未开启时用主题 `colorError` 强调，并在 `onResume` 刷新。
11. **组件初始化方式**：`keyComboDetector` / `lockOverlayManager` 在字段处创建、在 `onServiceConnected` 中复位（不用 `lateinit`），避免「服务被创建后立即销毁」时清理回调碰到未初始化属性。
12. **遮罩自动隐藏（1.0.2）**：到点后调用 `LockOverlayManager.hideVisuals()`，把根布局背景设为 `Color.TRANSPARENT`、图标与文字设为 `INVISIBLE`，**窗口本身保留**。
    刻意**不用** `WindowManager.LayoutParams.alpha = 0`：窗口整体完全透明后，系统可能不再把它算作可触摸窗口，触摸就会穿透到下层，锁定失效。窗口在、`FLAG_NOT_TOUCHABLE` 没加，触摸就一定会被消费。
13. **两个延时任务成对管理**：`autoUnlockRunnable`（10 分钟解锁）与 `overlayHideRunnable`（遮罩自动隐藏）都在 `lock()` 中投递、在 `unlock()` / `onServiceConnected()` / `onDestroy()` 中 `removeCallbacks`；解锁时若遮罩隐藏任务还在等待，必须取消，否则可能在窗口已移除后再去操作视图。
14. **设置读取时机**：服务端**每次加锁时**重新读 `TouchLockSettings`，所以首页改完设置下一次锁定即生效；`settings` 用 `by lazy` 创建，因为字段初始化阶段服务还没 attach，拿不 Context 去取 `SharedPreferences`。
15. **布局里显式用 AppCompat 控件**：设置区的输入框/勾选框写成 `androidx.appcompat.widget.AppCompatEditText` / `AppCompatCheckBox`，避免 Material 主题的 `MaterialComponentsViewInflater` 把 `EditText` 换成 `MaterialAutoCompleteTextView`（本场景不需要自动补全，用 AppCompat 版本行为更可控）。
16. **崩溃自记录**：`TouchLockApp` 里 `Thread.setDefaultUncaughtExceptionHandler` 先写堆栈到 `touchlock_crash.txt`（内部 + 外部私有目录），再交回原处理器；`MainActivity` 启动时若有记录就追加显示在说明文字后。目的：手机上不方便连 adb，崩溃也能直接截图反馈。
17. **回调兜底**：`onServiceConnected` / `onKeyEvent` / `lock` / `unlock` / `onUnbind` / `onDestroy` 全部 try-catch（`Throwable`）。无障碍服务回调抛异常会被系统判定为服务崩溃并反复重启，表现为「屡次停止运行」——这层兜底让偶发异常只留在日志里，不影响服务存活。

---

## 4. 已验证 / 待验证

### 已完成的验证（真机反馈 + 沙箱内静态与编译验证）

- **真机运行**：用户反馈 1.0.1 起「可以正常使用」（开启无障碍不再崩溃、锁定/解锁可用），1.0.1 的根因分析与修复见 0.1 节。
- **完整出包**：`bash tools/manual-build/build.sh` 全流程通过——`aapt2 compile`（应用 + 31 个含资源 AAR）→ `aapt2 link`（`--extra-packages` 生成 23 个库 R 包 + 应用 R）→ `javac`（R 类 + 2 个 ViewBinding 等价实现）→ `kotlinc`（6 个 Kotlin 文件）→ `d8`（4 个 dex）→ `zipalign` → `apksigner`；产物 `TouchLock-debug.apk`（v2 + v3 签名）。
- **状态机行为**：把工程里真实的 `KeyComboDetector.kt` 编译到桌面 JVM，用替身 `android.view.KeyEvent` / `android.os.SystemClock` 跑 25 个场景，全部通过：100ms 触发、601ms 不触发、恰好 500ms 触发、长按连发被忽略、只松开一键仍保持冷却、两键抬起后可再次触发、非音量键不干扰状态、`reset()` 清空半按与冷却状态、`ACTION_MULTIPLE` 不参与判定。
- **资源**：`aapt2 compile` 全部资源通过；`overlay_lock.xml` + `strings.xml` 单独 link 通过；锁图标确认为框架 PUBLIC 资源（`0x0108002f`）。
- **常量核对**：`javap` 核对 `TYPE_ACCESSIBILITY_OVERLAY=2032`、`FLAG_NOT_FOCUSABLE=8`、`FLAG_LAYOUT_IN_SCREEN=256`、`FLAG_LAYOUT_NO_LIMITS=512`、`FLAG_NOT_TOUCHABLE=16`；`grep` 确认工程未使用 `FLAG_NOT_TOUCHABLE` 与 `TYPE_APPLICATION_OVERLAY`。
- **清单核对**：`aapt2 dump xmltree` 确认 `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`（`protectionLevel=signature`）与对应 `uses-permission`、`android:name=".TouchLockApp"`、无障碍服务 meta-data → `res/xml/accessibility_service_config.xml`（`canRequestFilterKeyEvents=true`、`accessibilityFlags=0x21`）均在 APK 内；`aapt2 dump badging` 显示包名/versionCode 3/versionName 1.0.2/minSdk 26/targetSdk 26/入口 Activity 正确。
- **资源 id 一致性**：手工写的 `ActivityMainBinding` 里用的 id（`btnOpenAccessibilitySettings=0x7f080062`、`btnSaveOverlayDuration=0x7f080063`、`cbKeepLockedAfterHide=0x7f080069`、`etOverlayDurationMs=0x7f0800b6`、`tvDescription=0x7f0801de`、`tvOverlayDurationTitle=0x7f0801e0`…）与资源表中的名字一一对应；`--extra-packages` 覆盖了库字节码实际引用的全部 R 包（`androidx/activity`、`androidx/appcompat`、`androidx/core`、`androidx/recyclerview`、`com/google/android/material` 等，最终传入 23 个）。
- **缺失类检查**：从 4 个 dex 的反汇编里提取全部类型引用，与 dex 内定义 + `android.jar` 比对。1.0.1 之前有 60 个缺失（`constraintlayout-core`、`listenablefuture`、`kotlinx-coroutines`、`emoji2` 的 flatbuffer 等传递依赖没进包），补齐依赖后降到 4 个，且全部只被 `kotlinx.coroutines.debug.AgentPremain`（JVM java-agent，Android 上永不加载）引用：`sun.misc.Signal*`、`java.lang.instrument.*`。
- **字节码核对**：`dexdump` 确认 `TouchLockSettings`、`LockOverlayManager.hideVisuals`、`scheduleOverlayVisualHide`/`overlayHideRunnable`、两个 ViewBinding 与全部业务类都进了 dex；`registerActionReceiver` 中可见 `SDK_INT >= 33` 分支（低版本走系统原生 `registerReceiver`）与 `Exception` catch 表。

### 待验证（必须有真机）

- 遮罩自动隐藏（1.0.2）：默认 300ms 后灰幕消失但触摸仍被锁定——**重点确认「透明后触摸仍然完全被拦截」**（锁定期间点屏幕无反应），以及录像/看视频不被遮挡。
  把时长改成 `0` 与 `1000` 各测一次；取消勾选「继续保持触摸锁定」后，到点应直接解锁。
- AGENTS.md 验收标准第 3、4、5 条单独复测：熄屏解锁、长按组合键不连发、通知栏状态通知（含点击通知解锁）。
- 10 分钟超时解锁：建议临时把 `AUTO_UNLOCK_TIMEOUT_MS` 改为 `10_000L` 验证后改回。
- 覆盖层是否能拦截状态栏/导航栏区域的滑动。
- 首页设置项的交互细节（输入非法值、超出 0~10000 时的提示与回填）。

---

## 5. 构建与出包

### 沙箱环境限制（重要）

当前沙箱禁止创建 socket：`NetworkInterface.getNetworkInterfaces()` 抛 `Operation not permitted`，Gradle 启动时需要探测网卡地址，因此 **`./gradlew` 在本沙箱内无法运行**。这属于沙箱限制，不代表工程或本机 Gradle 有问题——本机（非沙箱）先前已有一次 `BUILD SUCCESSFUL` 记录。

### 手工构建流水线（产出 APK 的方式）

已脚本化：`bash tools/manual-build/build.sh`（脚本头部注释记录了关键坑位），
使用与 AGP 相同的底层工具，全部位于 `/home/glz/Android/Sdk/build-tools/34.0.0/`，JDK 用 `/snap/android-studio/247/jbr`：

1. `aapt2 compile --dir <res> -o <out.zip>`：分别编译应用与 31 个含资源依赖 AAR 的资源（脚本里共 44 个 AAR，其中 23 个有 R 包）。
2. `aapt2 link -o base.apk -I android.jar --manifest <带 package 属性的清单副本> --java gen --extra-packages <23 个库包> --auto-add-overlay -R <各依赖资源 zip> <app.zip>`：
   - 依赖资源必须以 `-R`（overlay）方式传入并加 `--auto-add-overlay`，否则 material 与 appcompat/constraintlayout 的同名 `styleable`（`SearchView`、`Carousel`）会冲突报错。
   - `--extra-packages` 需覆盖库字节码实际引用的全部 R 包（脚本会从各 AAR 的 classes.jar 里 grep `*/R$*` 打印出来对照），本次 23 个（不含 `android`）。
3. 两个 ViewBinding 类改为放在仓库里维护：`tools/manual-build/binding/ActivityMainBinding.java`、`OverlayLockBinding.java`（AGP 平时自动生成；手工构建里按等价结构提供 `root` 与子 View 字段，**新增控件后要同步补字段**）。
4. `javac` 编译生成的 R.java（应用包 + 23 个库包）+ 上面两个绑定类。
5. `kotlinc` 编译 6 个业务 Kotlin 文件（classpath：`android.jar` + 各 AAR 的 classes + androidx 纯 jar + 上一步产物 + `kotlin-stdlib`）。
   - 注意：`androidx.lifecycle:lifecycle-common`、`androidx.annotation:annotation-jvm`、`androidx.collection:collection`、`androidx.arch.core:core-common` 是 **jar** 而非 AAR，必须单独加入 classpath，否则报 `unresolved supertypes: LifecycleOwner`。
   - **必须加 `-no-jdk`**：JBR 是 JDK 25，`kotlin-compiler-embeddable 1.9.22` 解析不了它的版本号（`IllegalArgumentException: 25.0.3`）；Android 编译本来也不该用 JDK 的 `java.*` 类（`android.jar` 里有）。
   - `kotlin-compiler-embeddable` 需要 `trove4j` 在 classpath 上，否则 `NoClassDefFoundError: gnu/trove/THashMap`。
6. 把各 class 目录打成 jar 后交给 `d8 --min-api 26 --lib android.jar --release --output dex`（d8 在本版本不接受目录输入）；产物为 `classes.dex` ~ `classes4.dex`（多 dex）。
   - AAR 里的 `jars/libs/*.jar`（如 `emoji2` 的 `repackaged.jar`）也要一起喂给 d8，否则会留下缺失类。
7. 用 python 把 dex 追加进 `base.apk` → `zipalign -p 4` → `apksigner sign`（密钥 `TouchLock-debug.keystore`，alias `androiddebugkey`，口令 `android`）。

### 与本机 Gradle 构建的差异（诚实说明）

- ViewBinding 类是手工等价实现，不是 AGP 生成物（新增/删除控件时必须同步维护）。
- **未合并依赖库的 AndroidManifest**：当前唯一影响功能的后果是缺 `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`（1.0.1 已显式补上，见 0.1 节）；`androidx.startup` 初始化 provider、`profileinstaller` 接收器、`CoreComponentFactory` 等本项目用不到的条目仍然缺席——副作用是 `emoji2` 不会被初始化（`EmojiCompat` 保持未配置状态，appcompat 走非 emoji 分支，界面无 emoji 增强，功能不受影响）。
- 未执行 R8（Debug 构建本来也不开启混淆/压缩）。
- 签名密钥与 Android Studio 自带 debug 密钥不同：**Studio 构建的包与本 APK 不能互相覆盖安装**，需要先卸载。以后继续手工出包时请沿用本目录下的 keystore。
- 因此该 APK 适合自用测试；若要长期维护或分发，建议在本机跑一次 `./gradlew assembleDebug`（或在 Android Studio 里 Build > Build APK(s)，同样不需要 adb）产出标准包。

### 最新产物信息

| 项 | 值 |
| --- | --- |
| 文件 | `TouchLock-debug.apk` |
| 大小 | 5,523,212 字节（约 5.3 MB） |
| SHA-256 | `3d1f1c4acac4610793f026993d29c3b2120281e4cd7df8236a518030ed853fe3` |
| 签名 | APK Signature Scheme v2 + v3（`CN=Android Debug`） |
| 包名 / 版本 | `com.example.touchlock` / versionCode 3, versionName 1.0.2 |
| 系统要求 | Android 8.0（API 26）及以上 |

> 该 APK 的签名密钥与上一版（versionCode 1）相同，可直接覆盖安装；
> 上一版因缺少 `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`，在 Android 13 以下
> 开启无障碍服务后必然崩溃，已被本版替换。

> 出包方式：`bash tools/manual-build/build.sh`（脚本内的“踩过的坑”注释说明了
> 清单权限、`-no-jdk`、trove4j、AAR 内 `jars/libs/*.jar` 这几处关键点）。

> **手工流水线的固有坑**：不合并依赖库清单，意味着依赖库清单里声明的
> `<permission>` / `<provider>` / `<receiver>` / `appComponentFactory` 都不会出现在 APK 中。
> 本版已把当前代码路径必需的权限显式补进本工程清单；若日后新增依赖或改用
> `ContextCompat` 的其他 API，请先用 `unzip -p xxx.aar AndroidManifest.xml` 核对依赖库清单，
> 或干脆用 Android Studio / `./gradlew assembleDebug` 出包（会自动合并清单）。

---

## 6. 无 adb 的安装与验证

1. 把 `TouchLock-debug.apk` 传到手机（USB 存储、微信/QQ 传文件、网盘、蓝牙均可）。
2. 用手机「文件管理」点开 APK → 允许该应用「安装未知应用」→ 安装（Play 保护提示「未知开发者」时选择「仍要安装」）。
   已装过旧版（1.0.0 / 1.0.1）时**直接覆盖安装即可，不需要卸载**：三个版本的签名密钥完全相同。
3. 打开 TouchLock → 「打开无障碍设置」→ 开启 TouchLock 服务 → 返回应用，状态变为「已开启」。
4. 使用：同时按「音量上 + 音量下」锁定/解锁；锁定后遮罩只显示一小段（默认 300ms）就自动隐藏，触摸仍保持锁定，可以正常看视频。
5. 首页可改「遮罩显示时长（毫秒，0 ~ 10000）」与「遮罩隐藏后继续保持触摸锁定」，改完点「保存设置」，下一次锁定即生效。

验证要点（无需日志）：锁定后遮罩一闪即隐、此时点击滑动仍无反应；再次同按恢复；通知栏出现常驻「触摸已锁定」通知且点它可解锁（遮罩隐藏后它就是唯一的状态指示）；熄屏再亮屏自动解锁。

**锁死退路**：再按组合键 → 按电源键熄屏 → 等 10 分钟自动解锁 → 系统设置里关闭 TouchLock 无障碍开关（会移除覆盖层与通知）。

**没有 adb 也能看到崩溃原因**：应用启动时会自己检测上次崩溃，把堆栈追加在首页说明文字下方（直接截图即可）；
日志文件同时写在 `Android/data/com.example.touchlock/files/touchlock_crash.txt`，可用文件管理器取出。
另外也可以安装第三方 Logcat 读取类应用（走系统 bug report，无需 root）。

---

## 7. 环境信息

| 项 | 值 |
| --- | --- |
| Android SDK | `/home/glz/Android/Sdk`（platforms: android-34、android-37.0；build-tools: 34.0.0、36.0.0） |
| JDK | `/snap/android-studio/247/jbr`（OpenJDK 25；工程编译目标 JDK 17） |
| Gradle wrapper | 9.6.0 |
| AGP / Kotlin | 8.2.2 / 1.9.22 |
| compileSdk / minSdk / targetSdk | 34 / 26 / 26 |
| 主要依赖 | core-ktx 1.12.0、appcompat 1.6.1、material 1.11.0、activity-ktx 1.8.0、constraintlayout 2.1.4；测试：junit 4.13.2、androidx.test.ext:junit 1.1.5、espresso-core 3.5.1 |
| 手工出包用到的额外传递依赖 | lifecycle 2.6.1 系列、fragment 1.3.6、emoji2 1.2.0（含 `jars/libs/repackaged.jar`）、constraintlayout-core 1.0.4、concurrent-futures 1.1.0、listenablefuture 1.0、kotlinx-coroutines 1.6.4、kotlin-stdlib 1.9.22、trove4j 1.0.20200330（kotlinc 自身需要） |
| 沙箱限制 | 禁止创建 socket → Gradle（需探测网卡 + 客户端/守护进程通信）与 adb（需 5037 端口）都不可用；只能用手工流水线出包，静态检查靠 `dexdump` / `aapt2 dump` / `javap` |

---

## 8. 后续可选事项

1. 通知小图标换成专门的单色 vector（当前借用框架的 `ic_lock_lock`，需新增 `res/drawable/ic_lock_notification.xml`）。
2. 遮罩自动隐藏后完全没有视觉提示，状态只靠通知栏。如果实际用起来容易忘记「还在锁定」，可以考虑加一个更含蓄的常驻提示
   （例如锁定时把提示文字用 5% 透明度长期显示，或只在屏幕边缘留一条细线）。
3. 遮罩显示时长目前只能手输毫秒数，可以考虑加几个快捷档位按钮（0 / 300 / 1000ms）省去输入。
4. 给 `KeyComboDetector` 补本地单元测试（时间源已可注入；需 Robolectric 才能构造 `KeyEvent`）。
5. 是否吞掉组合键带来的那一格音量变化（需引入 `AudioManager` 反向调节）。
6. `targetSdk` 若要提升到 33+：需加 `POST_NOTIFICATIONS` 权限声明与运行时请求；`ContextCompat.registerReceiver` 的分支与 `PendingIntent.FLAG_IMMUTABLE` 已提前适配。
7. 清理模板残留文件（见第 2 节末尾）。
8. 补充 `README.md`（安装、开启无障碍、使用与排障说明）。
9. 真机补测 AGENTS.md 验收标准第 3、4、5 条，以及 1.0.2 的「遮罩隐藏后触摸仍被完全拦截」。
