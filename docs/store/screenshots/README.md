# Play Store screenshots

Wear OS store screenshots for the Play listing, captured from the **physical watch** running a
**signed release build**. Entered in Play Console by
[#79](https://github.com/SailorDave17/race-timer/issues/79); these files are the source, the Console
is the copy — the same arrangement as [`../listing.md`](../listing.md).

Play's asset rules for Wear are the strict ones (1:1, minimum 384 × 384, no device frames, no added
text or composed background, no transparency). The machine-checkable half is checked rather than
asserted here: run `python docs/store/check-screenshots.py`. Nothing in this file states a pixel
count or an image count — those are the claims that go stale first.

## Which build these depict

| | |
|---|---|
| Commit | `3cd45ae` (`develop`, tip at capture time) |
| Build | `:wear:assembleRelease` — R8-processed, signed with the upload key |
| Signer | `CN=Race Timer Upload, O=SailorDave17`, SHA-256 `918a8257…fe2dc8f6` |
| `versionName` / `versionCode` | `1.0` / `1` |
| Device | Galaxy Watch 5 Pro, `SM-R925U`, Wear OS on Android 16 (API 36), 450 × 450 |
| Captured | 2026-08-12 |

**The installed APK was hash-verified against the local build before a single capture** — `sha256sum`
on the device path from `pm path` against the local artefact, identical. That is what makes these
screenshots evidence about the build that ships rather than about whatever was already on the watch.

The uploaded artefact is `wear-release.aab`, and what was installed to capture these is
`wear-release.apk` from the same source tree and the same signing config. They are not the same file,
and this table says so rather than eliding it: no source changed between them, so the UI is identical,
but the literal bytes Play receives were never on this watch — Play rebuilds the APK from the bundle.

**Re-capture if the UI changes before upload.** These stop being accurate the moment a screen moves,
and Play requires at least one screenshot depicting the *current* version. The commit above is how a
later release tells whether that has happened.

## How they were captured

`adb exec-out screencap -p` — a framebuffer read. That method is why the no-device-frame,
no-added-text and no-composed-background rules hold by construction: there is no compositing step in
which a frame or a caption could be added, and nothing was resized (the watch's native 450 × 450 is
what these files carry, comfortably above Play's 384 minimum).

The one edit applied is `convert("RGB")`, dropping the alpha channel `screencap` emits. Every alpha
byte was already 255, so no pixel changed colour — this removes an argument with a reviewer about
whether "no transparency" means "no alpha channel", rather than winning it.

Two capture notes worth keeping, both of which cost a re-run:

- **The final-ten red state flashes** (`TimerScreen`'s `flashAlpha`, 1.0 → 0.3 over 400 ms). A
  screenshot taken at an arbitrary moment catches the numerals mid-fade and reads as a rendering
  fault rather than as the design. `05-final-ten-red.png` was chosen by measuring the recovered alpha
  across a burst and taking the highest — it is at 0.98.
- **`screencap` on this watch returns the unmasked square framebuffer**, drawing content into corners
  the round display does not physically have. For Play that is exactly right, since the rules demand
  the full square frame with no masking — but it means these files are *not* the instrument for
  judging whether something is clipped at the bezel. See
  `cairn/memory/reference/wear-os-adb-pairing-2026-07-30.md`.

## The set

The first eight are the listing, in order; Play accepts at most eight. The state machine is the app's
whole value, so the sequence walks it rather than showing eight views of one screen.

1. `01-sequence-picker.png` — the sequence list, the first thing a sailor sees
2. `02-pre-start-us-sailing.png` — US Sailing 5-4-1-Go armed at 5:00
3. `03-running-navy.png` — running above the minute, navy, with Sync and Stop
4. `04-last-minute-amber.png` — the last-minute amber state
5. `05-final-ten-red.png` — the final-ten red state
6. `06-gun-green.png` — the gun
7. `07-race-manager-count-up.png` — Scholastic Race Manager counting up after the gun
8. `08-custom-duration.png` — the Custom duration stepper

Two more are versioned here and deliberately **not** in the eight, because the four colour states earn
their slots first. They are the obvious substitutes if a slot frees up:

- `09-race-manager-lead-in.png` — the race-manager pre-start offering the signal-box lead-in
- `10-race-ended-frozen.png` — the finish time frozen on End Race
