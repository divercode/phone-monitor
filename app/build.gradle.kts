plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.phonemonitor.monitor"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.phonemonitor.monitor"
        minSdk = 24
        targetSdk = 34
        // 发版规则：每次更新必须同时递增这两个值（见 docs/RELEASING.md）
        versionCode = 11
        versionName = "0.8.2"
    }

    buildFeatures {
        // 用于在 App 内显示版本号（BuildConfig.VERSION_NAME）
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        // UVCAndroid 内含 libuvccamera 的 .so，保留原有打包方式
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")

    // UVC 采集库：Maven Central 托管、持续维护（1.0.13，2026 年仍在更新）。
    // AAR 内含 saki4510t/libuvccamera 的 com.serenegiant.usb.* 与 native 库，
    // 因此 uvc/UvcCameraSource.kt 可以直接使用 CameraHelper。
    implementation("com.herohan:UVCAndroid:1.0.13")

    // 纯 JVM 单元测试：LUT 解析/图集打包不依赖 Android 运行时，
    // 装好 Android Studio 后可直接 ./gradlew testDebugUnitTest 验证，无需真机。
    testImplementation("junit:junit:4.13.2")
}
