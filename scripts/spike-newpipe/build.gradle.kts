import java.security.MessageDigest

plugins { base }

val spike by configurations.creating

dependencies { spike(providers.gradleProperty("coord").get()) }

// Hanya me-resolve dan mencetak hash; tidak mengeksekusi kode unduhan.
tasks.register("printResolved") {
    notCompatibleWithConfigurationCache("spike")
    doLast {
        spike.resolvedConfiguration.resolvedArtifacts.sortedBy { it.file.name }.forEach { a ->
            val sha = MessageDigest.getInstance("SHA-256").digest(a.file.readBytes())
                .joinToString("") { "%02x".format(it) }
            println("RESOLVED ${a.moduleVersion.id}  $sha  ${a.file.name}")
        }
    }
}
