plugins {
    id("com.android.application")
}

android {
    namespace = "com.yalu.localconnect"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.yalu.localconnect"
        // 29 而不是 26：需要 ContentResolver.loadThumbnail()（API 29 引入）来安全地
        // 生成缩略图。它的好处是内部按目标尺寸解码，不会把整张原图读进内存
        // （一张 4000x3000 的照片直接解码要占约 48MB，几十张必然 OOM）。
        // 当前真机是 API 36、模拟器是 API 37，提到 29 不损失任何设备覆盖。
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.10.1")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.10.0")
    implementation("androidx.activity:activity:1.8.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    // Ktor 客户端：协程优先的网络库，WebSocket 用法是 for (frame in incoming) 这种顺序写法
    implementation("io.ktor:ktor-client-core:3.6.0")
    implementation("io.ktor:ktor-client-cio:3.6.0")
    implementation("io.ktor:ktor-client-websockets:3.6.0")
    // 协程：Ktor 的挂起函数需要协程作用域来跑
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}