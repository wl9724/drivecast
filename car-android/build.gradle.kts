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
        versionCode = 2
        versionName = "0.1.0"
    }

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
    testImplementation("junit:junit:4.13.2")
}
