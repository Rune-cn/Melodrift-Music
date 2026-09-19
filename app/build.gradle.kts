plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.melodrift.music"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.melodrift.music"
        minSdk = 26
        targetSdk = 36
        versionCode = 6
        versionName = "1.0.5"
        // 仅打包 arm64-v8a
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
        // 仅打包中文与英文资源（其他语言不进入 APK）
        resourceConfigurations += listOf("zh-rCN", "en")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 签名密钥不随源码分发：密钥库存在且密码非空时才启用（恢复方法见 README.md）
            val keystore = file("../melodrift.keystore")
            val storePass = project.findProperty("KEYSTORE_PASS") as? String
            val keyPass = project.findProperty("KEY_PASS") as? String
            if (keystore.exists() && !storePass.isNullOrEmpty() && !keyPass.isNullOrEmpty()) {
                signingConfig = signingConfigs.create("release") {
                    storeFile = keystore
                    storePassword = storePass
                    keyAlias = "melodrift"
                    keyPassword = keyPass
                }
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    // Compose M3（稳定版）
    implementation(platform("androidx.compose:compose-bom:2026.08.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.compose.animation:animation")

    // 网络 / 协程 / 图片
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("io.coil-kt:coil-compose:2.6.0")

    // 播放（media3 ExoPlayer + OkHttp 数据源：更稳的 http 播放）
    implementation("androidx.media3:media3-exoplayer:1.11.0")
    implementation("androidx.media3:media3-datasource-okhttp:1.11.0")
    // 通知栏媒体控制（MediaStyle）
    implementation("androidx.media:media:1.8.0")
}