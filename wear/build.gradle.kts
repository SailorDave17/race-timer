plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    // #211. Release signing, the #156 keystore guard and the #184 archive task moved to
    // buildSrc/src/main/kotlin/racetimer.release-artifacts.gradle.kts so :phone inherits them
    // instead of forking a second copy. Nothing about this module's release behaviour changed.
    id("racetimer.release-artifacts")
}


android {
    namespace = "com.racetimer.wear"
    compileSdk = 35

    defaultConfig {
        // Play/on-device identity. Deliberately different from `namespace` above, which stays
        // com.racetimer.wear so the Kotlin packages and the -keep rule in proguard-rules.pro are
        // untouched. Registered under Android developer verification as "Mad Cow Race Timer".
        applicationId = "io.github.sailordave17.racetimer"
        minSdk = 30
        // Wear OS is a carve-out from the general Play target-API rule: API 35 by 2026-08-31,
        // where regular apps must reach 36. See #69.
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }


    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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

    // #192. `bundleRelease` runs `lintVitalRelease`, so lint gates the release build even though
    // this repo deliberately has no lint step of its own (2026-08-02). AGP 8.13's lint fails that
    // task with 70 instances of exactly one check.
    //
    // Measured on the bump: `androidx.wear:wear:1.3.0` drags `androidx.fragment:fragment:1.2.4`,
    // and the check fires because `registerForActivityResult` is used while the resolved Fragment
    // is below 1.3.0. The combination it guards -- activity-result plumbing hosted in a Fragment --
    // CANNOT occur here: nothing under `wear/src` references Fragment at all, and `:phone`, which
    // has no fragment on its classpath, passes the same task untouched.
    //
    // Scoped to this one check on this one module on purpose. A lint baseline would absorb future
    // instances of the same id for genuinely different reasons, and forcing Fragment up seven
    // minors on a library this app never calls would change runtime bytes on the hardware-verified
    // cue path to fix a report rather than a fault.
    //
    // REVISIT IF A FRAGMENT IS EVER INTRODUCED HERE -- at that point the check stops being a
    // false positive and the right move becomes raising the dependency.
    lint {
        disable += "InvalidFragmentVersionForActivityResult"
    }

    testOptions {
        unitTests {
            // Robolectric resolves the merged manifest and this module's resources from the built
            // test APK. Without it the FGS-type and notification assertions would be asking about an
            // empty package, and `R.drawable.ic_stat_race_timer` would not resolve.
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(project(":shared"))
    // The leaf managers the phone app will consume too (#200, epic #196 D1). It re-exports
    // :shared via `api`, so the line above is redundant on the classpath and kept anyway: this
    // module's own code reads :shared types directly, and a dependency that is only reachable
    // through somebody else's `api` breaks the day that somebody stops needing it.
    implementation(project(":shared-android"))

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

    // #160: the module's first test source set, four months after the module. Robolectric, scoped
    // to the non-audio surfaces by the owner decision on that issue â€” the residue seven extractions
    // into `:shared` could not reach is orchestration, and an ordering of calls is a property a
    // shadow answers exactly (it is the framework's own bookkeeping, not physics).
    //
    // The boundary is not prose: `AudioHapticBoundaryTest` fails the build if a test under this
    // source set ever asserts on the audio or haptic path. A green run here says nothing about
    // #114, #144 or #126 â€” those are hardware, and this dependency does not make them otherwise.
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
}
