rootProject.name = "smoke-newpipe"

dependencyResolutionManagement {
    versionCatalogs {
        // Proyek mandiri ini memakai katalog root agar versi hanya hidup di satu tempat [CATALOG].
        create("libs") { from(files("../../gradle/libs.versions.toml")) }
    }
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // JitPack hanya untuk grup com.github.* (host build-time); sisanya wajib dari Maven Central.
        exclusiveContent {
            forRepository { maven("https://jitpack.io") }
            filter { includeGroupByRegex("com\\.github\\..*") }
        }
        mavenCentral()
    }
}
