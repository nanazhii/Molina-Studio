plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// [FEATURE-EXPLORER] UI Penjelajah (Berkas) Molina: dipakai bersama Terminal, Cinema, Unduhan, dan tab Berkas.
// Hanya bergantung ke :core (aturan module_gate). Diperiksa offline_gate per berkas (tidak bisa LOCKED: memakai :core:library).
android {
    namespace = "com.molina.explorer"
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
    implementation(project(":core:ui")) // Win7*, Mint*, MolinaTheme
    implementation(project(":core:library")) // MediaSidecar, SidecarRows, ThumbMath
    implementation(project(":core:fileops")) // FileOps, ExplorerList
    implementation(project(":core:files")) // FileTypes
    implementation(project(":core:system")) // MolinaPaths (w7Favorites)
    implementation(project(":core:api")) // LogLevel
    implementation(project(":core:log")) // LogBus
    implementation(libs.androidx.activity.compose) // izin penyimpanan, BackHandler
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.kotlinx.coroutines.core)
}
