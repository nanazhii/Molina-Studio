plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.molina.studio"
    compileSdk = 35
    ndkVersion = "27.3.13750724" // [NDK-PIN] sama dengan NDK default image runner GitHub

    defaultConfig {
        applicationId = "com.molina.studio"
        minSdk = 26
        targetSdk = 28 // exec binary PRoot dari data dir (lihat blueprint)
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    signingConfigs {
        // Kunci tetap dari secrets CI; tanpa env ini dipakai keystore debug bawaan.
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
        debug {
            signingConfigs.findByName("molina")?.let { signingConfig = it }
            // [WORKSPACE-NEUTRAL-ALL] Folder kerja sama dengan rilis: tanpa override dari luar.
            buildConfigField("String", "WORKSPACE_DIR", "\"Molina\"") // [WORKSPACE-IN-MOLINA]
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            buildConfigField("String", "WORKSPACE_DIR", "\"Molina\"") // [WORKSPACE-NEUTRAL] [WORKSPACE-IN-MOLINA]
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro") // [R8-OPTIMIZE]
            signingConfigs.findByName("molina")?.let { signingConfig = it }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    lint { abortOnError = false }
    packaging { jniLibs { useLegacyPackaging = true } }
}

// [DEBUG-OFF] Hanya varian release yang dibangun; varian debug (dan turunannya) dinonaktifkan.
androidComponents {
    beforeVariants(selector().withBuildType("debug")) { it.enable = false }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
    implementation(project(":terminal-view"))
    implementation(project(":feature:mpv"))
    implementation(project(":feature:code"))
    implementation(project(":core:exec")) // [CORE-EXEC]
    implementation(project(":feature:preview")) // [NANO-PREVIEW]
    implementation(project(":feature:workspace")) // [WORKSPACE-W1]
    implementation(project(":engine:newpipe"))
    implementation(project(":core:common"))
    implementation(project(":core:storage"))
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("net.lingala.zip4j:zip4j:2.11.5")
    implementation("org.tukaani:xz:1.10") // [ARCHIVE] dekoder XZ/LZMA2 untuk tar.xz, xz, dan 7z
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
}
