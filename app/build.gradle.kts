plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.hddownloader.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.hddownloader.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 10
        versionName = "1.8"
    }

    flavorDimensions += "edition"

    productFlavors {
        create("full") {
            dimension = "edition"
            // Bari wali app: dono engines (NewPipe + yt-dlp backup)
        }
        create("lite") {
            dimension = "edition"
            applicationIdSuffix = ".lite"
            versionNameSuffix = "-lite"
            // Halki app (~15MB): sirf NewPipe + apna FB parser, koi Python engine nahi
        }
    }

    buildFeatures {
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // yt-dlp / ffmpeg native binaries must be extracted to disk to be executed
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    // One APK per CPU type (small) + one universal APK that works on every phone
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    // yt-dlp engine SIRF full version me (lite ka size isi liye chhota hai)
    "fullImplementation"("io.github.junkfood02.youtubedl-android:library:0.18.1")
    "fullImplementation"("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")

    // NewPipeExtractor: YouTube fast fetch (Option A hybrid — yt-dlp backup rahega)
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.24.8")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Thumbnail loading
    implementation("io.coil-kt:coil:2.7.0")

    // Video preview player
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
}

// CI (purana workflow) APKs ko outputs/apk/debug me dhundta hai — flavors ki
// asal output (full/debug, lite/debug) wahan copy kar do, workflow badle baghair
val copyApksForCi = tasks.register<Copy>("copyApksForCi") {
    from(layout.buildDirectory.dir("outputs/apk/full/debug"))
    from(layout.buildDirectory.dir("outputs/apk/lite/debug"))
    into(layout.buildDirectory.dir("outputs/apk/debug"))
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
}
tasks.matching { it.name == "assembleDebug" }.configureEach {
    finalizedBy(copyApksForCi)
}
