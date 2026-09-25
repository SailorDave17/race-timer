// A shell: everything the watch app runs lives in :common. The phone and the watch differ only in
// their manifests.
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.racetimer.pairskew.wear"
    compileSdk = 35

    defaultConfig {
        // Must equal :phone's: the Data Layer connects only apps with the same applicationId and the
        // same signing certificate.
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
