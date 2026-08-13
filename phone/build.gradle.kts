// The phone app: a standalone start-sequence timer for the committee-boat console (#197, epic #196).
//
// Standalone, not a companion. It runs the same `TimerEngine` on the same monotonic anchor as the
// watch, and shares its identity on Play (`applicationId` below) so the two form factors ship under
// one listing.
//
// What this module deliberately does NOT hold yet, so a reader does not go looking:
//   - audio cues (#202) and screen-off cueing (#203) — this countdown is silent and foreground-only
//   - keep-screen-on and full brightness (#199 owns the mechanism, #225 who chooses it)
//   - release signing, versioning and archiving (#211 hoists wear's out of `wear/build.gradle.kts`;
//     until then this module has no release signingConfig and `versionCode` is not an upload
//     candidate — epic decision D3 gives both form factors one monotonic counter, and allocating
//     from it is that story's job)
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    // The Kotlin package, which is a different thing from the app identity below and need not match
    // it — see cairn `android-applicationid-vs-namespace`. `com.racetimer.wear` is the watch's and
    // nothing here may reference it (asserted by ModuleBoundaryTest).
    namespace = "com.racetimer.phone"
    compileSdk = 35

    defaultConfig {
        // The SAME identity as :wear, on purpose: one Play listing carrying both form factors, which
        // is what lets a sailor find one app rather than two. Play locks this name permanently at
        // first upload and it is already locked by the watch's, so it is not a choice this module
        // gets to make differently.
        applicationId = "io.github.sailordave17.racetimer"
        // Epic #196 decision D4. Matches :wear and :shared-android, so the FGS and notification
        // behaviour matrix is one matrix rather than one per form factor.
        minSdk = 30
        // API 35 for now, per epic decision D7: this module is scaffolded on the toolchain the repo
        // already runs (AGP 8.6.1 / SDK 35) rather than waiting for #191's AGP 9 crossing. The
        // Wear carve-out does NOT cover a phone artifact — Play requires API 36 for phone uploads
        // after 2026-08-31 — so #192 gates the first upload (#214), not this scaffold.
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        // Pinned to exactly the Kotlin version in the catalog; see the note on `kotlin` there.
        kotlinCompilerExtensionVersion = "1.5.8"
    }

    testOptions {
        unitTests {
            // Robolectric resolves the merged manifest and the module's resources from the built
            // test APK; without this it sees neither, and a test asking whether the launcher
            // activity is reachable would be asking about an empty package.
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    // The timer core, and the Android leaf managers that carry it onto a device. `:shared-android`
    // re-exports `:shared` via `api`, so the first line is redundant on the classpath and kept
    // anyway: this module reads `:shared` types directly, and a dependency reachable only through
    // somebody else's `api` breaks the day that somebody stops needing it.
    implementation(project(":shared"))
    implementation(project(":shared-android"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    // The race survives a rotation because the engine lives in a ViewModel, not in the activity.
    // A phone propped on a console gets picked up and turned; recreating the activity would
    // restart the countdown, which on a committee boat is the whole product failing at once.
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)

    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    // Scoped to the non-audio surfaces per #160's owner decision — see the catalog comment.
    testImplementation(libs.robolectric)
}
