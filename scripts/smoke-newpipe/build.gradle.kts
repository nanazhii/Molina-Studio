plugins {
    kotlin("jvm") version "2.0.21"
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(providers.gradleProperty("coord").get())
    implementation(libs.kotlinx.coroutines.core) // [SHIELD-NP] core/net memakai StateFlow
}

application {
    mainClass.set("SmokeKt")
    applicationDefaultJvmArgs = listOf("-DnpCoord=${providers.gradleProperty("coord").get()}")
}
