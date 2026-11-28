plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.molina.cinema"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("proguard-rules.pro") // keep MPVLib (JNI)
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = false }
}

dependencies {
    implementation(project(":core:stream"))
    implementation(project(":core:api")) // [LOG-CONTRACT]
    implementation(project(":core:net")) // [CORE-NET]
    // Versi diturunkan dari upstream agar cocok dengan AGP 8.7.3 / compileSdk 35 (lihat docs/UPSTREAM.md)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.media)
    implementation(libs.androidx.preference.ktx)
    implementation(libs.google.material)
    implementation(project(":core:diag"))
    implementation(project(":core:library"))
    implementation(project(":core:policy"))
    implementation(project(":core:system"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(project(":core:files")) // [FILE-CONTRACT]
}
