plugins { id("org.jetbrains.kotlin.jvm") }

// [CORE-STREAM] Resolusi stream, kualitas, urutan dan parser yt-dlp. JVM murni; boleh berjaringan lewat :core:net.
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
    api(project(":core:net"))
    implementation(project(":core:json"))
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
}
