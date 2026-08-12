# Message Surface — Warnings and Errors on the Watch

Where a warning or an error appears on a one-screen countdown, what it looks like, and what it does to
the Start button. Written so [#13](https://github.com/SailorDave17/race-timer/issues/13) has a concrete
target to build against instead of inventing a surface per error.

Closes the design half of [#22](https://github.com/SailorDave17/race-timer/issues/22).

**Status:** all three tiers are shipped and running on hardware. Tier 2 was a design only until
[#13](https://github.com/SailorDave17/race-timer/issues/13) built it.

Contrast figures below are **computed by `shared/MessageContrast.kt` and asserted by
`MessageContrastTest`**, not typed in here — every one of them was hand-calculated until
[#123](https://github.com/SailorDave17/race-timer/issues/123), and two were wrong (see the Tier 1
table). They are still *computed*, not measured on the watch under sun; the sun audit is
[#12](https://github.com/SailorDave17/race-timer/issues/12) / [#121](https://github.com/SailorDave17/race-timer/issues/121).

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

   Source: `backgroundArgbFor` in `shared/src/main/kotlin/com/racetimer/shared/MessageContrast.kt`,
   which `TimerScreen` renders through. It moved out of `wear/` in #123 so the contrast guard could
   derive each surface's reachable background set by driving it instead of restating its branches.

   Amber-on-amber is the case that bites. Any message that relies on the background being navy will
   disappear at exactly the moment the race gets tense.

## Three tiers

All three exist. Naming them before #13 was built is what kept it from adding a fourth — the
five conditions it wired up all landed on tiers defined here rather than inventing a surface each.

| Tier | Surface | Blocks Start? | Lifetime | Status |
|---|---|---|---|---|
| 1 | Transient banner, top-centre | No | Auto-clears after 3 s | **Shipped** (`d85684d`) |
| 2 | Blocking notice, in place of Start | **Yes** | Until the condition is resolved | **Shipped** (#13) |
| 3 | Persistent status line under the sequence name | No | Until the user acts | **Shipped**, two consumers |

The split is the answer to #22's "blocking vs. transient vs. persistent": it is not one of the three,
it is all three, chosen by *when* the problem occurs and *whether the sailor can do anything about it*.

**The rule:** if the problem happens **before Start** and makes the sequence unreliable, it blocks
(Tier 2). If it happens **mid-sequence**, it can never block — the countdown is running and a modal over
it is worse than the problem. It informs and clears (Tier 1), or informs and persists if it needs an
action (Tier 3).

---

## Tier 1 — Transient banner (shipped)

`MessageBanner` in [TimerScreen.kt:661](../wear/src/main/kotlin/com/racetimer/wear/ui/TimerScreen.kt#L661),
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
| All four | 8.03 : 1 | pass |

11 sp is normal-size text, so the bar is 4.5 : 1. The four rows collapsed to one when #102 made the
scrim opaque: at 80 % the background still contributed a fifth of the composite, and the amber
one-minute state was the worst case at 6.55 : 1. It now contributes nothing, so the worst case is the
only case.

*This figure read **8.6 : 1** from #102 until #123 recomputed it. 8.53 is `#FFC107` — Tier 3's text
colour — on this scrim; the published number for Tier 1 had been worked out with the wrong
foreground. Nothing was broken by it (both clear the bar), and nothing could have caught it: a wrong
ratio in prose reads exactly like a right one. That is why the numbers moved into a test.*

**Copy rules:** one line at 11 sp inside the width cap is roughly 34 characters, and two lines is all
the gap above the Start button holds — so keep a notice under about 60. State what happened and
what it means for the countdown, in that order — "Clock changed — countdown held steady" tells the
sailor the fact *and* that they need do nothing. Never end in an instruction the banner will vanish
before they can follow; that is Tier 3.

---

## Tier 2 — Blocking notice (shipped, #13)

`BlockingNotice` in `wear/src/main/kotlin/com/racetimer/wear/ui/TimerScreen.kt`, driven by the
`startNotice: StartNotice?` parameter and rendered **inside the branch that draws the Start button**.
That placement is the enforcement of rule 3 below: blocking cannot reach a running race because the
only branch it lives in is the pre-start one, so it is geometry rather than a condition that could be
got wrong.

*The decision of what to show is not here.* `shared/StartPreconditions.kt` holds it —
`DeviceReadiness` is five observations gathered in `wear/`, and `startNotice()` picks the single
notice worth showing. Same split as the colours: a rule inside a Compose screen can only be checked
by holding a watch, and `StartPreconditionsTest` checks this one on every combination of the five.

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
| Contrast | 11.38 : 1 worst case, 11.95 : 1 best — computed by `MessageContrast.kt`, asserted by `MessageContrastTest`. The 90 % scrim makes the background nearly irrelevant, and the border is held to WCAG's **non-text** 3 : 1 (it lands at 3.96 : 1) rather than the 4.5 : 1 the copy clears |
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

Two consumers, both `caption2` bold in `#FFC107` directly under the sequence name, and both
persisting until the sailor acts because each asks for something a 3 s banner could not:

| Consumer | Line | On screen when | Scrim |
|---|---|---|---|
| Degraded-recovery prompt | "Recovered — tap Sync to confirm" | `RUNNING`, after a `DEGRADED` restore, until Sync is tapped | **Yes** — `#FF3A2A00`, added by #123 |
| Discard warning (#89) | "Start discards saved *name*" | `IDLE` pre-start only, cleared by `clearResumeOffer` | No — navy is its whole exposure |

Both live in [TimerScreen.kt:174-214](../wear/src/main/kotlin/com/racetimer/wear/ui/TimerScreen.kt#L174-L214).

Use this tier for anything mid-sequence that needs a *sustained* action or a standing caveat, and Tier 1
for anything that is merely news.

### The amber-on-amber defect — fixed in #123

Bare `#FFC107` on the four backgrounds, which is what the re-sync prompt drew until #123:

| Background state | Contrast | | Prompt reachable there? |
|---|---|---|---|
| Normal navy | 10.46 : 1 | pass | yes |
| Final ten | 6.99 : 1 | pass | yes |
| One minute | **2.93 : 1** | **fails 4.5 : 1** | **yes — this was the defect** |
| Finished | 6.00 : 1 | pass | **no** — the prompt needs `RUNNING`, green needs `FINISHED` |

Reachable, not theoretical: `showResyncPrompt` is not cleared by starting, only by tapping Sync, so a
sailor who reboots mid-race and never syncs carries it into the final minute — the moment the race
gets tense.

**Fixed** by giving the prompt Tier 1's scrim, which takes it to **8.53 : 1** on every background.
The remedy this section used to name was `#CC3A2A00`, the *80 %* scrim — written before #102 replaced
it with an opaque one, so following it literally would have reinstated exactly the alpha #102 removed.
It would have passed (6.95 : 1 on amber) and given back 1.5 : 1 for nothing.

The discard warning stays deliberately bare: it is confined to the `IDLE` pre-start screen, so navy at
10.46 : 1 is its whole exposure. That is rule 1's "checked against all four states" branch rather than
an exception to it, and `MessageContrastTest` derives the confinement from `backgroundArgbFor` rather
than trusting this paragraph.

---

## Rules any new message must follow

1. **Scrim or nothing.** Text drawn directly on the background is only safe if it has been checked
   against all four states. In practice: use the scrim. Since #123 "checked" means *asserted* — add
   the surface to `MessageContrastTest` and let it derive the backgrounds the surface can reach; a
   ratio argued in prose here is how the 8.6 : 1 above stayed wrong.
2. **Never over the readout.** Above it (Tier 1), or below it in the button/label zone (Tiers 2-3).
3. **Blocking is pre-start only.** Once the sequence is running, nothing takes the screen.
4. **Amber is the message colour.** Red is reserved for the final-ten background and for the Tier 2
   border. Red text would collide with `BG_FINAL_TEN`.
5. **Say the consequence, not the cause.** "The gun will be silent" beats "AudioTrack init failed".
6. **One message at a time.** `uiMessage` is a single nullable — a second message replaces the first.
   That is correct; two stacked banners on a 45 mm screen is worse than losing one.

## Message catalogue

Every row below is **shipped**. The copy is held as constants in `shared/StartPreconditions.kt` and
`StartPreconditionsTest` asserts each one fits the panel, so a copy edit that outgrows the screen
fails rather than wraps.

| Condition | Tier | Copy | Action |
|---|---|---|---|
| Foreground service blocked | 2 | "Can't run in background — open Settings" | Settings → app info |
| Notification permission denied | **3** | "Notifications off — timer may be killed" | Settings (tap the line) |
| Audio unavailable / tone init failed | 2 (soft) | "Sound is off — the gun will be silent" | Start silent |
| Vibration unavailable | 3 | "No haptics — watch the screen" | none |
| Battery saver active | 3 | "Battery saver — sound may be cut" | none |
| Wall-clock jump | 1 | "Clock changed — countdown held steady" | none — **shipped** |
| Exact recovery (race resumed) | 1 | "Resumed race in progress" | none — **shipped** |
| Spent snapshot discarded | 1 | "Old race ended — starting fresh" | none — **shipped** |
| Degraded recovery | 3 | "Recovered — tap Sync to confirm" | Sync — **shipped**, scrimmed #123 |

### The notification row moved from Tier 2 to Tier 3, and that was a decision

This table put a denied notification permission at Tier 2 — Start removed, a "Grant" button in its
place — from #22 until #13 built it. **#13's own first acceptance criterion says the opposite**: the
sequence still starts and runs, and the sailor is informed once with a route to Settings.

The criterion is right and the table was wrong. On Android 13+ a foreground service **still starts**
with `POST_NOTIFICATIONS` denied; only the notification is suppressed. Blocking would therefore have
refused a race that would have run correctly, on a screen whose whole purpose is starting races. So
the row changed rather than the criterion (owner decision, 2026-08-11), and
`StartPreconditionsTest` carries an assertion named for it — a denied notification permission must
never remove Start — so restoring the old row from this table alone turns the suite red.

The remedy survives the demotion: the Tier 3 line is **tappable** and opens the app's notification
settings. That is the "clear next step" half of the criterion, and it is why this is the one Tier 3
consumer with an interaction. A second button was rejected — the pre-start screen has exactly one
control by design (#55), and a remedy button would have displaced Start to warn about something that
does not stop it.

Copy is a starting point, not fixed — all of it is untested on a wrist in sun.

## Error-surface audit (#13 AC5)

*"Every error surface has a visible message, not just a log line."* That is a claim about the whole
app rather than about a feature, so it is discharged as an audit: every `Log.w`/`Log.e` in `wear/`
classified, with the ones that stay silent saying why. Re-run it with
`grep -rn "Log\.\(w\|e\)(" wear/src/main/kotlin/` — 20 sites as of 2026-08-11.

### Visible to the sailor

| Failure | What is seen |
|---|---|
| Foreground service refused at dispatch (`MainActivity.armRace`) | Tier 2, Start removed |
| Foreground service refused inside the service (`startForegroundWithNotification`) | Tier 2, and the race is torn down rather than left running without a service |
| No audio output on the device (`obtainTrackLocked`) | Tier 2 soft, "Start silent" |
| `AudioTrack` build failed (`obtainTrackLocked`) | Tier 2 soft, "Start silent" |
| No vibrator | Tier 3, "No haptics — watch the screen" |
| Notification permission denied | Tier 3, tappable to Settings |
| Battery saver on | Tier 3, "Battery saver — sound may be cut" |
| No settings activity for a remedy | The notice it was offered from stays on screen |

### Silent by decision, with the reason

| Failure | Why no message |
|---|---|
| Keep-alive generator or `startTone` unavailable; start threshold rejected; keep-mixed write failed; could not idle the cue track | **Not failures.** Every one of these costs the cue latency — tens of milliseconds — and the cue still sounds. A banner saying a cue may be slightly late is worse than the lateness. |
| `AudioTrack` stop / release / keep-alive release failed | Teardown, after the race. Nothing left to affect. |
| Native output rate unavailable | Falls back to a working rate; the cue is unchanged. |
| Output rate changed with the stream | Handled by re-rendering. Not an error. |
| `setStreamVolume` refused (Do Not Disturb) | Recorded in `TimerService.cueVolumeRefused`. Deliberately **not** #13's to surface — [#96](https://github.com/SailorDave17/race-timer/issues/96) owns that warning and its design rests on the measured refusal rather than a prediction. Two warnings for one condition would be a second copy of the rule. |
| Haptic dropped under Do Not Disturb | The app cannot see it — the platform drops the effect and reports nothing. [#144](https://github.com/SailorDave17/race-timer/issues/144). |

### The one genuine gap, stated rather than closed

**A cue lost or truncated mid-race is silent.** Three paths in `ToneManager.writeCue` — a write
returning `<= 0` ("cue dropped"), an `IllegalStateException` discarding the track, and a failed tail
write ("cue truncated") — produce a log line and nothing else. None of them sets `initFailed`, so
the condition does not resurface as a Tier 2 block before the next race either.

It is left open rather than closed, for a reason worth stating: the fix is a Tier 1 banner on the
*running-race* screen, which is the most sensitive surface in the app and the one #102 already had
to move once. It also needs a route from the tone thread to the UI that does not exist yet. That is
a piece of work with its own risk, not a line to add to this story.

## Resolved questions

- **Where does the pre-start check run?** *(#13)* Three of the five conditions are readable ahead of
  a tap — the permission, the vibrator, battery saver — and are read on `onStart` (which is the
  callback a return from Settings arrives through), on service binding, and on a 1 s throttle while
  the pre-start screen is up. The other two **cannot** be: there is no API that answers "would a
  foreground service be allowed right now?", and the audio stack only answers once something has
  tried. Those two are latched from an attempt that already failed. So the answer is *both*, split
  by what the platform will actually tell you in advance rather than by preference.
- **Does battery saver deserve Tier 2?** *(#13)* No — left at Tier 3. Promoting it would block a
  race on a condition that has never been measured to kill a cue here, and the copy is hedged
  ("may be cut") for the same reason. Worth revisiting only with a measurement behind it.

## Open questions

- **Is the copy right on a wrist, in sun?** None of the five notices has been read by anyone outside
  a development session. #121's sun audit is the instrument, and #12 the parent.

---

Source: this repo's code as of the `develop` branch, plus issues #22, #13, #12, #123.
Owner: SailorDave17.
Last reviewed: 2026-08-11 (#13 — Tier 2 built, the notification row demoted to Tier 3 with its
reasoning recorded, both open questions answered).
