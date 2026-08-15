package com.racetimer.wear

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Looper
import com.racetimer.shared.BuiltInSequences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.w3c.dom.Element
import java.io.File
import java.time.Duration
import java.util.Properties
import javax.xml.parsers.DocumentBuilderFactory

/**
 * What the countdown puts on the watch face, and what the manifest promises Play about it.
 *
 * The notification is orchestration in the same sense the ordering is: the channel is created by one
 * component and posted on by another, the small icon has to be the alpha-only mark rather than the
 * launcher, and the foreground-service type is a declaration a human reviewer reads. None of it is
 * extractable into `shared/` and none of it is the audio path.
 *
 * ### The manifest half reads the merged manifest, not the committed one
 *
 * `wear/src/main/AndroidManifest.xml` is an ingredient. What ships -- and what Play's App content
 * declarations are checked against -- is the merged manifest, which picks up whatever the libraries
 * on the classpath contribute (cairn `verify-the-artefact-not-its-ingredients`: the source manifest
 * declared five permissions and the merged one carried six). AGP writes its location into
 * `com/android/tools/test_config.properties`, which is the same contract Robolectric itself reads to
 * find it, so this asks the artefact rather than the recipe.
 *
 * `PackageManager.getProperty` would be the tidier way to reach the special-use subtype and is
 * **unsupported under Robolectric** (`NameNotFoundException: unsupported`, measured 4.14.1 / SDK 35),
 * hence the XML. The foreground-service *type* does come back through `PackageManager`, so it is
 * asserted there -- two readings of the same manifest through two different mechanisms.
 */
@RunWith(RobolectricTestRunner::class)
class ForegroundNotificationTest {

    private fun armedService(): TimerService {
        val svc = Robolectric.buildService(TimerService::class.java).create().get()
        svc.onStartCommand(TimerService.startIntent(svc, BuiltInSequences.usSailing.id), 0, 1)
        return svc
    }

    @Test
    fun `the channel exists before the service ever runs`() {
        // Created by RaceTimerApplication.onCreate -- the Application the manifest names -- not by the
        // service. Read back from the manager, so a channel quietly moved into the service (where the
        // first post could race its creation) fails here.
        val nm = RuntimeEnvironment.getApplication()
            .getSystemService(NotificationManager::class.java)
        val channel = nm.getNotificationChannel(RaceTimerApplication.TIMER_CHANNEL_ID)
        assertNotNull("channel missing at Application init", channel)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
    }

    @Test
    fun `the countdown enters the foreground on that channel, under the alpha-only mark`() {
        val svc = armedService()
        val notification = shadowOf(svc).lastForegroundNotification
        assertNotNull("the service never entered the foreground", notification)

        assertEquals(RaceTimerApplication.TIMER_CHANNEL_ID, notification.channelId)
        // A notification small icon is rendered from its alpha channel alone and tinted, so the
        // adaptive launcher's foreground layer would arrive as a featureless disc (cairn
        // `android-notification-small-icon-alpha`). `ic_stat_race_timer` is the mark drawn for it.
        assertEquals(R.drawable.ic_stat_race_timer, shadowOf(notification.smallIcon).resId)
        assertEquals(
            RaceTimerApplication.TIMER_NOTIFICATION_ID,
            shadowOf(svc).lastForegroundNotificationId,
        )
    }

    @Test
    fun `the notification carries the countdown`() {
        val svc = armedService()
        val app = RuntimeEnvironment.getApplication()
        val posted = shadowOf(svc).lastForegroundNotification

        // M:SS, the format `formatCountdown` produces -- asserted as a shape rather than a value,
        // because the exact digit depends on where in its first second the test caught the race.
        val text = posted.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        assertTrue("countdown text was '$text'", Regex("""^\d+:\d\d$""").matches(text))
        assertEquals(
            app.getString(R.string.notification_content_title),
            posted.extras.getCharSequence(Notification.EXTRA_TITLE).toString(),
        )
    }

    @Test
    fun `the notification follows the countdown down`() {
        // Deliberately a second test, and it has to read a different object.
        // `ShadowService.lastForegroundNotification` is bound only by `startForeground`, while
        // `updateOngoingNotification` posts through `NotificationManager.notify` -- so the field the
        // test above reads is structurally blind to every refresh, whatever the looper is doing.
        // The two halves used to be one test whose name claimed both and whose assertions covered
        // the first.
        val svc = armedService()
        val app = RuntimeEnvironment.getApplication()
        val nm = app.getSystemService(NotificationManager::class.java)
        val started = postedCountdown(nm)

        // The tick loop runs at 50 ms and re-posts only when the rendered M:SS actually changes, so
        // one second is the smallest advance guaranteed to produce a new value. Robolectric's looper
        // is paused by default; idling it is what runs the posted `tickRunnable` and moves the clock.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_500))

        val later = postedCountdown(nm)
        assertNotNull("nothing was ever posted through the notification manager", later)
        assertNotEquals(
            "the notification still reads $started after a second and a half of countdown",
            started,
            later,
        )
        assertTrue("countdown text was '$later'", Regex("""^\d+:\d\d$""").matches(later!!))
    }

    private fun postedCountdown(nm: NotificationManager): String? =
        shadowOf(nm).getNotification(RaceTimerApplication.TIMER_NOTIFICATION_ID)
            ?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()

    @Test
    fun `the merged manifest declares the service specialUse`() {
        val app = RuntimeEnvironment.getApplication()
        val info = app.packageManager.getServiceInfo(
            ComponentName(app, TimerService::class.java),
            PackageManager.GET_META_DATA,
        )
        // Not one of the enumerated FGS types -- a race-start countdown is none of them -- so the
        // declaration is `specialUse` plus a subtype a reviewer reads. The default when a manifest is
        // not read at all is 0, so this cannot pass vacuously.
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            info.foregroundServiceType,
        )
    }

    @Test
    fun `the merged manifest carries the special-use subtype a Play reviewer reads`() {
        val serviceElement = mergedManifestServiceElement(".TimerService")
        assertEquals(
            "specialUse",
            serviceElement.getAttributeNS(ANDROID_NS, "foregroundServiceType"),
        )

        val properties = serviceElement.getElementsByTagName("property")
        val subtype = (0 until properties.length)
            .map { properties.item(it) as Element }
            .firstOrNull {
                it.getAttributeNS(ANDROID_NS, "name") == SPECIAL_USE_SUBTYPE_PROPERTY
            }
        assertNotNull(
            "no $SPECIAL_USE_SUBTYPE_PROPERTY on the service. Play rejects a specialUse " +
                "foreground service that does not say what the special use is (#74).",
            subtype,
        )
        assertTrue(
            "the subtype is declared but empty",
            subtype!!.getAttributeNS(ANDROID_NS, "value").isNotBlank(),
        )
    }

    private fun mergedManifestServiceElement(nameSuffix: String): Element {
        // `javaClass` is read here rather than inside `Properties().apply { }`, where it resolves
        // against the Properties receiver -- a bootstrap-loaded class whose loader is null.
        val loader = javaClass.classLoader ?: error("no classloader to read the AGP test config from")
        val config = Properties().apply {
            val stream = loader.getResourceAsStream("com/android/tools/test_config.properties")
                ?: error("AGP wrote no test_config.properties; there is no merged manifest to read")
            stream.use { load(it) }
        }
        val manifest = File(config.getProperty("android_merged_manifest"))
        assertTrue("no merged manifest at ${manifest.absolutePath}", manifest.isFile)

        val document = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(manifest)
        val services = document.getElementsByTagName("service")
        // A scan that found nothing would pass every assertion below by never running one.
        assertTrue("no <service> in the merged manifest", services.length > 0)
        return (0 until services.length)
            .map { services.item(it) as Element }
            .firstOrNull { it.getAttributeNS(ANDROID_NS, "name").endsWith(nameSuffix) }
            ?: error("no service matching $nameSuffix in ${manifest.absolutePath}")
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
        const val SPECIAL_USE_SUBTYPE_PROPERTY = "android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
    }
}
