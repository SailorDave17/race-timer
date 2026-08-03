# Message Surface — Warnings and Errors on the Watch

Where a warning or an error appears on a one-screen countdown, what it looks like, and what it does to
the Start button. Written so [#13](https://github.com/SailorDave17/race-timer/issues/13) has a concrete
target to build against instead of inventing a surface per error.

Closes the design half of [#22](https://github.com/SailorDave17/race-timer/issues/22).

**Status:** Tier 1 is shipped and running on hardware. Tier 2 is a design only — nothing in `wear/`
implements it yet. Contrast figures below are computed from the source colour constants, not measured
on the watch under sun; the sun audit is [#12](https://github.com/SailorDave17/race-timer/issues/12).

## The constraint

The timer face is one full-bleed countdown, and the MM:SS readout is the whole product. Every rule here
follows from two facts:

1. **The readout must never be covered.** A sailor glancing down mid-sequence needs the number, not the
   message. Messages live above or below the readout, never over it.
2. **The background is not a constant.** It animates through four states, and a message can be on screen
   during any of them:

   | State | Colour | When |
   |---|---|---|
   | Normal | `#1A1A2E` deep navy | idle, or running above 1:00 |
   | One minute | `#A0660A` amber | running, ≤ 60 s |
   | Final ten | `#7B0000` dark red | running, ≤ 10 s |
   | Finished | `#005000` dark green | gun fired |

   Source: `backgroundColorFor` in `wear/src/main/kotlin/com/racetimer/wear/ui/TimerScreen.kt`.

   Amber-on-amber is the case that bites. Any message that relies on the background being navy will
   disappear at exactly the moment the race gets tense.

## Three tiers

Two of these already exist. Naming them keeps #13 from adding a fourth.

| Tier | Surface | Blocks Start? | Lifetime | Status |
|---|---|---|---|---|
| 1 | Transient banner, top-centre | No | Auto-clears after 3 s | **Shipped** (`d85684d`) |
| 2 | Blocking notice, in place of Start | **Yes** | Until the condition is resolved | **Design only — #13 builds it** |
| 3 | Persistent status line under the sequence name | No | Until the user acts | **Shipped**, one consumer |

The split is the answer to #22's "blocking vs. transient vs. persistent": it is not one of the three,
it is all three, chosen by *when* the problem occurs and *whether the sailor can do anything about it*.

**The rule:** if the problem happens **before Start** and makes the sequence unreliable, it blocks
(Tier 2). If it happens **mid-sequence**, it can never block — the countdown is running and a modal over
it is worse than the problem. It informs and clears (Tier 1), or informs and persists if it needs an
action (Tier 3).

---

## Tier 1 — Transient banner (shipped)

`MessageBanner` in [TimerScreen.kt:532](../wear/src/main/kotlin/com/racetimer/wear/ui/TimerScreen.kt#L532),
driven by the `message: String?` parameter and cleared through `onMessageExpired`.

| | |
|---|---|
| Position | `Alignment.TopCenter` of the root `Box`, offset down by `BANNER_TOP_FRACTION` of screen height — which puts it **below** the readout, in the gap above the Start button. Overlays the centred `Column` rather than sitting in it, so it still cannot push the readout |
| Width | Capped at `BANNER_MAX_WIDTH_FRACTION` of screen width. A cap, not a width: short notices keep a band snug around their own text |
| Type | 11 sp, `TextAlign.Center`, amber `#FFB74D` |
| Backing | `#FF3A2A00` (opaque dark amber), 8 dp rounded corners, 8 × 3 dp padding |
| Lifetime | `showTransientMessage` sets `uiMessage`; a `LaunchedEffect` **in `TimerScreen`** clears it after `MESSAGE_DURATION_MS` = 3 s, counted from the composition that puts it on screen |
| Interaction | None. Not tappable, not dismissible, does not block anything |
| Consumers | Five, all via `showTransientMessage`: `restorePendingSelection` → "Saved race unreadable — starting fresh" and "Saved sequence unreadable — using default"; `TimerListener.onClockAdjusted` → "Clock changed — countdown held steady"; `announceRestoreOutcome` → "Resumed race in progress" (`EXACT`) and "Old race ended — starting fresh" (`EXPIRED`) |

### Why it is below the readout, and why the screen owns the timer (#102)

Both were changed by #102, which was filed as "the banner never renders" and was really two separate
reasons a sailor never saw one.

**The timer.** The three seconds used to be counted by `uiHandler.postDelayed` at the call site, so
they started when the message was *posted*. That is fine for the three consumers that fire off an
engine callback with the app already up, and useless for the two that fire from `onCreate`: a cold
launch on an SM-R925U takes **4.4 s to first paint**, measured, so the notice had expired before there
was a screen to put it on. Counting the dwell from the composition that renders it makes three
seconds mean three seconds a sailor could have read it, whatever the app spent getting there.

**The position.** Pinned 2 dp from the top of a *circle*, the banner had a visible chord of about
84 px to live in, and any message that wrapped lost its first line to the bezel mask. Moving it down
far enough to clear the mask put it over the countdown, which rule 1 above forbids. There is no
top-centre geometry that fits a forty-character notice inside the circle *and* above the readout, so
it moved below the readout instead — the widest part of the screen, where the same message needs two
lines instead of three and cannot cover the readout by construction. The arithmetic lives in
`shared/BannerLayout.kt` and is asserted by `BannerLayoutTest`.

The scrim is what makes this work on all four backgrounds. Computed WCAG contrast for `#FFB74D` on the
banner background:

| Background state | Contrast | |
|---|---|---|
| All four | 8.6 : 1 | pass |

11 sp is normal-size text, so the bar is 4.5 : 1. The four rows collapsed to one when #102 made the
scrim opaque: at 80 % the background still contributed a fifth of the composite, and the amber
one-minute state was the worst case at 6.6 : 1. It now contributes nothing, so the worst case is the
only case.

**Copy rules:** one line at 11 sp inside the width cap is roughly 34 characters, and two lines is all
the gap above the Start button holds — so keep a notice under about 60. State what happened and
what it means for the countdown, in that order — "Clock changed — countdown held steady" tells the
sailor the fact *and* that they need do nothing. Never end in an instruction the banner will vanish
before they can follow; that is Tier 3.

---

## Tier 2 — Blocking notice (design only, for #13)

**Nothing implements this yet.** There is no permission check, no `POST_NOTIFICATIONS` handling, and no
foreground-service failure path anywhere in `wear/`; `handleStart` calls `startForegroundService` and
assumes it works ([MainActivity.kt:179](../wear/src/main/kotlin/com/racetimer/wear/MainActivity.kt#L179)).
The three `catch (e: RuntimeException)` blocks in `ToneManager.kt` swallow tone failures silently. This
section defines what #13 builds.

### It is not a dialog

A Wear `Dialog` or `Alert` is swipe-dismissable. A blocking condition that can be swiped away is not
blocking — the sailor dismisses it, taps Start, and gets a silent countdown. It also costs a nav
destination and an animation before a race.

Instead the timer face itself enters a **blocked state**: the message takes the space the Start button
occupies, and Start is not on screen to be tapped.

### Layout

```
        US Sailing  ▾            ← sequence name, unchanged

           5:00                  ← readout stays, dimmed to 40 % alpha
                                   (identity of the screen; greyed = not armed)

    ┌───────────────────────┐
    │ ⚠ Sound is off —      │    ← warning panel, red 1 dp border
    │   the gun will be      │      amber text on a near-black scrim
    │   silent               │
    └───────────────────────┘

        [ Settings ]              ← Start replaced by the remedy action
```

| | |
|---|---|
| Position | Inside the centred `Column`, below `CountdownText`, where `syncLabel` sits |
| Readout | Stays visible at `alpha = 0.4f` — communicates "not armed" without removing context |
| Panel | Scrim `#E6000000` (90 % black), 8 dp rounded, 1 dp `#D32F2F` border. The border carries "this is blocking"; the text stays amber so it never becomes red-on-red |
| Type | `caption1`, amber `#FFB74D`, centre-aligned, **max 3 lines** |
| Contrast | ≥ 11 : 1 on every background state (computed) — the 90 % scrim makes the background nearly irrelevant |
| Primary button | Labelled by the **remedy**, not the problem: "Settings", "Grant", "Retry". Never "OK" |
| Secondary button | None. The pre-start screen has exactly one control ([#55](https://github.com/SailorDave17/race-timer/issues/55) removed Reset), and the remedy takes its place |
| Start | **Absent**, not disabled. A greyed Start on a watch invites repeated taps |
| Dismissal | **None.** Clears only when the precondition passes |
| Re-check | On `onResume` (returning from a Settings intent) and on tapping the primary button |

### Escape hatch

One condition must not be absolutely blocking: **audio unavailable**. A silent countdown is still a
usable countdown if the sailor is watching the screen and feeling the haptics. For that case only, the
primary button reads "Start silent" and arms the sequence with the warning demoted to Tier 3 for the
duration. Every other blocking condition (no foreground service = the countdown will not survive the
screen turning off) has no usable degraded mode and stays hard-blocked.

---

## Tier 3 — Persistent status line (shipped)

The `showResyncPrompt` line at
[TimerScreen.kt:120-129](../wear/src/main/kotlin/com/racetimer/wear/ui/TimerScreen.kt#L120-L129) —
"Recovered — tap Sync to confirm", `caption2` bold, `#FFC107`, directly under the sequence name. It
persists until the sailor taps Sync, because it asks for an action a 3 s banner could not.

Use this tier for anything mid-sequence that needs a *sustained* action or a standing caveat, and Tier 1
for anything that is merely news.

### Known defect — this line has no scrim

`#FFC107` is drawn straight onto the background. Computed contrast:

| Background state | Contrast | |
|---|---|---|
| Normal navy | 10.4 : 1 | pass |
| Final ten | 7.0 : 1 | pass |
| Finished | 6.0 : 1 | pass |
| One minute | **2.9 : 1** | **fails 4.5 : 1** |

Amber text on the amber background. The prompt can be on screen while the timer runs inside 1:00 —
`showResyncPrompt` is not cleared by starting, only by tapping Sync — so this is reachable, not
theoretical. **Fix: give it the same `#CC3A2A00` scrim as the Tier 1 banner.** Belongs to #12; noted
here so the doc is not describing a surface that is quietly broken.

---

## Rules any new message must follow

1. **Scrim or nothing.** Text drawn directly on the background is only safe if it has been checked
   against all four states. In practice: use the scrim.
2. **Never over the readout.** Above it (Tier 1), or below it in the button/label zone (Tiers 2-3).
3. **Blocking is pre-start only.** Once the sequence is running, nothing takes the screen.
4. **Amber is the message colour.** Red is reserved for the final-ten background and for the Tier 2
   border. Red text would collide with `BG_FINAL_TEN`.
5. **Say the consequence, not the cause.** "The gun will be silent" beats "AudioTrack init failed".
6. **One message at a time.** `uiMessage` is a single nullable — a second message replaces the first.
   That is correct; two stacked banners on a 45 mm screen is worse than losing one.

## Message catalogue for #13

Tier assignments so #13 does not have to re-litigate each one:

| Condition | Tier | Copy | Action |
|---|---|---|---|
| Foreground service blocked | 2 | "Can't run in background — open Settings" | Settings → app info |
| Notification permission denied | 2 | "Notifications off — the timer may be killed" | Grant |
| Audio unavailable / tone init failed | 2 (soft) | "Sound is off — the gun will be silent" | Start silent |
| Vibration unavailable | 3 | "No haptics — watch the screen" | none |
| Battery saver active | 3 | "Battery saver — sound may be cut" | none |
| Wall-clock jump | 1 | "Clock changed — countdown held steady" | none — **shipped** |
| Exact recovery (race resumed) | 1 | "Resumed race in progress" | none — **shipped** |
| Spent snapshot discarded | 1 | "Old race ended — starting fresh" | none — **shipped** |
| Degraded recovery | 3 | "Recovered — tap Sync to confirm" | Sync — **shipped** |

Copy is a starting point, not fixed — all of it is untested on a wrist in sun.

## Open questions

- **Where does the pre-start check run?** Tier 2 needs the preconditions evaluated *before*
  `handleStart` fires the service intent, which means a check on `onResume` as well, so the blocked
  state is visible rather than appearing only after a failed tap. #13's call.
- **Does battery saver deserve Tier 2?** Listed as Tier 3 above. If testing shows saver mode reliably
  kills the tone, promote it.

---

Source: this repo's code as of the `develop` branch, plus issues #22, #13, #12.
Owner: SailorDave17.
Last reviewed: 2026-07-30.
