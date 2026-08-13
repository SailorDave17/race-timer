package com.racetimer.phone

import android.content.Intent
import android.content.pm.ApplicationInfo
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Can a person get to this app, and does its manifest say what the epic ratified?
 *
 * The one question no unit test of the timer can answer is whether there is a path from a home
 * screen to any of it (cairn `exported-is-not-reachable`). This asks the platform's own resolver,
 * against the **merged** manifest — the artefact, not the ingredient — which is also the only thing
 * that can catch a launcher entry lost to a manifest merge.
 *
 * This is the module's one Robolectric test, and the scope is the point: a real `Context` and a real
 * `PackageManager`, no audio and no haptics. A shadow `AudioTrack` is a fourth instrument of the
 * class that reported success through #61 while the watch was silent, and #160's owner decision is a
 * scoped yes for exactly the surfaces this one covers.
 */
@RunWith(RobolectricTestRunner::class)
class LauncherReachabilityTest {

    @Test
    fun `the launcher resolves to the timer, under the shared Play identity`() {
        val context = RuntimeEnvironment.getApplication()

        // The same identity as the watch, deliberately: one Play listing, two form factors. Play
        // locks this permanently at first upload and the watch has already locked it.
        assertEquals("io.github.sailordave17.racetimer", context.packageName)

        val launch = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setPackage(context.packageName)
        val resolved = context.packageManager.queryIntentActivities(launch, 0)

        assertEquals("launcher entries", 1, resolved.size)
        assertEquals(
            "com.racetimer.phone.MainActivity",
            resolved.single().activityInfo.name,
        )
    }

    @Test
    fun `auto backup is off, as ratified`() {
        // Epic #196 decision D5. The platform default is ON, so this is a value somebody had to set:
        // Auto Backup ships SharedPreferences to the user's Google account with no INTERNET
        // permission involved, which is a claim the published privacy policy would have to carry.
        val app = RuntimeEnvironment.getApplication()

        // Positive control, and not a decoration: `FLAG_ALLOW_BACKUP` is 0 on the default
        // `ApplicationInfo` Robolectric falls back to when it cannot read the merged manifest, so
        // the assertion below passes while reading nothing at all. Found by mutation — turning
        // `isIncludeAndroidResources` off reddened the reachability test and left this one green.
        // Naming the package first is what makes the absence of the manifest a failure rather than
        // a pass.
        assertEquals("io.github.sailordave17.racetimer", app.packageName)

        val flags = app.applicationInfo.flags
        assertEquals(0, flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
    }
}
