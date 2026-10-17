plugins {
    // Android 应用插件
    id("com.android.application")
    // Kotlin Android 插件
    id("org.jetbrains.kotlin.android")
}

android {
    // 命名空间与 applicationId 保持一致
    namespace = "com.example.touchlock"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.touchlock"
        // 最低支持 Android 8.0
        minSdk = 26
        // 目标版本固定为 26，避免高版本后台与前台服务限制影响无障碍服务行为
        targetSdk = 26
        // 版本历史：
        // 1.0.1 修复「开启无障碍服务后应用屡次停止运行」（Android 13 以下调用
        //       ContextCompat.registerReceiver(RECEIVER_NOT_EXPORTED) 需要清单声明
        //       DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION，见 AndroidManifest.xml）；
        // 1.0.2 遮罩显示时长可设置，到点后自动隐藏（触摸锁定保持），方便锁定期间看视频。
        versionCode = 3
        versionName = "1.0.2"
        // 仪器测试使用的 Runner，与 androidx.test 依赖配套
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            // 步骤 0 保持默认，不开启混淆
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    // compileSdk 34 对应的 AGP 8.x 需要 JDK 17
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        // 开启视图绑定，后续覆盖层布局也会用到
        viewBinding = true
    }
}

dependencies {
    // Kotlin 标准库扩展
    implementation("androidx.core:core-ktx:1.12.0")
    // AppCompat，提供 AppCompatActivity 与兼容主题
    implementation("androidx.appcompat:appcompat:1.6.1")
    // Material 组件，提供 Material3 主题与按钮样式
    implementation("com.google.android.material:material:1.11.0")
    // Activity KTX，后续跳转与结果回调使用
    implementation("androidx.activity:activity-ktx:1.8.0")
    // ConstraintLayout，后续布局细化时使用
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // 本地单元测试
    testImplementation("junit:junit:4.13.2")
    // 仪器测试
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
