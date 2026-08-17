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
 * decision rather than a shortcut.** On the watch both properties are pure functions of the engine
 * state, living in `shared/`, reasoned and hardware-verified (#65, #100 and the sun test that closed
 * it) — correct for a wrist that is *glanced at*, on a small battery, by one person who can be
 * decided for. A phone propped on a committee-boat console is a different instrument: it is watched
 * continuously by a boat full of people, may or may not have a charger aboard, and has to last a
 * start day (#216). That makes the battery-versus-legibility trade a property of **the day** — this
 * sun, this boat, this much racing left — which is why the officer makes it and this path applies it
 * without re-deciding. The wear app and the shared table are untouched by this story.
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
