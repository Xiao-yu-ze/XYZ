plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// MuMu / AOSP emulators are x86_64. Including that ABI lets OpenCV run natively there
// instead of through the ARM translation layer (libnb.so), which is much faster and
// avoids loading a 24 MB ARM .so under emulation.
//   ./gradlew :app:assembleDebug -PincludeX86=true
// Real devices are ARM, so the default build stays lean (~100 MB smaller).
val includeX86: Boolean = (findProperty("includeX86") as String?)?.toBoolean() ?: false

android {
    namespace = "com.pvz2.auto"
    compileSdk = 35

    /**
     * 单测里允许调用 android.util.Log 等框架方法。
     *
     * 默认行为是抛 "Method e in android.util.Log not mocked" ——
     * 于是任何在**失败路径**上打日志的代码都没法在 JVM 单测里跑，
     * 而失败路径恰恰最需要测：坏 JSON、文件不存在、权限错误。
     */
    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    defaultConfig {
        applicationId = "com.pvz2.auto"
        minSdk = 26          // Android 8.0 as required
        targetSdk = 34       // Android 14: foreground service types are enforced
        versionCode = 1
        versionName = "1.0"

        // The OpenCV AAR ships arm64-v8a / armeabi-v7a / x86 / x86_64 natives (~121 MB total).
        // Real phones only need the two ARM ABIs; dropping x86* removes ~100 MB from the APK.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
            if (includeX86) abiFilters += "x86_64"
        }

        // 采集端必须是 native 的：Java 的 ImageReader / SurfaceTexture 给的 Surface
        // buffer usage 不够，建出来的虚拟屏会被系统标成 untrusted（窗口不跟、注入被丢）。
        // 见 cpp/capture.cpp 里的说明。
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_static")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        // AGP 8 defaults this to false. Required for src/main/aidl/IPvz2Service.aidl.
        aidl = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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

    packaging {
        resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    // 预览固定在顶部、功能页左右滑动切换。viewpager2 之前只是传递依赖，显式声明避免版本漂移。
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")  // repeatOnLifecycle
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // MaterialButton for the styled action buttons. The app theme therefore has to
    // inherit a MaterialComponents theme (see values/themes.xml).
    implementation("com.google.android.material:material:1.12.0")

    // Official Shizuku coordinates, exactly as the Shizuku-API README documents.
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    // Official OpenCV Android artifact published on Maven Central since 4.9.0.
    implementation("org.opencv:opencv:4.13.0")

    // PaddleOCR inference for the Chinese UI text the Endless flow reads
    // (开始挑战 / 清空卡牌 / 继续挑战 / 第 / 确定 / 最后一关 / 0/80).
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")

    // ---- 单元测试（纯 JVM，不需要模拟器/真机）----
    testImplementation("junit:junit:4.13.2")
    // org.json 必须显式加这条。Android SDK 里虽然有 org.json，但那是桩实现，
    // 单测环境下一调用就抛 "not mocked"。加了这条真实现才会生效。
    testImplementation("org.json:json:20240303")
}
