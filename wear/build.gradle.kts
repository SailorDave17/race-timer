import java.time.ZonedDateTime
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Release signing reads a gitignored keystore.properties at the repo root. A build with no such
// file must still configure, so the release signingConfig only exists when the file does. ci.yml
// builds release on every push since #129 and writes no such file — it builds unsigned, which is
// the point of that gate (R8 runs on every push).
//
// Since #81 an uploadable bundle is no longer local-only: release.yml materialises a keystore from
// GitHub secrets and points `keystorePropertiesFile` at it, outside the checkout. So there are now
// three graphs, not two — unsigned in ci.yml, signed in release.yml, signed locally — and the
// property below is what the second one turns on.
//
// The path is a property only so the guard below can be proven without going near a real credential
// file. Nothing in normal use passes it, and it defaults to the documented location.
val keystorePropsFile = rootProject.file(
    providers.gradleProperty("keystorePropertiesFile").getOrElse("keystore.properties")
)
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        keystorePropsFile.inputStream().use { load(it) }
    }
}

// Refuse a half-filled keystore.properties instead of signing with it (#156).
//
// The state this guards is not hypothetical: the file shipped as a template with REPLACE_ME on both
// password lines and sat that way for two days. Because the file *exists*, the signingConfig is
// created and the release path is taken, so the build dies deep inside signReleaseBundle with
// `keystore password was incorrect` — which reads as a wrong password, i.e. as a broken backup,
// rather than as a setup step nobody finished. It cost a wrong diagnosis before a grep settled it.
//
// Configuration-time, not a doFirst on bundleRelease: signReleaseBundle runs *before* that task's
// actions, so a doFirst there would fire after the confusing failure it exists to replace.
val signingKeys = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
val unfilledSigningKeys = if (keystorePropsFile.exists()) {
    signingKeys.filter { key ->
        val value = keystoreProps.getProperty(key)
        value.isNullOrBlank() || value.contains("REPLACE_ME")
    }
} else {
    emptyList()
}

// Scoped to release builds so an unfilled file never blocks :shared:test or a debug build — the
// machine is only misconfigured for the thing it cannot do. CI has no file at all, so the list is
// empty there and this can never fire.
if (unfilledSigningKeys.isNotEmpty() &&
    gradle.startParameter.taskNames.any { it.contains("release", ignoreCase = true) }
) {
    throw GradleException(
        buildString {
            appendLine("${keystorePropsFile.name} exists but is not filled in.")
            appendLine("Unset or still REPLACE_ME: ${unfilledSigningKeys.joinToString(", ")}")
            appendLine()
            appendLine("This is a setup step, not a bad keystore. Both passwords take the same value,")
            appendLine("from Google Password Manager entry race-timer-upload-keystore.com (its username")
            appendLine("field carries the key alias). See docs/release-signing.md.")
            appendLine()
            append("Refusing rather than signing, because the failure it replaces reads as a wrong password.")
        }
    )
}

android {
    namespace = "com.racetimer.wear"
    compileSdk = 35

    defaultConfig {
        // Play/on-device identity. Deliberately different from `namespace` above, which stays
        // com.racetimer.wear so the Kotlin packages and the -keep rule in proguard-rules.pro are
        // untouched. Registered under Android developer verification as "Mad Cow Race Timer".
        applicationId = "io.github.sailordave17.racetimer"
        minSdk = 30
        // Wear OS is a carve-out from the general Play target-API rule: API 35 by 2026-08-31,
        // where regular apps must reach 36. See #69.
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    // #192. `bundleRelease` runs `lintVitalRelease`, so lint gates the release build even though
    // this repo deliberately has no lint step of its own (2026-08-02). AGP 8.13's lint fails that
    // task with 70 instances of exactly one check.
    //
    // Measured on the bump: `androidx.wear:wear:1.3.0` drags `androidx.fragment:fragment:1.2.4`,
    // and the check fires because `registerForActivityResult` is used while the resolved Fragment
    // is below 1.3.0. The combination it guards -- activity-result plumbing hosted in a Fragment --
    // CANNOT occur here: nothing under `wear/src` references Fragment at all, and `:phone`, which
    // has no fragment on its classpath, passes the same task untouched.
    //
    // Scoped to this one check on this one module on purpose. A lint baseline would absorb future
    // instances of the same id for genuinely different reasons, and forcing Fragment up seven
    // minors on a library this app never calls would change runtime bytes on the hardware-verified
    // cue path to fix a report rather than a fault.
    //
    // REVISIT IF A FRAGMENT IS EVER INTRODUCED HERE -- at that point the check stops being a
    // false positive and the right move becomes raising the dependency.
    lint {
        disable += "InvalidFragmentVersionForActivityResult"
    }

    testOptions {
        unitTests {
            // Robolectric resolves the merged manifest and this module's resources from the built
            // test APK. Without it the FGS-type and notification assertions would be asking about an
            // empty package, and `R.drawable.ic_stat_race_timer` would not resolve.
            isIncludeAndroidResources = true
        }
    }
}

// Retain the release bundle and its R8 mapping outside build/ (#127).
//
// `build/outputs/` is wiped by `clean` and excluded by .gitignore, so the mapping a future crash
// report has to be deobfuscated against does not survive the next build. Both are copied into
// release-archive/v<versionCode>/ at the repo root, which neither touches. versionCode is the stamp
// because it is the value Play makes permanently unique per upload — see docs/release-signing.md.
//
// The task asserts its inputs rather than copying what it finds: a copy with a missing source is a
// silent no-op, and an empty archive is indistinguishable from one that was never needed.
val releaseVersionCode = android.defaultConfig.versionCode
val releaseVersionName = android.defaultConfig.versionName
val releaseBundle = layout.buildDirectory.file("outputs/bundle/release/wear-release.aab")
val releaseMapping = layout.buildDirectory.file("outputs/mapping/release/mapping.txt")

// #184. The archive keys on versionCode alone, and versionCode moves once per Play upload while
// branches move constantly - so every release build on every branch wrote to the same directory and
// the last one won, silently.
//
// *Measured 2026-08-12*: the bundle prepared for the first Play upload was replaced THREE times in
// three hours by ordinary gate runs on an unrelated story's branch, each time swapping a reviewed
// artifact for one carrying unmerged code, while docs/releases.md went on describing the old one.
// Nothing failed. The .aab is gitignored, so `git status` stayed clean, and a signed bundle from the
// wrong branch is indistinguishable from the right one without opening the dex.
//
// The stakes are why this is a refusal rather than a warning: the archive is what a human uploads to
// Play, a versionCode can never be reclaimed once burned, and the package name locks permanently at
// first upload.
//
// **The discriminator is (commit, clean/dirty), not commit alone**, and that is not a detail - all
// three occurrences had the SAME commit. A feature branch cut from the integration branch and not yet
// committed has exactly the base SHA, so a commit-only check would have passed every time while the
// working tree carried the change that made the artifact wrong. Clean@X -> dirty@X is the real signal.
val repoRootDir = rootProject.layout.projectDirectory.asFile
val archiveOverwriteAllowed = providers.gradleProperty("archiveOverwrite").isPresent

fun gitOutput(root: java.io.File, vararg args: String): String =
    try {
        val process = ProcessBuilder(listOf("git", *args))
            .directory(root)
            .redirectErrorStream(true)
            .start()
        val text = process.inputStream.bufferedReader().readText().trim()
        if (process.waitFor() == 0) text else ""
    } catch (_: Exception) {
        // No git on PATH, or not a checkout. Provenance degrades to "unknown" rather than failing the
        // build: an archive with a weaker stamp still beats no archive, and a release built outside a
        // checkout is somebody's deliberate choice.
        ""
    }

val archiveReleaseArtifacts = tasks.register("archiveReleaseArtifacts") {
    group = "publishing"
    description = "Copies the release bundle and R8 mapping into release-archive/v<versionCode>/, " +
        "refusing to overwrite an archive built from different sources."
    doLast {
        val bundle = releaseBundle.get().asFile
        val mapping = releaseMapping.get().asFile
        // Plain ASCII in these messages on purpose: a Windows console renders an em dash here as a
        // replacement character, which reads as corruption at the moment the build is already failing.
        check(bundle.isFile) { "No release bundle at ${bundle.path} - run :wear:bundleRelease first." }
        check(mapping.isFile) {
            "No R8 mapping at ${mapping.path}. isMinifyEnabled must stay true for the release build, " +
                "or a crash report from this bundle cannot be deobfuscated."
        }

        val commit = gitOutput(repoRootDir, "rev-parse", "HEAD").ifEmpty { "unknown" }
        val branch = gitOutput(repoRootDir, "rev-parse", "--abbrev-ref", "HEAD").ifEmpty { "unknown" }
        val dirty = gitOutput(repoRootDir, "status", "--porcelain").isNotEmpty()
        // The identity line is what gets compared. Everything else in the file is for a human.
        val identity = "$commit dirty=$dirty"

        val destination = rootProject.layout.projectDirectory.dir("release-archive/v$releaseVersionCode").asFile
        val provenance = destination.resolve("provenance.txt")

        if (provenance.isFile) {
            val recorded = provenance.readLines()
                .firstOrNull { it.startsWith("identity: ") }
                ?.removePrefix("identity: ")
                ?.trim()
                .orEmpty()
            if (recorded.isNotEmpty() && recorded != identity && !archiveOverwriteAllowed) {
                // Two different answers, because these are two different situations.
                //
                // A DIRTY build is never an upload candidate - the commit does not describe it - so
                // there is nothing to decide: skip, say so loudly, leave the archive alone, and let
                // the build carry on. That matters because :wear:bundleRelease is one of the three
                // quality-gate commands, and failing it would break the gate for a reason that has
                // nothing to do with the code under test. All three measured clobbers were this case.
                //
                // A CLEAN build at a different commit is a genuine conflict between two shippable
                // artifacts, and nobody should guess which one wins. That one refuses, per the
                // allowlist-that-forces-a-question shape: the cost is an edit, and the edit is where
                // somebody asks which bundle they actually meant.
                if (dirty) {
                    logger.lifecycle(
                        buildString {
                            appendLine("NOT archiving: the working tree is dirty and the archive holds a different build.")
                            appendLine("  keeping:    $recorded")
                            appendLine("  not stored: $identity  (branch $branch)")
                            appendLine("The bundle is still at ${bundle.path} if you need it.")
                            append("Commit, or pass -ParchiveOverwrite, to archive this one. See #184.")
                        }
                    )
                    return@doLast
                }
                throw GradleException(
                    buildString {
                        appendLine("Refusing to overwrite release-archive/v$releaseVersionCode.")
                        appendLine()
                        appendLine("  already there: $recorded")
                        appendLine("  this build:    $identity  (branch $branch)")
                        appendLine()
                        appendLine("Both are clean builds, so this is a real choice between two shippable")
                        appendLine("artifacts and guessing is not safe - the archive is what gets uploaded to")
                        appendLine("Play, and a burned versionCode cannot be reclaimed.")
                        appendLine()
                        appendLine("If the archived bundle is finished with, delete the directory and re-run.")
                        appendLine("To overwrite deliberately: -ParchiveOverwrite")
                        append("See #184 and docs/releases.md.")
                    }
                )
            }
        }

        destination.mkdirs()
        bundle.copyTo(destination.resolve(bundle.name), overwrite = true)
        mapping.copyTo(destination.resolve("mapping.txt"), overwrite = true)
        provenance.writeText(
            buildString {
                appendLine("identity: $identity")
                appendLine("commit: $commit")
                appendLine("branch: $branch")
                appendLine("dirty: $dirty")
                appendLine("versionCode: $releaseVersionCode")
                appendLine("versionName: $releaseVersionName")
                // Deliberately NOT part of the identity line: two builds of the same sources are the
                // same artifact whenever they were made, and putting the clock in the comparison
                // would make every re-run a mismatch.
                appendLine("archivedAt: ${ZonedDateTime.now()}")
                appendLine()
                appendLine("dirty=true means the working tree carried uncommitted changes, so the commit")
                appendLine("above does NOT describe what is in this bundle. Do not upload a dirty build")
                appendLine("without recording what was different - see docs/releases.md.")
            }
        )
        logger.lifecycle("Archived ${bundle.name} and mapping.txt to ${destination.path} ($identity)")
    }
}

// AGP creates bundleRelease lazily, so it does not exist while this script is being configured —
// tasks.named("bundleRelease") here fails the build outright with "Task with name 'bundleRelease'
// not found". A live filtered collection matches it whenever AGP gets round to adding it.
tasks.matching { it.name == "bundleRelease" }.configureEach { finalizedBy(archiveReleaseArtifacts) }

dependencies {
    implementation(project(":shared"))
    // The leaf managers the phone app will consume too (#200, epic #196 D1). It re-exports
    // :shared via `api`, so the line above is redundant on the classpath and kept anyway: this
    // module's own code reads :shared types directly, and a dependency that is only reachable
    // through somebody else's `api` breaks the day that somebody stops needing it.
    implementation(project(":shared-android"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    // Wear Compose
    implementation(libs.wear.compose.material)
    implementation(libs.wear.compose.foundation)
    implementation(libs.wear.compose.navigation)

    // Wear OS core
    implementation(libs.wear.core)
    implementation(libs.wear.ongoing)

    // Compose tooling (debug only)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.wear.compose.ui.tooling)

    // #160: the module's first test source set, four months after the module. Robolectric, scoped
    // to the non-audio surfaces by the owner decision on that issue — the residue seven extractions
    // into `:shared` could not reach is orchestration, and an ordering of calls is a property a
    // shadow answers exactly (it is the framework's own bookkeeping, not physics).
    //
    // The boundary is not prose: `AudioHapticBoundaryTest` fails the build if a test under this
    // source set ever asserts on the audio or haptic path. A green run here says nothing about
    // #114, #144 or #126 — those are hardware, and this dependency does not make them otherwise.
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
}
