// What both apps run: the exchange engine, the Data Layer glue, the log, and one screen. The phone and
// watch apps are shells around it; only their manifests differ.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.racetimer.pairskew"
    compileSdk = 35

    defaultConfig {
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
    implementation(project(":protocol"))
    // Named here and never in gradle/libs.versions.toml. The product's first GMS dependency is #219's
    // to add, together with the merged-manifest and Play-declaration checks that story carries. This
    // build ships nothing.
    implementation("com.google.android.gms:play-services-wearable:20.0.1")
}
