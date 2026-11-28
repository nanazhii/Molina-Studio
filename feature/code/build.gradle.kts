plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.molina.code"
    compileSdk = 35

    defaultConfig { minSdk = 26 }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = false }
}

dependencies {
    implementation(project(":core:preview")) // [CORE-PREVIEW]
    implementation(project(":core:exec")) // [CORE-EXEC]
    implementation(project(":core:files")) // [FILE-CONTRACT]
    implementation(project(":core:json")) // [CORE-JSON] parser JSON bersama
    implementation(project(":core:ui")) // [MINT-ICONS] ikon tree bersama
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.sora.core)
    implementation(libs.sora.textmate) // [CODE-TM] highlight TextMate; versi sama dengan editor
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.activity.compose)
}

