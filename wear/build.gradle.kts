import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Release signing reads a gitignored keystore.properties at the repo root. CI has no such
// file and must still configure, so the release signingConfig only exists when the file does —
// an unsigned release build there is fine, since CI never builds release (#71).
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        keystorePropsFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.racetimer.wear"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.racetimer.wear"
        minSdk = 30
        // Wear OS is a carve-out from the general Play target-API rule: API 35 by 2026-08-31,
        // where regular apps must reach 36. See #69.
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }
}

// Retain the release bundle and its R8 mapping outside build/ (#127).
//
// `build/outputs/` is wiped by `clean` and excluded by .gitignore, so the mapping a future crash
// report has to be deobfuscated against does not survive the next build. Both are copied into
// release-archive/v<versionCode>/ at the repo root, which neither touches. versionCode is the stamp
// because it is the value Play makes permanently unique per upload — see docs/release-signing.md.
//
// The task asserts its inputs rather than copying what it finds: a copy with a missing source is a
// silent no-op, and an empty archive is indistinguishable from one that was never needed.
val releaseVersionCode = android.defaultConfig.versionCode
val releaseBundle = layout.buildDirectory.file("outputs/bundle/release/wear-release.aab")
val releaseMapping = layout.buildDirectory.file("outputs/mapping/release/mapping.txt")

val archiveReleaseArtifacts = tasks.register("archiveReleaseArtifacts") {
    group = "publishing"
    description = "Copies the release bundle and R8 mapping into release-archive/v<versionCode>/."
    doLast {
        val bundle = releaseBundle.get().asFile
        val mapping = releaseMapping.get().asFile
        // Plain ASCII in these messages on purpose: a Windows console renders an em dash here as a
        // replacement character, which reads as corruption at the moment the build is already failing.
        check(bundle.isFile) { "No release bundle at ${bundle.path} - run :wear:bundleRelease first." }
        check(mapping.isFile) {
            "No R8 mapping at ${mapping.path}. isMinifyEnabled must stay true for the release build, " +
                "or a crash report from this bundle cannot be deobfuscated."
        }
        val destination = rootProject.layout.projectDirectory.dir("release-archive/v$releaseVersionCode").asFile
        destination.mkdirs()
        bundle.copyTo(destination.resolve(bundle.name), overwrite = true)
        mapping.copyTo(destination.resolve("mapping.txt"), overwrite = true)
        logger.lifecycle("Archived ${bundle.name} and mapping.txt to ${destination.path}")
    }
}

// AGP creates bundleRelease lazily, so it does not exist while this script is being configured —
// tasks.named("bundleRelease") here fails the build outright with "Task with name 'bundleRelease'
// not found". A live filtered collection matches it whenever AGP gets round to adding it.
tasks.matching { it.name == "bundleRelease" }.configureEach { finalizedBy(archiveReleaseArtifacts) }

dependencies {
    implementation(project(":shared"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    // Wear Compose
    implementation(libs.wear.compose.material)
    implementation(libs.wear.compose.foundation)
    implementation(libs.wear.compose.navigation)

    // Wear OS core
    implementation(libs.wear.core)
    implementation(libs.wear.ongoing)

    // Compose tooling (debug only)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.wear.compose.ui.tooling)
}
