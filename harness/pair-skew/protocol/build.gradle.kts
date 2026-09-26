// Pure JVM: the harness's one line format, the frame every round is read in, and the constants both
// apps share. It compiles :shared's main sources in place, so the translateGun called here IS the
// product's, byte for byte. #217 was built so that the spike and the production link consume it
// unchanged, and a copy would be a second implementation to keep in step.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions.jvmTarget = "1.8"
}

kotlin {
    sourceSets["main"].kotlin.srcDir("../../../shared/src/main/kotlin")
}

dependencies {
    testImplementation(libs.junit)
}
