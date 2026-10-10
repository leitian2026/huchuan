plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// CI 每次构建的序号（GitHub Actions 自动递增），本地构建则为 0
val buildNo = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0

android {
    namespace = "com.hulian.transfer"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.hulian.transfer"
        minSdk = 29
        targetSdk = 34
        versionCode = 1000 + buildNo
        versionName = "0.3.$buildNo"
    }

    // 固定签名：每次 CI 构建签名一致，才能直接覆盖安装升级（发布版沿用同一个 keystore，所以能覆盖之前装的调试版）
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        create("release") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true        // R8 代码压缩 + 混淆
            isShrinkResources = true      // 去掉未使用的资源
            isDebuggable = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")   // 远程中转：WebSocket（Cloudflare）和 WebDAV（坚果云）
    implementation("com.squareup.okhttp3:okhttp-dnsoverhttps:4.12.0")   // 自建 DoH（版本要和 okhttp 一致）

    testImplementation("junit:junit:4.13.2")
}
