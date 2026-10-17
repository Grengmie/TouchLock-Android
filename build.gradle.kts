// 根构建脚本：只负责声明插件版本，不在这里配置具体模块
plugins {
    // Android Gradle Plugin，compileSdk 34 需要 AGP 8.x
    // 该版本已在本工程的 Gradle 9.6.0 + JDK 17/JDK 25 环境下验证可成功构建
    id("com.android.application") version "8.2.2" apply false
    // Kotlin Android 插件，与 AGP 8.2.2 搭配的稳定版本
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
}
