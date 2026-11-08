rootProject.name = "spike-newpipe"

dependencyResolutionManagement {
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
