// #218's throwaway skew harness: a build of its own, which the product build and CI never see.
//
// Standalone on purpose (owner decision at #218 pickup, 2026-09-25). This is the repo's first code
// on the Wearable Data Layer, and #219 is the story that puts play-services-wearable into the shipped
// modules, with the manifest and Play-declaration checks that go with it. So the dependency lives
// here, in a build nothing ships from, and the product's version catalog never names it. Deleting
// this directory removes the harness completely.
//
// Built from the repo root with the root wrapper - see README.md beside this file:
//
//     ./gradlew -p harness/pair-skew :phone:assembleDebug :wear:assembleDebug
pluginManagement {
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

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
    // The product's catalog, read in place rather than copied, so AGP, Kotlin and JUnit here are
    // whatever the product builds with on the day the harness is built.
    versionCatalogs {
        create("libs") {
            from(files("../../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "pair-skew-harness"
include(":protocol", ":common", ":phone", ":wear", ":analysis")
