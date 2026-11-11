import java.security.MessageDigest

plugins { id("org.jetbrains.kotlin.jvm") }

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// Versi dipin; harus sama dengan nama berkas di scripts/newpipe/pins.sha256.
val newpipeVersion = "v0.26.5"

dependencies {
    api(project(":core:common"))
    implementation("com.github.TeamNewPipe:NewPipeExtractor:$newpipeVersion")
}

// Gerbang rantai pasok: setiap jar pada pins.sha256 wajib ada di runtimeClasspath dengan SHA-256
// yang sama. Jika versi transitif bergeser atau isi jar berubah, build gagal sebelum kompilasi.
val verifyNewPipePins by tasks.registering {
    notCompatibleWithConfigurationCache("memeriksa hash artefak")
    val pinsFile = rootProject.file("scripts/newpipe/pins.sha256")
    inputs.file(pinsFile)
    doLast {
        val info = file("src/main/kotlin/com/molina/engine/newpipe/NewPipeInfo.kt").readText()
        if (!info.contains("VERSION = \"$newpipeVersion\"")) {
            throw GradleException("NewPipeInfo.VERSION tidak sama dengan newpipeVersion ($newpipeVersion); samakan keduanya.")
        }
        val pins = pinsFile.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .associate { line ->
                val parts = line.split(Regex("\\s+"), limit = 2)
                require(parts.size == 2) { "baris pins.sha256 tidak valid: $line" }
                parts[1].trim() to parts[0].lowercase()
            }
        val files = configurations.getByName("runtimeClasspath").resolve().associateBy { it.name }
        val problems = mutableListOf<String>()
        for ((name, expected) in pins) {
            val f = files[name]
            if (f == null) {
                problems += "tidak ada di runtimeClasspath: $name"
                continue
            }
            val actual = MessageDigest.getInstance("SHA-256").digest(f.readBytes())
                .joinToString("") { "%02x".format(it) }
            if (actual != expected) problems += "SHA-256 TIDAK COCOK: $name"
        }
        if (problems.isNotEmpty()) {
            throw GradleException(
                "Verifikasi pin NewPipe gagal:\n  - " + problems.joinToString("\n  - ") +
                    "\nTinjau dulu sebelum memperbarui scripts/newpipe/pins.sha256."
            )
        }
        logger.lifecycle("Pin NewPipe terverifikasi: ${pins.size} jar.")
    }
}
tasks.named("compileKotlin") { dependsOn(verifyNewPipePins) }
