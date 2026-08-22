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
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
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
