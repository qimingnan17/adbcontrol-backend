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
    implementation("io.ktor:ktor-server-compression-jvm:${libs.versions.ktor.get()}") // 静态资源/API 响应 gzip,缓解弱网首屏
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

    // cron 调度(TaskSchedulerService,UNIX 5 字段,与 shared 测试同口径)
    implementation(libs.cron.utils)

    // 日志
    implementation(libs.logback.classic)

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    // 路由层回归测试(/emqx 鉴权绕过回归,见 EmqxRoutesAuthBypassTest)
    testImplementation("io.ktor:ktor-server-test-host:${libs.versions.ktor.get()}")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

// ============ 前端构建产物自动同步 ============
// 将 ../adbcontrol-web/dist 镜像进后端静态资源目录，替代此前手动执行的：
//   cp -r adbcontrol-web/dist/* adbcontrol-backend/backend/src/main/resources/static/
//
// 行为说明：
//  - 本地（monorepo 同级 checkout）构建时会自动把最新前端产物同步进 static，构建即生效；
//  - CI / Fly 等仅 checkout 后端仓库的场景下没有 ../adbcontrol-web/dist，任务自动跳过，
//    回退使用仓库中已提交的 src/main/resources/static。
//  - Sync 为镜像语义：会删除 static 中不属于 dist 的旧哈希文件，避免残留。
val webDistDir = rootProject.projectDir.parentFile.resolve("adbcontrol-web/dist")

val syncWebDist by tasks.registering(Sync::class) {
    description = "同步前端构建产物 (../adbcontrol-web/dist) 到后端静态资源目录"
    group = "build"
    onlyIf { webDistDir.resolve("index.html").isFile }
    from(webDistDir)
    into(layout.projectDirectory.dir("src/main/resources/static"))
    doFirst { logger.lifecycle("[syncWebDist] $webDistDir -> src/main/resources/static") }
}

// 在资源处理前完成同步，确保 processResources / jar / distZip / run 都打包到最新前端
// （dist 不存在时该任务会被跳过，不影响后端独立构建）
tasks.named("processResources") {
    dependsOn(syncWebDist)
}
