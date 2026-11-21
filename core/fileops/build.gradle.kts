plugins { id("org.jetbrains.kotlin.jvm") }

// [CORE-FILEOPS] Operasi berkas Penjelajah (salin, pindah, hapus, zip). JVM murni: tanpa Android dan tanpa jaringan.
// Satu-satunya pustaka eksternal adalah zip4j (ZIP berkata sandi AES). Terkunci oleh scripts/offline_gate.py (LOCKED).
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
    implementation("net.lingala.zip4j:zip4j:2.11.5")
}
