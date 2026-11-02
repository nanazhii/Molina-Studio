plugins { id("com.android.library") }

android {
    namespace = "com.molina.terminal"
    compileSdk = 35
    defaultConfig {
        minSdk = 26
        ndk { abiFilters += listOf("arm64-v8a") }
        externalNativeBuild {
            // tanpa -Werror (asli Termux) agar tidak gagal oleh warning compiler NDK baru
            ndkBuild { cFlags("-std=c11", "-Os", "-fno-stack-protector") }
        }
    }
    externalNativeBuild { ndkBuild { path = file("src/main/jni/Android.mk") } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}
