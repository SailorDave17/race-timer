// A stand-in for the race-committee companion, for the companion link's device test only (#294).
//
// It carries the companion's own application id, `com.procompanion.app`, because race-timer binds
// that package by explicit component and nothing else, and a test that bound anything else would
// prove a target race-timer never uses. So it can never be installed beside a real companion, and
// `harness/companion-link/run.sh` uninstalls whatever holds the id before it installs this.
//
// Never shipped and never built by CI: no gate step names it, and docs/declared-surface.lock reads
// :wear and :phone only. It answers ADR 004's wire exactly, trusts every caller, and keeps what it
// receives in memory for the test to read back.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    // The namespace is the package too, so `.LinkService` resolves to the production class name,
    // com.procompanion.app.LinkService, exactly as in pro-companion.
    namespace = "com.procompanion.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.procompanion.app"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "stub"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

// #194. The Kotlin target on the `kotlin` extension, the form AGP 9's built-in Kotlin also reads.
// Why not `kotlinOptions` in `android { }`, and why kotlin.android stays for now: wear/build.gradle.kts.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
    }
}
