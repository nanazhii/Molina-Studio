plugins { id("org.jetbrains.kotlin.jvm") }

// [CORE-NET] Satu-satunya tempat kode berjaringan bersama (kebijakan URL, Molina Shield, IPTV, pin IP).
// JVM murni. TERLARANG bagi modul yang memegang data pengguna (lihat FORBIDDEN_PROJECTS di scripts/offline_gate.py).
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
    implementation(project(":core:api")) // [LOG-CONTRACT] AppLog/LogLevel
    implementation(libs.kotlinx.coroutines.core)
}
