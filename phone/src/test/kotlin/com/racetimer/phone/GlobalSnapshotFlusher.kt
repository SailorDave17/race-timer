package com.racetimer.phone

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.delay

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
 * Short enough that a burst of global writes is flushed within a frame or two of pumping; the
 * interval is virtual time, so it costs nothing real. Identical in shape to the app's own display
 * poll, which is measured not to keep the idling strategy busy.
 */
private const val FLUSH_INTERVAL_MS = 20L
