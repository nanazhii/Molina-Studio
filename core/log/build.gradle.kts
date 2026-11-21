plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.molina.core.log"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    api(project(":core:api")) // [LOG-CONTRACT]
    api(project(":core:diag"))
    api(project(":core:system"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
}
