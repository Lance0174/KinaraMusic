import java.io.File
import java.util.Properties
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val unsignedRelease = providers.gradleProperty("ciUnsigned").isPresent
val localBuildSettings = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}
fun privateBuildSetting(gradleName: String, localName: String, envName: String): String =
    providers.gradleProperty(gradleName).orNull
        ?: localBuildSettings.getProperty(localName)
        ?: providers.environmentVariable(envName).orNull
        ?: ""
fun buildConfigString(value: String): String = "\"" + value
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
    .replace("\r", "\\r")
    .replace("\n", "\\n") + "\""
val solaraBuildUrl = privateBuildSetting("kinaraSolaraBaseUrl", "kinara.solaraBaseUrl", "KINARA_SOLARA_BASE_URL")
val solaraBuildSecret = privateBuildSetting("kinaraSolaraSecret", "kinara.solaraSecret", "KINARA_SOLARA_SECRET")
val biliWorkerBuildBases = privateBuildSetting("kinaraBiliWorkerBases", "kinara.biliWorkerBases", "KINARA_BILI_WORKER_BASES")
// Keep credentials outside source archives; a missing release key must never fall back to debug.
val signingSettingsPath = providers.gradleProperty("kinaraSigningProperties").orNull
    ?: providers.environmentVariable("KINARA_SIGNING_PROPERTIES").orNull
    ?: localBuildSettings.getProperty("kinara.signingProperties")
val signingSettingsFile = signingSettingsPath?.let { rootProject.file(it) }
val releaseSigning = Properties().apply {
    if (!unsignedRelease) signingSettingsFile?.takeIf { it.isFile }?.inputStream()?.use { load(it) }
}
val releaseSigningReady = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
    .all { !releaseSigning.getProperty(it).isNullOrBlank() }

android {
    namespace = "com.kinara.music"
    compileSdk = 34

    // 只出 arm64-v8a：现代 Android 设备均为 64 位，不再单独产 armeabi-v7a 包。
    // abiFilter 属性仅用于 x86_64 模拟器调试（如 MuMu），正式构建不受影响。
    val debugAbi = (project.findProperty("abiFilter") as String?)
        ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
    splits {
        abi {
            isEnable = true
            reset()
            if (debugAbi.isNullOrEmpty()) include("arm64-v8a") else include(*debugAbi.toTypedArray())
            isUniversalApk = false
        }
    }

    defaultConfig {
        applicationId = "com.kinara.music"
        minSdk = 24
        targetSdk = 34
        versionCode = 8
        versionName = "1.3.0"
        // Release artifacts may carry locally supplied Solara settings; the public source defaults to empty.
        buildConfigField("String", "SOLARA_BASE_URL", buildConfigString(solaraBuildUrl))
        buildConfigField("String", "SOLARA_SECRET", buildConfigString(solaraBuildSecret))
        // A public checkout has no Bilibili proxy. Local builds may provide comma-separated HTTPS bases.
        buildConfigField("String", "BILI_WORKER_BASES", buildConfigString(biliWorkerBuildBases))
        // 只保留中英文资源，去掉其它语言的 Compose/AndroidX 字符串，减小体积
        resourceConfigurations += listOf("en", "zh")
    }

    signingConfigs {
        if (!unsignedRelease) create("kinaraRelease") {
            storeFile = releaseSigning.getProperty("storeFile")?.let {
                val path = File(it)
                if (path.isAbsolute) path else File(signingSettingsFile!!.parentFile, it)
            }
            storeType = releaseSigning.getProperty("storeType", "PKCS12")
            storePassword = releaseSigning.getProperty("storePassword")
            keyAlias = releaseSigning.getProperty("keyAlias")
            keyPassword = releaseSigning.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // M0 阶段沿用 debug 签名占位；正式发版前替换为专用签名密钥（密钥不入库）。
            // 2026-10-17：已改为固定 Kinara 发布密钥，CI 明确选择未签名构建。
            signingConfig = if (unsignedRelease) null else signingConfigs.getByName("kinaraRelease")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-P", "plugin:androidx.compose.compiler.plugins.kotlin:strongSkipping=true")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    testOptions.unitTests.isIncludeAndroidResources = true
    testOptions.unitTests.all {
        it.systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
        // Robolectric's ParcelFileDescriptor shadow reflects FileDescriptor on JDK 17.
        it.jvmArgs("--add-opens=java.base/java.io=ALL-UNNAMED")
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// AGP may omit validateSigningRelease when credentials are incomplete, so enforce this
// independently before release builds instead of relying on that optional task alone.
val validateKinaraReleaseSigning = tasks.register("validateKinaraReleaseSigning") {
    group = "verification"
    description = "Require the fixed release key unless an unsigned CI build was explicitly requested."
    doLast {
        check(unsignedRelease || releaseSigningReady) {
            "Kinara Release signing is not configured. Set kinara.signingProperties in local.properties, " +
                "or pass -PkinaraSigningProperties=<private signing.properties>. " +
                "Use -PciUnsigned=true only for an intentionally unsigned build."
        }
    }
}

tasks.matching { it.name == "preReleaseBuild" || it.name == "validateSigningRelease" }.configureEach {
    dependsOn(validateKinaraReleaseSigning)
}

val verifyFoliaBundle = tasks.register("verifyFoliaBundle") {
    doLast {
        val root = rootProject.file("visualizer")
        val manifest = Properties().apply { root.resolve("bundle.properties").inputStream().use { load(it) } }
        val source = (listOf("build.mjs", "package.json", "package-lock.json").map(root::resolve) +
            listOf("src", "vendor").flatMap { root.resolve(it).walkTopDown().filter(File::isFile).toList() })
            .sortedBy { it.relativeTo(root).invariantSeparatorsPath }
        val digest = MessageDigest.getInstance("SHA-256")
        source.forEach { file ->
            digest.update(file.relativeTo(root).invariantSeparatorsPath.toByteArray(Charsets.UTF_8)); digest.update(byteArrayOf(0))
            digest.update(file.readBytes())
        }
        fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
        check(digest.digest().hex() == manifest.getProperty("source.sha256")) { "Folia sources changed. Run npm ci && npm run build in visualizer/." }
        manifest.stringPropertyNames().filter { it != "source.sha256" }.forEach { key ->
            val asset = file("src/main/assets/folia/${key.removeSuffix(".sha256")}")
            check(MessageDigest.getInstance("SHA-256").digest(asset.readBytes()).hex() == manifest.getProperty(key)) { "Folia asset differs from verified bundle: ${asset.name}" }
        }
    }
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(verifyFoliaBundle) }

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")

    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    // 播放内核：Media3/ExoPlayer + MediaSession（锁屏/耳机/蓝牙控制）
    implementation("androidx.media3:media3-exoplayer:1.4.0")
    implementation("androidx.media3:media3-session:1.4.0")
    implementation("androidx.media3:media3-common:1.4.0")

    // 封面加载（领导批示 D4：引入 Coil；2.7.0 已在本机依赖缓存）
    implementation("io.coil-kt:coil-compose:2.7.0")
    // 只在本机生成官方登录二维码；不向第三方二维码服务发送登录链接。
    implementation("com.google.zxing:core:3.5.3")

    // 液态玻璃使用 Compose GraphicsLayer + Android 12 RenderEffect，无额外渲染依赖。

    // 单元测试：纯 JVM 可离线运行，不进 APK（教训 E004：不引入缓存中没有的测试依赖）
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
