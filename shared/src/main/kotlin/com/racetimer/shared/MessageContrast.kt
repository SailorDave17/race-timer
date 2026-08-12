package com.racetimer.shared

// ---------------------------------------------------------------------------
// Whether a message on the timer face can actually be read (pure, testable)
//
// `docs/message-surface.md` has carried a contrast table since #22, and every number in it was
// worked out by hand and typed in. Two of them were wrong by the time #123 recomputed them: the
// Tier 1 figure had been calculated with Tier 3's text colour, and the Tier 3 remedy still named
// the 80 % scrim that #102 had already replaced with an opaque one. Neither error was visible to
// anything — a wrong ratio in prose reads exactly like a right one.
//
// So the ratios live here instead, computed from the same constants the screen renders, and
// `MessageContrastTest` asserts them. That is the same move `BannerLayout` made for the banner's
// geometry and `ScreenPolicy` for the display rules, and for the same stated reason: a constant
// nobody can assert is a constant that drifts back.
//
// The colours are here rather than in `wear/` deliberately. A guard that measured its own private
// copy of `#FFC107` would pass forever while the screen drew something else — it has to read the
// value the UI actually uses, so this file is the single definition and `TimerScreen` imports it.
// ---------------------------------------------------------------------------

// --- Background states ------------------------------------------------------
// The timer face animates through these four. A message can be on screen during any state its own
// display condition allows, which is not the same as all four — see MessageContrastTest.

/** Deep navy — idle, or running above 1:00. */
const val BG_NORMAL_ARGB = 0xFF1A1A2EL

/** Amber — running, inside 1:00. The state that makes amber text a problem. */
const val BG_ONE_MINUTE_ARGB = 0xFFA0660AL

/** Dark red — running, final 10 s. */
const val BG_FINAL_TEN_ARGB = 0xFF7B0000L

/** Dark green — the gun has fired. */
const val BG_FINISHED_ARGB = 0xFF005000L

/**
 * Pick the background colour for the given [remainingMs] and [state].
 *
 * Moved out of `TimerScreen` by #123 so the contrast guard can reach it. It is the *only* thing
 * that decides which background a message is drawn on, so a surface's reachable background set is
 * derived by driving this function rather than by restating its branches — which is what keeps the
 * guard honest when a threshold moves.
 */
fun backgroundArgbFor(remainingMs: Long, state: TimerState): Long = when {
    // Both read as "this run is complete" — RACE_ENDED is a race committee's equivalent of the
    // gun having fired, just held open for review instead of lingering for a few seconds.
    state == TimerState.FINISHED || state == TimerState.RACE_ENDED -> BG_FINISHED_ARGB
    state != TimerState.RUNNING  -> BG_NORMAL_ARGB
    remainingMs <= 10_000L       -> BG_FINAL_TEN_ARGB
    remainingMs <= 60_000L       -> BG_ONE_MINUTE_ARGB
    else                         -> BG_NORMAL_ARGB
}

// --- Message surfaces -------------------------------------------------------

/** Tier 1 transient banner text (`MessageBanner`). */
const val TIER1_TEXT_ARGB = 0xFFFFB74DL

/**
 * Tier 1 scrim. Opaque since #102: at 80 % the background still contributed a fifth of the
 * composite and the amber state was the worst case at 6.55 : 1, so contributing nothing collapses
 * four cases to one.
 */
const val TIER1_SCRIM_ARGB = 0xFF3A2A00L

/** Tier 3 persistent status-line text — the re-sync prompt and the discard warning. */
const val TIER3_TEXT_ARGB = 0xFFFFC107L

/**
 * Tier 3 scrim for the re-sync prompt, added by #123.
 *
 * The same opaque scrim as Tier 1, not the 80 % one `docs/message-surface.md` proposed while the
 * defect was open — that figure predates #102 and naming it as the remedy would have reinstated
 * exactly the alpha #102 removed. Both pass; matching Tier 1 means the tier has one contrast case
 * instead of four, which is the property #102 was actually buying.
 */
const val TIER3_SCRIM_ARGB = 0xFF3A2A00L

/**
 * Tier 2 blocking-notice text (#13). The same amber as Tier 1, and deliberately not red.
 *
 * Red is spoken for twice over — it is the final-ten background and it is this panel's own border —
 * so red text here would be red-on-red at the exact moment it had to be read. The border carries
 * "this is blocking"; the text only has to be legible.
 */
const val TIER2_TEXT_ARGB = 0xFFFFB74DL

/**
 * Tier 2 scrim: 90 % black, and the only **translucent** scrim left in the app (#13).
 *
 * Tiers 1 and 3 went opaque in #102 so their contrast collapsed to a single case. This one keeps
 * 10 % of the background on purpose: the blocking panel covers the Start button on a screen whose
 * background is the sailor's only cue to how much time is left, and a fully opaque plate reads as a
 * separate surface floating over a dead screen. The cost is four contrast cases instead of one,
 * which is affordable because they span 11.38–11.95 : 1 — the whole range clears the bar with room
 * that Tier 3 bare-on-amber never had. `compositeOver` is what makes that claim checkable, and
 * `MessageContrastTest` checks it on every background rather than on the one that looks worst.
 */
const val TIER2_SCRIM_ARGB = 0xE6000000L

/**
 * Tier 2 border, 1 dp. A UI-component boundary rather than text, so its bar is WCAG's non-text
 * 3 : 1 ([WCAG_NON_TEXT_MIN]) and not the 4.5 : 1 the copy has to clear. It lands at 3.96 : 1 on
 * the worst background, which passes the bar it is actually held to and fails the other — worth
 * stating, because reading the wrong bar off this file would look like a defect.
 */
const val TIER2_BORDER_ARGB = 0xFFD32F2FL

/** WCAG 2.1 AA minimum for normal-size text. Both tiers are 11 sp / `caption2`, so this is the bar. */
const val WCAG_NORMAL_TEXT_MIN = 4.5

/**
 * WCAG 2.1 AA minimum for non-text elements — borders, icons, anything whose job is to be *seen*
 * rather than read (success criterion 1.4.11). The Tier 2 border is the only thing held to it.
 */
const val WCAG_NON_TEXT_MIN = 3.0

// --- The arithmetic ---------------------------------------------------------

private fun channel(argb: Long, shift: Int): Int = ((argb shr shift) and 0xFF).toInt()

private fun linearize(component: Int): Double {
    val c = component / 255.0
    return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
}

/** WCAG 2.1 relative luminance of an opaque [argb] colour. Alpha is ignored — composite first. */
fun relativeLuminance(argb: Long): Double =
    0.2126 * linearize(channel(argb, 16)) +
        0.7152 * linearize(channel(argb, 8)) +
        0.0722 * linearize(channel(argb, 0))

/** WCAG 2.1 contrast ratio between two opaque colours. Symmetric, and always ≥ 1. */
fun contrastRatio(a: Long, b: Long): Double {
    val la = relativeLuminance(a)
    val lb = relativeLuminance(b)
    val lighter = if (la > lb) la else lb
    val darker = if (la > lb) lb else la
    return (lighter + 0.05) / (darker + 0.05)
}

/**
 * Composite [fgArgb] over [bgArgb] using the foreground's own alpha, returning an opaque colour.
 *
 * Load-bearing since #13. It was kept through #123 on the argument that it makes an alpha change
 * *measurable* rather than merely visible — the one time this repo shipped a translucent scrim it
 * cost the amber state 2 : 1 of contrast without anything reporting it. Tier 2's 90 % scrim is now
 * an actual caller rather than a hypothetical one, so this is the only reason its four backgrounds
 * can be told apart at all.
 */
fun compositeOver(fgArgb: Long, bgArgb: Long): Long {
    val alpha = channel(fgArgb, 24) / 255.0
    var out = 0xFF000000L
    for (shift in intArrayOf(16, 8, 0)) {
        val blended = Math.round(channel(fgArgb, shift) * alpha + channel(bgArgb, shift) * (1 - alpha))
        out = out or (blended shl shift)
    }
    return out
}

/**
 * The contrast a sailor actually gets: [textArgb] over [scrimArgb] (or straight onto the
 * background when the surface has no scrim) against [backgroundArgb].
 *
 * A null [scrimArgb] is the "scrim or nothing" case from `docs/message-surface.md` rule 1 — safe
 * only where every reachable background has been checked, which is what the test does.
 */
fun effectiveContrast(textArgb: Long, scrimArgb: Long?, backgroundArgb: Long): Double {
    val behindText = if (scrimArgb == null) backgroundArgb else compositeOver(scrimArgb, backgroundArgb)
    return contrastRatio(textArgb, behindText)
}
