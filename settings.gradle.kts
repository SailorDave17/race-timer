// #211. Release signing, the #156 keystore guard and the #184 archive task live in an included
// build so :wear and :phone share one copy instead of forking a second. It is `build-logic` rather
// than `buildSrc` deliberately: buildSrc's dependencies join the root buildscript classpath, and AGP
// arriving there with an unknown version makes every versioned plugin alias in this repo
// unresolvable. Measured on this change.
pluginManagement {
    includeBuild("build-logic")

    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

// Lets Gradle download a matching JDK for the toolchains the modules declare (`:shared` asks for
// JVM 8, which is rarely installed nowadays). Without a resolver, a clean build on a machine with
// only a modern JDK fails outright with "No matching toolchains found".
// Version is hardcoded: the version catalog is not available to settings.gradle.kts.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "race-timer"
include(":shared")
include(":shared-android")
include(":wear")
include(":phone")
