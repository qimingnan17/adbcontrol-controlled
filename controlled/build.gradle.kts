plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.adbcontrol.controlled"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.adbcontrol.controlled"
        minSdk = 31
        targetSdk = 34

        // 版本可由 CI 参数覆盖:./gradlew assembleDebug -PversionCode=42 -PversionName=1.4.2
        // (OTA workflow 用 git rev-list 计数作 versionCode,tag 名作 versionName)
        versionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1
        versionName = (project.findProperty("versionName") as String?) ?: "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    // v2.0 签名:keystore 不再入库,从环境变量读取(CI 由 Secret SIGNING_KEYSTORE_BASE64 解码得到文件)。
    //   SIGNING_KEYSTORE_PATH / SIGNING_STORE_PASSWORD / SIGNING_KEY_ALIAS / SIGNING_KEY_PASSWORD
    // 未提供时:debug 回退 AGP 默认调试签名(~/.android/debug.keystore);release 同样回退并打印警告
    // (CI 在 workflow 中强制校验 Secret 存在,防止发布出签名不一致、无法覆盖安装的包)。
    // ⚠️ 未轮换密钥:CI Secret 必须是原 keystore/debug.keystore 的 base64,否则现网设备 OTA 会签名不一致。
    val signingKeystorePath: String? = System.getenv("SIGNING_KEYSTORE_PATH")?.takeIf { it.isNotBlank() }
    val hasEnvSigning = signingKeystorePath != null && file(signingKeystorePath).exists() &&
        !System.getenv("SIGNING_STORE_PASSWORD").isNullOrEmpty() &&
        !System.getenv("SIGNING_KEY_ALIAS").isNullOrEmpty()
    signingConfigs {
        if (hasEnvSigning) {
            create("env") {
                storeFile = file(signingKeystorePath!!)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")?.takeIf { it.isNotEmpty() }
                    ?: System.getenv("SIGNING_STORE_PASSWORD")
            }
        }
    }
    if (!hasEnvSigning) {
        logger.warn("SIGNING_KEYSTORE_PATH 等签名环境变量未配置,构建将使用 AGP 默认调试签名(不可用于覆盖现网设备)")
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            // 有环境签名时 debug 也用它(便于本地覆盖安装现网设备);否则默认调试签名
            if (hasEnvSigning) signingConfig = signingConfigs.getByName("env")
        }
        release {
            // 任务 9:MIUI 安装包优化,启用 R8 代码压缩 + 资源压缩,降低 APK 体积。
            // proguard-rules.pro 已保留 Shizuku / shared / Paho / AWS S3;Hilt / WorkManager /
            // Compose 的 consumer rules 由各自 AAR 自带,无需在此额外配置。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // OTA 要求签名一致:release 使用环境变量提供的固定签名(CI 从 Secret 解码),
            // 才能覆盖安装现网设备上的既有包(否则 INSTALL_FAILED_UPDATE_INCOMPATIBLE)。
            signingConfig = if (hasEnvSigning) signingConfigs.getByName("env") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/INDEX.LIST"
            excludes += "/META-INF/io.netty.versions.properties"
            // Apache HttpComponents 同时随 httpclient / httpcore 携带同名 DEPENDENCIES 描述文件
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/LICENSE"
            excludes += "/META-INF/NOTICE"
            excludes += "/META-INF/*.kotlin_module"
        }
    }
}

dependencies {
    implementation(project(":shared"))

    // AndroidX core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.material)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)

    // WorkManager
    implementation(libs.work.runtime.ktx)
    implementation(libs.androidx.datastore)

    // Serialization / network
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    // MQTT
    implementation(libs.paho.mqtt)

    // Security crypto (EncryptedFile)
    implementation(libs.androidx.security.crypto)

    // Shizuku(主桥接)。注意:dev.rikka.hidden:stub 在版本目录登记为 4.3.0,
    // 但 Maven Central 实际未发布该版本(已有 4.3.1+);且 shizuku-api 不传递依赖它,
    // 本模块仅用 rikka.shizuku.Shizuku(newProcess / pingBinder 等),不直接引用 hidden API,
    // 故不引入 hidden-api-stub。后续若需直连 IShellService,需协调 backend agent 修正版本号。
    implementation(libs.shizuku.provider)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.shared)

    // QR 扫码:内嵌 ZXing(离线完整扫码界面)。原 ML Kit GmsBarcodeScanner 运行时需从
    // Google Play 下载 Barcode UI 模块,国行 ROM 无 Play 服务时永远卡在"模块下载中"。
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("androidx.appcompat:appcompat:1.7.0")

    // Play App Update
    implementation(libs.play.app.update)
    implementation(libs.play.app.update.ktx)

    // AWS S3 (R2 object storage for screenshot bypass)
    implementation(libs.aws.s3)

    // Camera (fallback QR scan via CameraX)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.camera.lifecycle)
}
