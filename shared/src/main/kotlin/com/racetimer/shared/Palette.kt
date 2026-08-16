package com.racetimer.shared

// ---------------------------------------------------------------------------
// The app's chrome palette — one definition, read by both form factors (#198)
//
// The four countdown *state* backgrounds are not here. They live in `MessageContrast.kt`, moved
// there by #123 so the contrast guard could measure the values the screen actually renders, and
// this file deliberately does not restate them — `BG_NORMAL_ARGB` and its three siblings are
// imported from there by anything that needs them, including [ON_ACCENT_ARGB] below.
//
// What is here is the rest: the accent, surface and button colours the watch carried as inline
// `Color(0xFF…)` literals across `Theme.kt` and five screens, where the phone module could not
// read them and a second copy was the only way to match them.
//
// Same form as `MessageContrast` for the same reason. `:shared` is a pure-JVM library both app
// modules depend on, so a Compose `Color` would not compile here; an ARGB `Long` does, and each
// module wraps it at the point of use. That is what makes one definition possible at all.
//
// The values are the watch's, unchanged. Epic #196 records the owner directive — *the watch's
// colors, same colors for now* — so #198 is a relocation and nothing else, and `PaletteTest` pins
// every constant below to its exact pre-move value so that stays checkable rather than asserted.
// ---------------------------------------------------------------------------

// --- Accents ----------------------------------------------------------------

/** Gold. Every primary action: Start, Sync, the confirm button on each picker. */
const val PRIMARY_ARGB = 0xFFFFD700L

/** Dark gold. `Colors.primaryVariant`; nothing draws with it directly today. */
const val PRIMARY_VARIANT_ARGB = 0xFFB8860BL

/** Light blue. Secondary actions — the one that is not the thing you came to press. */
const val SECONDARY_ARGB = 0xFF64B5F6L

/** `Colors.secondaryVariant`; like the gold variant, no direct drawer today. */
const val SECONDARY_VARIANT_ARGB = 0xFF1565C0L

/** `Colors.error`. */
const val ERROR_ARGB = 0xFFCF6679L

/**
 * Ink on an accent — the label inside a gold or blue button, and `Colors.onPrimary` /
 * `Colors.onSecondary`.
 *
 * Defined **from** [BG_NORMAL_ARGB] rather than repeated as a literal: they are the same navy, and
 * writing the value twice is the way two names for one colour quietly become two colours. A second
 * *name* costs nothing and cannot drift; a second *value* is the defect this story exists to remove.
 * The two roles are separate because they answer different questions — one is the calm background a
 * race starts on, the other is what stays legible on gold in sunlight — and a future retune could
 * reasonably move one without the other, at which point this line is where that decision is made.
 */
const val ON_ACCENT_ARGB = BG_NORMAL_ARGB

// --- Surfaces and buttons ---------------------------------------------------

/** `Colors.surface`. */
const val SURFACE_ARGB = 0xFF2A2A40L

/**
 * The unselected row in a picker list.
 *
 * Two hex digits off [SURFACE_ARGB] and not the same value — carried over exactly as the watch had
 * it. Collapsing the two would be a retune, which this story is not (epic #196), so the difference
 * is preserved and named here instead of being silently reconciled.
 */
const val LIST_ROW_ARGB = 0xFF2A2A50L

/** Slate grey. Cancel, Back, End Race — present but never the action being offered. */
const val NEUTRAL_BUTTON_ARGB = 0xFF555577L

/** A disabled button's fill: desaturated blue, so "not yet" reads as off rather than as absent. */
const val DISABLED_BUTTON_ARGB = 0xFF3A4A5EL
