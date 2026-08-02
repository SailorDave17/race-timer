package com.racetimer.shared

/**
 * What the display should be doing for a given [TimerState].
 *
 * Both rules here are pure functions of the engine state, and they live together because they are
 * *nearly* the same rule and must not be read as accidentally different. The one state they disagree
 * on — [TimerState.FINISHED] — is the whole substance of #65, and is spelled out below.
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
 */
fun keepsScreenOn(state: TimerState): Boolean = when (state) {
    TimerState.RUNNING,
    TimerState.RACE_ENDED -> true

    TimerState.IDLE,
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
