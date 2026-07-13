plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "ab.inque.converter"
    compileSdk = 35

    defaultConfig {
        applicationId = "ab.inque.converter"
        minSdk = 27
        targetSdk = 35
        versionCode = 24
        versionName = "0.1.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // Play 16 KB page-size: drop x86/x86_64 (TAndroidLame was 4 KB-aligned on 64-bit x86).
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildTypes {
        debug {
            // Debug builds already get premium via BuildConfig.DEBUG in ConversionPrefs.
            buildConfigField("boolean", "PREMIUM_ENABLED_BY_DEFAULT", "false")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
            // Flip to false when shipping with points / in-app premium purchases.
            buildConfigField("boolean", "PREMIUM_ENABLED_BY_DEFAULT", "true")
        }
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
    lint {
        disable.add("NullSafeMutableLiveData")
    }
    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
        resources {
            excludes.add("META-INF/native-image/**")
            excludes.add("META-INF/licenses/**")
            excludes.add("META-INF/DEPENDENCIES")
            excludes.add("META-INF/INDEX.LIST")
            excludes.add("META-INF/NOTICE")
            excludes.add("META-INF/LICENSE")
            excludes.add("META-INF/LICENSE.txt")
            excludes.add("META-INF/NOTICE.txt")
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.material)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.viewpager2)
    implementation("com.github.chrisbanes:PhotoView:2.3.0")
    implementation(libs.billing.client.ktx)
    implementation(libs.play.services.ads)
    // File handling and storage
    implementation("androidx.documentfile:documentfile:1.0.1")
    // PDF operations
    implementation("com.itextpdf:itext7-core:7.2.5")
    implementation("com.itextpdf:html2pdf:4.0.5")
    // EPUB handling
    implementation("org.jsoup:jsoup:1.16.2")
    // HEIC encoding (device-dependent)
    implementation("androidx.heifwriter:heifwriter:1.1.0")
    // Word document creation
    implementation("org.apache.poi:poi:5.2.4")
    implementation("org.apache.poi:poi-ooxml:5.2.4")
    // ML Kit OCR (image → text)
    implementation("com.google.mlkit:text-recognition:16.0.1")
    // MKV remux (stream copy after MediaCodec MP4 encode); audio variant includes libmp3lame (16 KB aligned)
    implementation("dev.ffmpegkit-maintained:ffmpeg-kit-audio:8.1.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation("dev.ffmpegkit-maintained:ffmpeg-kit-audio:8.1.7")
}