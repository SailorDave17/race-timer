package com.racetimer.phone

import android.view.Window
import android.view.WindowManager

/**
 * The phone's two display properties, applied to a window (#199).
 *
 * **Two booleans and nothing else reaches here — that is the criterion, not an accident of the
 * signature.** This is the *mechanism*; who decides what the two booleans are is #225, where the
 * officer is asked once per launch. Nothing about the race — no engine state, no timer state, no
 * clock — is visible from this file, so a later change cannot make the display quietly depend on
 * where the countdown is.
 *
 * **The phone deliberately does not share the watch's display rules, and the divergence is the
 * decision rather than a shortcut.** On the watch both properties are decided *for* the wearer by a
 * table in `shared/` — keyed on the engine state, and since #300 keep-screen-on also on which screen
 * is up — reasoned and hardware-verified (#65, #100 and the sun test that closed it). That is correct
 * for a wrist that is *glanced at*, on a small battery, by one person who can be decided for.
 * A phone propped on a committee-boat console is a different instrument: it is watched
 * continuously by a boat full of people, may or may not have a charger aboard, and has to last a
 * start day (#216). That makes the battery-versus-legibility trade a property of **the day** — this
 * sun, this boat, this much racing left — which is why the officer makes it and this path applies it
 * without re-deciding. The wear app and the shared table are untouched by this story.
 *
 * **Amended by #279 and #284, and the amendments are smaller than they sound.** The full-brightness
 * answer is read as *the panel for the race*: every countdown, and the gun's own moment, still gets
 * exactly what the officer asked for. Where nothing is being timed and nothing ends on its own — the
 * picker, the pre-start screen, the GO! screen once the gun's linger is over — it is released
 * (#284). An unbounded count-up is where "a race is on screen" and "burn the panel" come apart, so
 * the officer is asked once, at the gun, whether to keep it there (#279), and the frozen final time
 * after End Race follows that same answer rather than asking again (#284). **None of that happens
 * here.** The question, the answer and the rule that combines them live in the app
 * (`DisplayChoice.kt` and `MainActivity`), and what crosses into this file is still two booleans
 * with nothing about the race in them — which is what `ModuleBoundaryTest` asserts and why the
 * amendments cost this file no new knowledge. Said in band because this file is cited elsewhere as
 * the account of *why* the two form factors diverge, so a reader arriving on that pointer would
 * otherwise get an older story.
 *
 * *(The shared file is named nowhere in `phone/src/main` on purpose: `ModuleBoundaryTest` reads this
 * module's source text to assert exactly that, and a guard whose subject is source text fires on the
 * prose explaining it — cairn `a-guard-that-reads-source-must-survive-its-own-docs`. Describe it,
 * do not name it.)*
 */

/**
 * Apply [keepScreenOn] and [fullBrightness] to this window, each independently of the other.
 *
 * The two are set by two separate statements over two separate platform mechanisms, and neither is
 * derived from the other. Every combination is legitimate and one is asked for by real conditions:
 * shore power wants both, a dying battery under overcast wants neither, and direct sun with a free
 * thumb wants brightness alone.
 *
 * Brightness is a **window** override (`WindowManager.LayoutParams.screenBrightness`), never a write
 * to `Settings.System.SCREEN_BRIGHTNESS`. That choice is structural rather than stylistic: the
 * system setting needs `WRITE_SETTINGS`, changes the whole device, and leaves the phone pinned bright
 * if this process dies mid-race, so "put it back afterwards" becomes a promise the app cannot keep.
 * A window override has nothing to put back — it applies only while this window is the visible one
 * and evaporates with it, so the officer's own brightness (or the ambient sensor's) is untouched
 * throughout and is simply back in charge the moment `BRIGHTNESS_OVERRIDE_NONE` lands here. See
 * cairn `android-window-brightness-override`, which also records why the applied `layoutParams`
 * value is the thing to assert: the obvious success indicator reports 100% of a range that stops
 * well short of the panel's maximum.
 *
 * Assigning [Window.setAttributes] is what applies the change; mutating the object returned by
 * [Window.getAttributes] alone does nothing. Calling this repeatedly with the same values is
 * harmless, and it is meant to be called when the choice changes — not from the display poll.
 */
fun Window.applyDisplayProperties(keepScreenOn: Boolean, fullBrightness: Boolean) {
    if (keepScreenOn) {
        addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    } else {
        clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    attributes = attributes.apply {
        screenBrightness = if (fullBrightness) {
            WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
        } else {
            WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
    }
}
