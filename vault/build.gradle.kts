plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.molina.vault"
    compileSdk = 34
    ndkVersion = "27.3.13750724" // [NDK-PIN] sama dengan NDK default image runner GitHub
    defaultConfig {
        applicationId = "com.molina.vault"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += "arm64-v8a" }
    }
    // [VAULT-SIGN] Kunci tetap dari secrets CI (sama dengan Studio). Tanpa itu dipakai kunci debug (tidak stabil antar build).
    signingConfigs {
        val ksPath = System.getenv("MOLINA_KEYSTORE_FILE")
        if (ksPath != null && File(ksPath).isFile) {
            create("molina") {
                storeFile = File(ksPath)
                storeType = "pkcs12"
                storePassword = System.getenv("MOLINA_KEYSTORE_PASSWORD")
                keyAlias = "molina"
                keyPassword = System.getenv("MOLINA_KEYSTORE_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("molina") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
