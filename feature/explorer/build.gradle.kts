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
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
}
