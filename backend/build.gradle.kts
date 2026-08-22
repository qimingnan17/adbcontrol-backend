plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktor)
}

// Ktor Gradle plugin 已 apply application 插件,提供 run / installDist 等任务。
application {
    mainClass = "com.adbcontrol.backend.ApplicationKt"
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

// 依赖 :shared(BUG#3):共享协议模型。后端暂保留镜像 data class 以不破坏现有 import,后续再迁移类型。
dependencies {
    implementation(project(":shared"))
    implementation(libs.kotlinx.serialization.json)

    // Ktor server
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.auth)
    implementation("io.ktor:ktor-server-auth-jvm:${libs.versions.ktor.get()}")   // Session auth 走 auth jvm 集成
    implementation("io.ktor:ktor-server-sessions-jvm:${libs.versions.ktor.get()}") // Ktor 3.x 用 -jvm suffix
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.config.yaml)
    implementation("io.ktor:ktor-server-cors-jvm:${libs.versions.ktor.get()}")  // Bug#23: CORS 插件, Ktor 3.x 用 -jvm suffix
    implementation("org.mindrot:jbcrypt:0.4")
    implementation("commons-codec:commons-codec:1.17.0") // PairingService generatePairToken 的 Base32 编码

    // Ktor client(EMQX REST 代理)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.client.auth)

    // 数据库
    implementation(libs.hikari)
    implementation(libs.mysql.connector)

    // MQTT(遥测 ingestor,见 TelemetryIngestService)
    implementation(libs.paho.mqtt)

    // 日志
    implementation(libs.logback.classic)

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
