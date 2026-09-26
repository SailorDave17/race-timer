package com.racetimer.shared

/**
 * What the display should be doing for a given [TimerState].
 *
 * Both rules here are pure functions, and they live together because they are *nearly* the same rule
 * and must not be read as accidentally different. [forcesMaxBrightness] reads the engine state alone.
 * [keepsScreenOn] also reads which screen is up (#300), because the pre-start screen and the sequence
 * picker are both [TimerState.IDLE] and only one of them is waited on. They disagree in two places,
 * each deliberate and each spelled out below: [TimerState.FINISHED], the whole substance of #65, is
 * driven bright without being held awake, and the pre-start screen is held awake without being
 * driven bright.
 *
 * Kept in `shared/` rather than inline in `MainActivity` so they can be asserted by the JVM suite:
 * the display policy is a table, and a table is exactly the kind of thing that drifts when it is
 * written out at its call site (see `resumeOfferRemainingMs`, whose rule was previously duplicated
 * *inverted* across two methods of that same activity).
 *
 * Both use an exhaustive `when` with no `else`, so a new [TimerState] fails the build here until
 * someone decides what the screen should do for it.
 */

/**
 * Should the screen be held awake — `FLAG_KEEP_SCREEN_ON`?
 *
 * [TimerState.RACE_ENDED] is included alongside [TimerState.RUNNING]: the whole point of that state is
 * to hold the final race time up for the race committee to read, and letting the screen sleep the
 * instant they tap End Race would defeat it. Unlike RUNNING this has no wake lock behind it (see
 * `TimerService.onGun` — released at the gun, on purpose), so it only holds the screen while the
 * activity itself is in the foreground; backgrounding still lets it sleep, same as it always could
 * between taps.
 *
 * [TimerState.COUNTING_UP] is deliberately absent — a race-committee count-up is allowed to sleep
 * (#59), because it has no bound and the countdown to the gun is over.
 *
 * [TimerState.IDLE] is held **on the timer screen only** — [onTimerScreen] true — because in `IDLE`
 * that screen is the pre-start screen: the sequence is loaded, and the sailor is waiting on it for the
 * warning signal (#300). Letting it sleep there meant waking the watch before pressing Start, and
 * sometimes finding it back on the watch face, at the one moment the tap has to be on time. Three
 * decisions ride on this line (owner, 2026-09-25):
 *
 * - **No timeout.** The hold lasts as long as the pre-start screen shows. A postponement can hold a
 *   fleet well past any sensible bound, and a bound would bring the sleep back in exactly that case.
 *   `FLAG_KEEP_SCREEN_ON` is window state, so leaving the app releases it.
 * - **Not the picker.** The sequence picker is `IDLE` too, but nobody waits on it for a signal. It
 *   sleeps as it always did, and so does every other screen stacked over the timer screen — Custom's
 *   stepper and both lead-in screens.
 * - **Not bright.** [forcesMaxBrightness] still answers false for `IDLE`. This keeps the panel awake
 *   at the system's brightness; driving it bright for the length of a postponement is the unbounded
 *   panel cost #59 and #284 exist to avoid.
 *
 * [TimerState.PAUSED] also shows Start on the watch's timer screen, and is **not** held — decided, not
 * forgotten. Nothing calls `TimerEngine.pause()`, so the UI cannot reach the state, and #300 left it
 * unbuilt on purpose. A story that wires a pause control decides whether a paused countdown is a
 * screen someone waits on, and this is the line it changes.
 *
 * [TimerState.RUNNING] and [TimerState.RACE_ENDED] ignore [onTimerScreen]. The sequence name opens the
 * picker only when neither is showing, so the two never meet on the watch today — and a race on
 * screen stays held whichever screen a later change puts it behind.
 */
fun keepsScreenOn(state: TimerState, onTimerScreen: Boolean): Boolean = when (state) {
    TimerState.RUNNING,
    TimerState.RACE_ENDED -> true

    TimerState.IDLE -> onTimerScreen

    TimerState.PAUSED,
    TimerState.FINISHED,
    TimerState.COUNTING_UP -> false
}

/**
 * Should the panel be driven to maximum brightness — `BRIGHTNESS_OVERRIDE_FULL` on the window (#65)?
 *
 * Sun legibility, not aesthetics. Running the app on the water in direct sunlight showed the default
 * ambient brightness is not reliably readable at a wrist-glance, which is precisely when the timer
 * matters. #12 verified the *colours* are readable; this drives the panel hard enough to give those
 * colours a fighting chance.
 *
 * Covers every state the countdown's colour arc can be on screen for — navy, amber, red-flash, green:
 *
 * - [TimerState.RUNNING] is navy → amber → red-flash, the countdown itself.
 * - [TimerState.FINISHED] is the green gun screen. **This is the one state [keepsScreenOn] excludes
 *   and this does not**, and the difference is intentional: FINISHED is transient (the service returns
 *   the engine to IDLE once the gun cue and its "GO!" linger are done), so it needs no help staying
 *   awake — the screen is already on, coming straight out of RUNNING. But it is the single most
 *   important instant in the sequence, and dropping to system brightness *at the gun* would be the
 *   worst possible moment to dim.
 * - [TimerState.RACE_ENDED] is the frozen green final time. It already forces the screen awake, and it
 *   is read off the wrist in the same sunlight, so dimming it there would look like a defect.
 *
 * Excluded, and why:
 *
 * - [TimerState.IDLE] / [TimerState.PAUSED] — nothing is being timed. AC 3: normal power-saving
 *   behaviour resumes as soon as the sequence ends, so the override cannot outlive it.
 * - [TimerState.COUNTING_UP] — the one state where "a race is on screen" and "burn the panel" come
 *   apart. A committee count-up is unbounded (an hour is ordinary) and is already allowed to sleep;
 *   forcing an OLED to full brightness for that long is the battery cost AC 3 exists to prevent.
 */
fun forcesMaxBrightness(state: TimerState): Boolean = when (state) {
    TimerState.RUNNING,
    TimerState.FINISHED,
    TimerState.RACE_ENDED -> true

    TimerState.IDLE,
    TimerState.PAUSED,
    TimerState.COUNTING_UP -> false
}

// ---------------------------------------------------------------------------
// The ambient half of the same decision (#12)
// ---------------------------------------------------------------------------

/**
 * Illuminance at or above which the brightness override is **released**, in lux.
 *
 * The counter-intuitive half of #65, and the reason this file gained a second input. A window
 * brightness override does not merely outvote the automatic strategy — it switches it off
 * (`lux=-1.0`, `rcmdBrt=NaN`, `hbmMode=off` in `dumpsys display`) and pins the panel at the top of
 * its *normal* range. On the SM-R925U that is **600 nits**, while the automatic strategy driven from
 * the light sensor reaches the high-brightness range and was measured at **1000 nits at 7033 lux**.
 * The crossover is around 3000 lux.
 *
 * Direct sunlight is 10,000–100,000 lux. So above the crossover, forcing "maximum" brightness makes
 * the screen **dimmer than doing nothing**, in precisely the conditions the override was written
 * for — and every instrument reports success while it happens, because `brt=1.0 (100.0%)` is 100 %
 * of a range that stops at 60 % of the hardware.
 *
 * No app API can request the high-brightness range: `screenBrightness` is documented `0..1` and
 * `BRIGHTNESS_OVERRIDE_FULL` is `1.0f`. An app cannot ask for sunlight mode. It can only stop
 * suppressing it, which is what releasing the override does.
 *
 * Below the crossover the override remains a large win — up to **8.6×** at indoor levels (49 nits
 * automatic against 600 forced at 11 lux) — so this releases rather than abandons it.
 */
const val OVERRIDE_RELEASE_LUX = 3_000f

/**
 * Illuminance at or below which the override is **re-engaged**, in lux.
 *
 * Deliberately below [OVERRIDE_RELEASE_LUX] rather than equal to it. A single threshold oscillates
 * for any sailor standing near it, and each flip is a visible brightness step on a screen someone
 * is trying to read a clock off. The band between the two is held by whichever way the gate last
 * went, which is what [ambientPermitsOverride] takes its `currentlyPermitted` argument for.
 */
const val OVERRIDE_ENGAGE_LUX = 2_000f

/**
 * Does the ambient light permit forcing the panel, given the last answer?
 *
 * Split from [forcesMaxBrightness] rather than folded into it because the two gates answer
 * different questions and fail differently. The state gate is a fact about the race; this is a fact
 * about the weather, and only this one has hysteresis. The applied value is the conjunction — see
 * `MainActivity.applyDisplayPolicy`.
 *
 * A null [lux] means no reading yet, or no light sensor on this device. That answers **true**: the
 * pre-#12 behaviour, which is right at every illuminance below the crossover and no worse than
 * shipped above it. A missing sensor must not cost the indoor 8.6×.
 */
fun ambientPermitsOverride(lux: Float?, currentlyPermitted: Boolean): Boolean = when {
    lux == null -> true
    lux >= OVERRIDE_RELEASE_LUX -> false
    lux <= OVERRIDE_ENGAGE_LUX -> true
    else -> currentlyPermitted
}
