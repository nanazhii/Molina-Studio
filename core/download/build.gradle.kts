plugins { id("org.jetbrains.kotlin.jvm") }

// [CORE-DOWNLOAD] Perintah, sesi, riwayat dan perencanaan unduhan. JVM murni; bergantung ke :core:stream.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api(project(":core:stream"))
    implementation(project(":core:common"))
    implementation(project(":core:json"))
}
