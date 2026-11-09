plugins { id("org.jetbrains.kotlin.jvm") }

// [CORE-EXEC] Pelaksana skrip/proses bersama (run, plan/RunPlan, stream/StreamTransport). JVM murni tanpa dependensi.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
