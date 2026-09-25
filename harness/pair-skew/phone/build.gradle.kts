// A shell: everything the phone app runs lives in :common. The phone and the watch differ only in
// their manifests.
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.racetimer.pairskew.phone"
    compileSdk = 35

    defaultConfig {
        // The scratch identity #218 asks for, so the harness installs beside the release app rather
        // than over it. The Data Layer connects only apps with the same applicationId AND the same
        // signing certificate: :wear carries this id too, and both are built debug-signed on one
        // machine.
        applicationId = "io.github.sailordave17.racetimer.pairskew"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "218-harness"
    }
}

dependencies {
    implementation(project(":common"))
}
