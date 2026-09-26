// An included build, not buildSrc (#211).
//
// buildSrc's dependencies join the ROOT build's buildscript classpath, so pinning AGP there makes
// it "already on the classpath with an unknown version" and Gradle then refuses every versioned
// plugin request in the repo — `alias(libs.plugins.android.application)` in all four modules and the
// root. Measured on this change: the only way through with buildSrc is to strip the version from
// every alias, which drags :shared and :shared-android into a release-signing story.
//
// An included build resolves its plugins through the normal plugin-marker mechanism instead, so
// nothing leaks and every module's plugins block is untouched.
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"
