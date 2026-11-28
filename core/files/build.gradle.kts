plugins { id("org.jetbrains.kotlin.jvm") }

// [CORE-FILES] Kontrak berkas murni JVM: tanpa Android, tanpa jaringan, tanpa dependensi proyek lain.
// Terkunci oleh scripts/offline_gate.py (LOCKED).
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
