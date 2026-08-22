package com.racetimer.phone

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.delay
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * The #239 fix: a flush loop composed alongside the app under test, doing the job of a flusher
 * that is dead in every Robolectric test class except the first one in the JVM.
 *
 * Compose it first inside `setContent`, before the app content:
 * ```
 * compose.setContent {
 *     GlobalSnapshotFlushLoop()
 *     RaceTimerApp(...)
 * }
 * ```
 *
 * ## The mechanism, measured (issue #239)
 *
 * Composing the full app writes platform-owned state **outside any composition frame** — the
 * `InputModeManager` mode and the window container size, read by name off the frozen modified-set
 * during a live hang. Writes like these land in the **global** snapshot and are invisible until
 * `Snapshot.sendApplyNotifications()` broadcasts them. In production, and in the first test class
 * a JVM runs, compose's `GlobalSnapshotManager` makes that call — but it is a once-per-classloader
 * singleton whose collector lives on the main looper of whichever test class started it, and
 * Robolectric shares one sandbox classloader across test classes while resetting loopers between
 * them. Every class after the first therefore runs with a flusher that no longer runs.
 *
 * `RobolectricIdlingStrategy` then spins: its busy check reads
 * `Snapshot.current.hasPendingChanges()`, the un-broadcast writes keep that true forever, and
 * pumping frames — all the loop knows how to do — cannot clear it. The wall-clock policy fails the
 * test at 60 s as `AppNotIdleException` after ~3 million attempts. It reads as *nondeterministic*
 * because the JVM's class order varies run to run and the hang needs this class to not be first —
 * measured 6/6 on #239.
 *
 * Sufficiency was proven by intervention: a watchdog thread calling `sendApplyNotifications()`
 * 16 s into a live hang un-hung it on the spot. This loop is that intervention made permanent.
 *
 * ## Why a composed loop, and not an `IdlingResource`
 *
 * The first version of this fix was a `compose.registerIdlingResource` whose `isIdleNow` flushed.
 * *Measured with a poll counter during a captured hang: it was never called* — the Robolectric
 * strategy's busy loop consults its private `ComposeIdlingResource` directly and no registry poll
 * ever reached the registered resource, so the fix was installed, plausible, and dead (the shape
 * cairn's `a-ported-guard-can-be-dead-on-arrival` names). What the spin *does* run, measured, is
 * the test dispatcher: every busy poll pumps a frame through `advanceTimeByFrame`, which advances
 * `delay`-scheduled coroutines. This loop rides that — the spin itself is what drives the flush
 * that ends it, usually within a few pumped frames of the write.
 */
@Composable
internal fun GlobalSnapshotFlushLoop() {
    LaunchedEffect(Unit) {
        while (true) {
            Snapshot.sendApplyNotifications()
            delay(FLUSH_INTERVAL_MS)
        }
    }
}

/**
 * The same #239 remedy for a test class that has no `setContent` to compose [GlobalSnapshotFlushLoop]
 * into — a `createAndroidComposeRule` class, whose content comes from the real activity.
 *
 * ```
 * @get:Rule
 * val rules: RuleChain = RuleChain.outerRule(GlobalSnapshotFlushRule()).around(compose)
 * ```
 *
 * ## Why this exists, and what it says about the first fix
 *
 * #239's fix was applied where the tests compose their own content, which is every class in this
 * module **except** `DisplayChoiceSurfaceTest`: it launches the real `MainActivity` through
 * `createAndroidComposeRule`, so there is no lambda to put a composable in. That class was therefore
 * never covered by the fix and has been relying on being the **first** compose class the JVM runs —
 * the one position in which the `GlobalSnapshotManager`'s collector is still alive.
 *
 * *Measured 2026-08-21, on #281:* adding four unrelated test classes moved it out of that position
 * and all **5 of 5** failed with `AppNotIdleException` after ~3M attempts; run with `--tests` alone,
 * the same 5 pass. Nothing about that class or the app changed. It was one class-order shuffle from
 * failing on any day, and a suite that only passes in one ordering is not passing for a reason.
 *
 * A daemon thread rather than a composable, because the hang can begin inside the rule's own
 * activity launch — before any test body runs and before there is a composition to hold a loop. This
 * is the *intervention* #239 proved sufficient (a watchdog thread calling `sendApplyNotifications()`
 * un-hung a captured hang on the spot), made into a rule rather than a one-off.
 *
 * It must be the **outer** rule: the compose rule launches the activity during its own `before`, so
 * a flusher nested inside it starts too late to help. `RuleChain` states that ordering explicitly
 * rather than leaving it to `@Rule(order = …)`, whose direction is easy to get backwards and whose
 * failure mode here is a hang rather than an error.
 */
internal class GlobalSnapshotFlushRule : TestRule {

    override fun apply(base: Statement, description: Description): Statement =
        object : Statement() {
            override fun evaluate() {
                val stopped = AtomicBoolean(false)
                val flusher = thread(isDaemon = true, name = "global-snapshot-flush") {
                    while (!stopped.get()) {
                        Snapshot.sendApplyNotifications()
                        try {
                            Thread.sleep(FLUSH_INTERVAL_MS)
                        } catch (interrupted: InterruptedException) {
                            Thread.currentThread().interrupt()
                            return@thread
                        }
                    }
                }
                try {
                    base.evaluate()
                } finally {
                    // Daemon, so it could be left running — stopped anyway, because a flusher
                    // outliving its test would be a thread mutating global snapshot state
                    // underneath the *next* class, which is the family of fault this whole file
                    // is about.
                    stopped.set(true)
                    flusher.interrupt()
                }
            }
        }
}

/**
 * Short enough that a burst of global writes is flushed within a frame or two of pumping; the
 * interval is virtual time, so it costs nothing real. Identical in shape to the app's own display
 * poll, which is measured not to keep the idling strategy busy.
 */
private const val FLUSH_INTERVAL_MS = 20L
