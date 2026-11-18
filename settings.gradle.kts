pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "TurboLLM"
include(":composeApp")
include(":turbollm-sdk")

// The Rust core (UniFFI bindings) lives in the turbollm checkout; PR-1 put the Android module there.
val turbollmDir = providers.gradleProperty("turbollm.dir")
    .map { file(it) }
    .getOrElse(rootDir.resolve("../turbollm"))
include(":turbollm-core")
project(":turbollm-core").projectDir = file("$turbollmDir/core/bindings/android/turbollm-core")
