plugins { id("org.jetbrains.kotlin.jvm") }

// [CORE-FILEOPS] Operasi berkas Penjelajah (salin, pindah, hapus, zip). JVM murni: tanpa Android dan tanpa jaringan.
// Pustaka eksternal: zip4j (ZIP berkata sandi AES), commons-compress dan xz (7z/tar/gz/bz2/xz). Terkunci oleh scripts/offline_gate.py (LOCKED).
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
    implementation(project(":core:files")) // [CORE-FILEOPS] ExplorerList.looksText memakai FileTypes
    implementation(libs.zip4j)
    implementation(libs.commons.compress) // ArchiveEngine: 7z/tar/gz/bz2/xz (versi: gradle/libs.versions.toml)
    implementation(libs.tukaani.xz) // dekoder XZ/LZMA2 (versi: gradle/libs.versions.toml)
}
