pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // NewPipeExtractor v0.26.5 (estável, tag release) + nanojson (commit transitivo)
        maven("https://jitpack.io")
    }
}
rootProject.name = "NexYou"
include(":app")
