plugins {
    id("com.android.application")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

android {
    namespace = "com.kidlock.app"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        applicationId = "com.kidlock.app"
        // 目标设备 Android 7.1.2（API 25）：
        //  minSdk 24  —— Flutter 3.x 引擎下限，设备为 25，满足
        //  targetSdk 25 —— 以 API 25 兼容行为运行，绕开 API 26+ 的后台启动限制、
        //                  通知渠道、隐式广播限制等变更，保证常驻服务与开机广播稳定
        minSdk = 24
        targetSdk = 25
        // Uses the version code from pubspec.yaml. When using split APKs, 1000 * ABI_VERSION
        // is added automatically by Flutter. (https://developer.android.com/studio/build/configure-apk-splits#configure-APK-versions)
        // You can force using the value of versionCode by specifying the `-P force-version-code-ignoring-abi=true`
        // flag during build.
        versionCode = flutter.versionCode
        versionName = flutter.versionName

        // 目标盒子是 Amlogic arm64-v8a：只保留 arm64 一个架构，
        // 包体最小、无 32 位兼容负担（配合 gradle.properties 的 disable-abi-filtering=true）
        ndk {
            abiFilters.add("arm64-v8a")
        }
    }

    // 目标盒子是 Amlogic arm64-v8a：只保留 arm64 一个架构，
    // 包体最小、无 32 位兼容负担

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    buildTypes {
        release {
            // TODO: Add your own signing config for the release build.
            // Signing with the debug keys for now, so `flutter run --release` works.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

flutter {
    source = "../.."
}

