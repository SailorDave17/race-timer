// The Android half of the shared code: the leaf managers that touch the platform's audio, haptic and
// clock APIs, and which both form factors run (#200, epic #196 decision D1).
//
// Deliberately leaf managers only. `TimerService` does NOT live here — each app keeps its own service
// shell, and the ordering rules it holds are duplicated on purpose until that duplication is measured
// to hurt. Moving a service shell here would drag notification channels, foreground-service types and
// two different lifecycle stories into one class to serve a form factor it was not written for.
//
// What may depend on what, and it is one-way in both directions:
//
//     :shared          pure JVM, no Android types at all, all tests run on the JVM
//        ^
//     :shared-android  Android types, no app identity, no UI, no service
//        ^                    ^
//     :wear                :phone        <- never reference each other
//
// No test source set here, and that is a decision rather than an omission. Every class in this module
// is bound to `android.media` / `android.os` behaviour that a JVM test cannot reach and a Robolectric
// shadow would only pretend to; the audio and haptic path is the exact scope
// `race-timer-testing-strategy` rules out for Robolectric, and #160's scoped yes covers the non-audio
// surfaces instead. The instrument for this code is a race run on a wrist -- AC 6 of #200.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    // Not `com.racetimer.shared.android`: the module is shared between apps, so its package must not
    // read as belonging to either, and a package segment that shadows the platform's own `android`
    // root is a resolution question nobody should have to think about while reading a cue path.
    namespace = "com.racetimer.android"
    compileSdk = 35

    defaultConfig {
        // Matches :wear, and matches epic #196 decision D4 for the phone. One minSdk across both apps
        // is what lets the API-level branches in here -- HapticManager's 33 split, ToneManager's 30
        // start-threshold path -- stay one branch each rather than one per form factor.
        minSdk = 30
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    // `api`, not `implementation`: the moved classes take and return :shared types -- ToneManager.prepare
    // takes a CueStream, HapticManager.play takes a SignalPattern -- so a consumer cannot call them
    // without those types on its own compile classpath.
    api(project(":shared"))
}
