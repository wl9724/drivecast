plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "org.drivecast.car"
    compileSdk = 34

    defaultConfig {
        applicationId = "org.drivecast.car"
        minSdk = 21 // 覆盖老旧后装大屏
        targetSdk = 34
        // CI 用运行序号，保证每次发布都比上一次大，车机上能直接覆盖升级
        versionCode = System.getenv("DRIVECAST_VERSION_CODE")?.toIntOrNull()?.plus(100) ?: 2
        versionName = "0.1.0"
        // Conscrypt 的 so 只要 ARM 的：车机都是 ARM，x86 的两份会让 APK 再大 2 MB
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
    }

    // 固定的发布签名：密钥只在 CI 的 Secrets 里，本地和 fork 构建没有它时只能出 debug 包
    val keystore = System.getenv("DRIVECAST_KEYSTORE")
    if (!keystore.isNullOrEmpty()) {
        signingConfigs {
            create("release") {
                storeFile = file(keystore)
                storeType = "pkcs12"
                storePassword = System.getenv("DRIVECAST_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("DRIVECAST_KEY_ALIAS")
                keyPassword = System.getenv("DRIVECAST_KEYSTORE_PASSWORD") // PKCS12 的密钥密码和库密码相同
            }
        }
        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("release")
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

/** 把手机端投屏服务 APK 打进车机 App 的 assets，连接时推到手机。 */
abstract class ServerAssetTask : DefaultTask() {
    @get:InputFiles
    abstract val serverApk: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val out = outputDir.get().asFile
        out.listFiles()?.forEach { it.delete() }
        serverApk.asFileTree.matching { include("*.apk") }.singleFile
            .copyTo(File(out, "drivecast-server.apk"), overwrite = true)
    }
}

val serverAsset = tasks.register<ServerAssetTask>("serverAsset") {
    serverApk.from(project(":phone-server").layout.buildDirectory.dir("outputs/apk/release"))
    serverApk.builtBy(":phone-server:assembleRelease")
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(serverAsset, ServerAssetTask::outputDir)
    }
}

dependencies {
    implementation(project(":protocol"))
    // 无线调试的 TLS 1.3 和 exporter：车机系统 Android 10 之前没有 TLS 1.3（Apache-2.0）
    implementation("org.conscrypt:conscrypt-android:2.7.0")
    testImplementation("junit:junit:4.13.2")
    // 单元测试跑在电脑的 JVM 上：用带 Linux/macOS/Windows so 的版本，测真的 TLS
    testImplementation("org.conscrypt:conscrypt-openjdk-uber:2.7.0")
}

// 两个 Conscrypt 的类同名，单元测试只留 JVM 版（安卓版会去加载 ARM 的 so）
configurations.configureEach {
    if (name.endsWith("UnitTestRuntimeClasspath")) exclude(group = "org.conscrypt", module = "conscrypt-android")
}
