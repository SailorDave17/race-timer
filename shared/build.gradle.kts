// Pure Kotlin/JVM library — no Android SDK dependency.
// Unit tests run on the JVM without a device or emulator.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

kotlin {
    jvmToolchain(8)
}

dependencies {
    testImplementation(libs.junit)
}
