// 插件仓库配置：AGP 与 Kotlin 插件需要从 Google / Maven Central 获取
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

// 依赖仓库统一在这里声明，模块内不再单独声明仓库
dependencyResolutionManagement {
    // 若模块内私自声明仓库，直接构建失败，避免依赖来源混乱
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

// 工程名称
rootProject.name = "TouchLock"

// 只有一个应用模块，只声明一次（重复 include 会让配置阶段产生冗余）
include(":app")
