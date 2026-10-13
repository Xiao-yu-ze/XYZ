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
    implementation("androidx.core:core-ktx:1.13.1")            // ServiceCompat.startForeground (androidx-core >= 1.12)
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")  // repeatOnLifecycle
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Official Shizuku coordinates, exactly as the Shizuku-API README documents.
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    // Official OpenCV Android artifact published on Maven Central since 4.9.0.
    implementation("org.opencv:opencv:4.13.0")

    // PaddleOCR inference for the Chinese UI text the Endless flow reads
    // (开始挑战 / 清空卡牌 / 继续挑战 / 第 / 确定 / 最后一关 / 0/80).
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
}
