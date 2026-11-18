plugins { id("org.jetbrains.kotlin.jvm") }

// [CORE-PREVIEW] Render Markdown/HTML/JSON + PreviewKind bersama. JVM murni; dipakai feature:code dan feature:preview.
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
    implementation(project(":core:exec")) // RunPlans (dipakai PreviewKinds)
}
