plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.nous.aurora"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nous.aurora"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.2a3c"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.cardview:cardview:1.0.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.fragment:fragment-ktx:1.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Room database (industrial standard)
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Coil image loading (Kotlin-first)
    implementation("io.coil-kt:coil:2.7.0")

    // kotlinx.serialization (type-safe JSON)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // CommonMark (industrial Markdown parser)
    implementation("org.commonmark:commonmark:0.22.0")
    implementation("org.commonmark:commonmark-ext-gfm-tables:0.22.0")

    // Readium Kotlin Toolkit
    implementation("org.readium.kotlin-toolkit:readium-shared:3.1.2")
    implementation("org.readium.kotlin-toolkit:readium-streamer:3.1.2")
    implementation("org.readium.kotlin-toolkit:readium-navigator:3.1.2")
    implementation("org.readium.kotlin-toolkit:readium-adapter-pdfium:3.1.2")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")
}