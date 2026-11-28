plugins { id("org.jetbrains.kotlin.jvm") }

// [CORE-JSON] Parser JSON kecil tanpa dependensi (keluaran alat luar). JVM murni, tanpa Android.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
