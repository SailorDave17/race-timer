// Reads captures and writes the numbers #218 is written up from. JVM only. Every offset and bound it
// reports goes through translateGun by way of :protocol, so a bound it gives for a policy is the bound
// production would compute on the same rounds.
//
//     ./gradlew -p harness/pair-skew :analysis:run --args="<report.md> <capture> [<capture>...]"
plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

application {
    mainClass.set("com.racetimer.pairskew.analysis.AnalyseKt")
}

// :protocol's target, which this module links against. Kotlin refuses a Java/Kotlin target mismatch.
java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

// #193: `compilerOptions`, because Kotlin 2.2 refuses the task-level `kotlinOptions` as an error.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
}

dependencies {
    implementation(project(":protocol"))
    testImplementation(libs.junit)
}
