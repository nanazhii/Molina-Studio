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
    packaging {
        jniLibs {
            useLegacyPackaging = true
            // [APK-GATE] Biner ber-pin SHA-256 tidak boleh diubah AGP (strip): isi di APK harus identik dengan sumber di repo.
            keepDebugSymbols += setOf("**/lib7zz.so", "**/libproot.so", "**/libproot_loader.so")
        }
    }
}

// [DEBUG-OFF] Hanya varian release yang dibangun; varian debug (dan turunannya) dinonaktifkan.
androidComponents {
    beforeVariants(selector().withBuildType("debug")) { it.enable = false }
}

dependencies {
    implementation(project(":core:stream"))
    implementation(project(":core:download"))
    implementation(project(":core:net")) // [CORE-NET]
    coreLibraryDesugaring(libs.android.desugar.jdk.libs.nio)
    implementation(project(":terminal-view"))
    implementation(project(":feature:mpv"))
    implementation(project(":feature:code"))
    implementation(project(":feature:explorer")) // [FEATURE-EXPLORER]
    implementation(project(":core:exec")) // [CORE-EXEC]
    implementation(project(":feature:preview")) // [NANO-PREVIEW]
    implementation(project(":engine:newpipe"))
    implementation(project(":core:diag"))
    implementation(project(":core:library"))
    implementation(project(":core:policy"))
    implementation(project(":core:system"))
    implementation(project(":core:files")) // [FILE-CONTRACT]
    implementation(project(":core:fileops")) // [CORE-FILEOPS]
    implementation(project(":core:storage"))
    implementation(project(":core:ui"))
    implementation(project(":core:security"))
    implementation(project(":core:api"))
    implementation(project(":core:log"))
    implementation(libs.commons.compress)
    implementation(libs.kotlinx.coroutines.android)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
}
