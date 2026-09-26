// Top-level build file — configuration shared across all sub-projects.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
}

// #211. One monotonic versionCode counter across both form factors (epic #196 decision D3).
//
// :wear and :phone ship under the SAME applicationId, so Play treats them as one app and refuses a
// second artifact reusing a versionCode that is already burned. wear=1 was burned at the 2026-08-13
// upload. The scheme is an allocation discipline, and this task is what stops it from being ONLY a
// discipline.
//
// A guard rather than a derivation, on purpose: which module takes the next number depends on which
// one ships next, and a build cannot make that call. What it can do is refuse a state Play would
// reject anyway - here, in seconds, rather than after a signed upload.
val checkVersionCodeCollision = tasks.register("checkVersionCodeCollision") {
    group = "verification"
    description = "Refuses two app modules declaring the same versionCode under one applicationId."

    // Captured at configuration time so the task does not reach across projects while it runs.
    val declared = subprojects
        .filter { it.plugins.hasPlugin("com.android.application") }
        .associate { sub ->
            val ext = sub.extensions
                .findByType(com.android.build.api.dsl.ApplicationExtension::class.java)
            sub.path to Pair(ext?.defaultConfig?.versionCode, ext?.defaultConfig?.applicationId)
        }

    doLast {
        // The identity is the (applicationId, versionCode) PAIR, not the number alone: two modules
        // under different applicationIds are separate Play apps and may legitimately share a number.
        val collisions = declared.entries
            .filter { it.value.first != null && it.value.second != null }
            .groupBy { it.value }
            .filter { it.value.size > 1 }

        if (collisions.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("Two app modules declare the same versionCode under one applicationId.")
                    appendLine()
                    collisions.forEach { (identity, entries) ->
                        appendLine("  versionCode ${identity.first} on ${identity.second}:")
                        entries.forEach { appendLine("    ${it.key}") }
                    }
                    appendLine()
                    appendLine("Play refuses this: one applicationId is one app, and a versionCode is")
                    appendLine("permanently unique within it. Epic #196 decision D3 gives both form")
                    appendLine("factors ONE monotonic counter - allocate the next free number to the")
                    append("module that ships next, and record it in docs/releases.md.")
                }
            )
        }
        logger.lifecycle(
            "versionCodes distinct per applicationId (" +
                declared.entries.joinToString(", ") { "${it.key}=${it.value.first}" } + ")"
        )
    }
}

// Bound to the release path: a collision costs nothing until something uploadable exists, and this
// is the last point before one does.
subprojects {
    tasks.matching { it.name == "bundleRelease" }.configureEach {
        dependsOn(checkVersionCodeCollision)
    }
}
