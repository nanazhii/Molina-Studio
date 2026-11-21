plugins { id("org.jetbrains.kotlin.jvm") }

// [CORE-SYSTEM] Proses, monitor, dan jalur sistem. JVM murni.
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
}
