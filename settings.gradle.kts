pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google(); mavenCentral()
        // JitPack hanya untuk NewPipeExtractor + nanojson (grup com.github.TeamNewPipe); hash dipin
        // di scripts/newpipe/pins.sha256 dan diverifikasi tugas verifyNewPipePins.
        exclusiveContent {
            forRepository { maven("https://jitpack.io") }
            filter { includeGroup("com.github.TeamNewPipe") }
        }
    }
}
rootProject.name = "molina"
include(":app", ":core:common", ":core:storage", ":core:exec", ":feature:preview", ":terminal-emulator", ":terminal-view", ":feature:mpv", ":feature:code", ":engine:newpipe")

include(":vault")
