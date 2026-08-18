plugins {
    `kotlin-dsl`
}

dependencies {
    // AGP so the convention plugin compiles against the typed `ApplicationExtension` DSL. Version
    // comes from the root catalog (settings.gradle.kts above), so what this compiles against cannot
    // drift from the AGP the modules run — which is the failure #192 would otherwise have created
    // silently, by moving one and not the other.
    //
    // `implementation` is correct here, unlike in buildSrc: an included build does not contribute to
    // the root build's buildscript classpath, so nothing is put in front of the modules' own
    // versioned plugin aliases.
    implementation("com.android.tools.build:gradle:${libs.versions.agp.get()}")
}
