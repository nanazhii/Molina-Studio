plugins { id("org.jetbrains.kotlin.jvm") }

// [CORE-LIBRARY] Rak media (sidecar, kartu rak, progres tonton, tempat sampah, panduan TV). JVM murni.
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
    implementation(project(":core:json"))
}
