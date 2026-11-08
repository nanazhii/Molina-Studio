plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// [WORKSPACE-W1] Folder Kerja: logika berkas dan folder utama (tanpa Compose; UI masuk di W2).
android {
    namespace = "com.molina.workspace"
    compileSdk = 35

    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = false }
}
