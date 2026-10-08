plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 不是给用户装的 App：车机通过 ADB 推到手机，用 app_process 以 shell 身份运行
android {
    namespace = "org.drivecast.server"
    compileSdk = 34

    defaultConfig {
        applicationId = "org.drivecast.server"
        minSdk = 29 // 虚拟屏 + 按 displayId 注入输入需要 Android 10
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    lint { checkReleaseBuilds = false }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":protocol"))
}
