pluginManagement {
    repositories {
        // 国内加速镜像（如不需要可整段删除，不影响正确性）
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/central")

        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // 国内加速镜像（阿里云代理 Maven Central / Google，构件缺失会自动回落到官方仓库）
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/central")

        google()
        mavenCentral()
        // 注意：不再需要 jitpack.io
        // 旧的 AndroidUSBCamera 2.3.6 依赖 com.serenegiant:common:4.1.1，
        // 该构件只存在于已关停的 jcenter，现代 Gradle 下无法解析，故弃用。
    }
}

rootProject.name = "PhoneMonitor"
include(":app")
