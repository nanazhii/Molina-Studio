plugins { id("org.jetbrains.kotlin.jvm") }

// [CORE-POLICY] Kebijakan dan bukti keamanan (intent, skrip bawaan, komponen bundel, visibilitas app). JVM murni.
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
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
}
