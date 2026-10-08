plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303") // 读测试向量
}

// 测试读 docs/ 下的共享测试向量：声明成输入，向量变了本地测试才会重跑
tasks.test {
    inputs.file(rootProject.file("docs/testvectors/ios-pairing.json"))
}
