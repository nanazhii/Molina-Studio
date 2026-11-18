plugins { id("org.jetbrains.kotlin.jvm") }

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// Hanya kontrak (interface dan data class). Tanpa Android, tanpa implementasi.
dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
}
