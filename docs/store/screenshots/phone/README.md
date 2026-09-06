# Play Store screenshots — phone

Phone store screenshots for the Play listing's second form factor
([#213](https://github.com/SailorDave17/race-timer/issues/213)), captured from a **signed release
build** on an Android emulator. To be entered in Play Console by
[#214](https://github.com/SailorDave17/race-timer/issues/214); these files are the source, the
Console is the copy — the same arrangement as the Wear set one directory up and as
[`../../listing.md`](../../listing.md).

Play's phone asset rules were **read from the Console help page on 2026-09-05, not copied from the
Wear set** — the issue asked for that because specs drift, and the two do differ in the one
dimension that matters. The machine-checkable half is checked rather than asserted here: run
`python docs/store/check-screenshots.py`, which applies the phone rules to this directory and the
Wear rules to its parent, and quotes the rules in its docstring. Nothing in this file states a pixel
count or an image count — those are the claims that go stale first.

## Which build these depict

| | |
|---|---|
| Commit | `a9aceff` (`develop`, tip at capture time) |
| Build | `:phone:assembleRelease` — R8-processed, signed with the upload key |
| Signer | `CN=Race Timer Upload, O=SailorDave17`, SHA-256 `918a8257…fe2dc8f6` — the same certificate the Wear set records |
| `versionName` / `versionCode` | `1.0` / `2` |
| Device | Android emulator, AVD `RaceTimer_Phone_1080x1920`, `google_apis` x86_64 system image, Android 16 (API 36), 1080 × 1920 at 440 dpi |
| Captured | 2026-09-05 |

**The installed APK was hash-verified against the local build before a single capture** —
`sha256sum` on the device path from `pm path` against the local artefact, identical. That is what
makes these screenshots evidence about the build that ships rather than about whatever was already
installed.

The uploaded artefact will be `phone-release.aab`, built and signed by `release.yml`; what was
installed to capture these is `phone-release.apk` from the same source tree and the same signing
key. They are not the same file, and this table says so rather than eliding it: no source changed
between them, so the UI is identical, but the literal bytes Play receives were never on this
emulator — Play rebuilds the APK from the bundle.

**Re-capture if the UI changes before upload.** These stop being accurate the moment a screen moves,
and Play requires at least one screenshot depicting the *current* version. The commit above is how a
later release tells whether that has happened.

## Why an emulator, and why this one

An emulator rather than the owner's phone because the issue priced it that way — *"emulator
screenshots are session-producible"* — and because a screenshot is a capture of the framebuffer, not
a verification of behaviour: nothing in these files depends on a real panel, a real speaker or a
real motor, which are the things `verify-hardware-claims-on-hardware` reserves the physical device
for. The owner's phone is an S23 Ultra (`SM-S918U`, 1440 × 3088), and that is also why it would
have been the *wrong* instrument here, see next.

**Play caps a phone screenshot's long side at twice its short side**, and no current phone display
fits. The three phone AVDs already on the build machine are 1440 × 3088 and 1080 × 2424 — both
taller than 2:1 — so a raw framebuffer from any of them fails the rule outright and a crop would be
the one edit the no-composition argument below cannot make. The AVD named above was created for
this set with a **1080 × 1920** display, which is Play's stated minimum for a 9:16 portrait
screenshot to be eligible for its large-format placements and lands inside every mandatory rule by
construction. The app declares no `screenOrientation` and lays out for whatever display it gets, so
what these show is the app on a 16:9 phone, not a synthetic frame.

One consequence of the shorter display is visible in the picker screenshot: the **Custom** entry's
subtitle sits under the navigation bar and the list scrolls to reach it. That is what a 16:9 phone
shows and is left as captured.

## How they were captured

`adb exec-out screencap -p` — a framebuffer read. That method is why the no-added-text and
no-composed-background expectations hold by construction: there is no compositing step in which a
caption or a frame could be added, and nothing was resized. The status bar is the emulator's real
one, not demo mode.

The one edit applied is `convert("RGB")`, dropping the alpha channel `screencap` emits. Every alpha
byte was already 255, so no pixel changed colour — Play's phone rule reads *"24-bit PNG (no alpha)"*,
and stripping the channel is what makes the file say so.

The UI was driven with `adb shell input tap` and timed captures from the Start tap, so the countdown
shots land inside their colour states rather than at their edges. Unlike the watch, the phone's
final-ten readout does not flash, so there was no burst to choose from.

## The set

The first eight are the listing, in order; Play accepts at most eight per form factor. The state
machine is the app's whole value, so the sequence walks it rather than showing eight views of one
screen, and it is deliberately the same walk as the Wear set so the two listings read as one product.

1. `01-sequence-picker.png` — the sequence list, the first thing an officer sees
2. `02-pre-start-us-sailing.png` — US Sailing 5-4-1-Go armed at 5:00
3. `03-running-navy.png` — running above the minute, navy, with Sync and Stop
4. `04-last-minute-amber.png` — the last-minute amber state
5. `05-final-ten-red.png` — the final-ten red state
6. `06-gun-green.png` — the gun
7. `07-race-manager-count-up.png` — Scholastic Race Manager counting up after the gun, with End Race
8. `08-race-manager-pre-start.png` — the race-manager pre-start offering Start beside Lead-in

Three more are versioned here and deliberately **not** in the eight, because the four colour states
and the two race-manager screens earn their slots first. They are the obvious substitutes if a slot
frees up:

- `09-race-ended-frozen.png` — the finish time frozen on End Race
- `10-custom-duration.png` — the Custom duration stepper
- `11-race-manager-lead-in.png` — the box-alert picker behind the Lead-in control

The Wear set leads with Custom in slot 8 and holds the lead-in back; this set swaps them. On the
console the race-manager modes are the point — the epic's signature moment is an officer propping
the phone on the committee boat — so the second race-manager screen outranks the stepper here.

### Landscape alternates

A propped console phone is as likely to be on its side as upright, so the same walk was captured
again in **landscape** (1920 × 1080, the emulator rotated with `user_rotation`; the app declares no
orientation and lays out for it). They are held back from the eight, not because they are weaker
but because Play presents one orientation per listing cleanly and the portrait set matches the Wear
walk. Swap the pair in if the listing is ever re-cut for the console moment:

- `12-sequence-picker-landscape.png`
- `13-pre-start-us-sailing-landscape.png`
- `14-running-navy-landscape.png`
- `15-last-minute-amber-landscape.png`
- `16-final-ten-red-landscape.png`
- `17-gun-green-landscape.png`
- `18-race-manager-count-up-landscape.png` — US Sailing — Race Manager rather than Scholastic, because
  it is the race-manager entry the landscape picker shows without scrolling; the count-up screen is
  the same for both

The checker reports each of these as `16:9 promo-eligible`, which is the landscape half of the
same Play recommendation the portrait set meets.

## The feature graphic stands

The second criterion of #213 asked whether the existing 1024 × 500 feature graphic
(`store/feature_graphic_1024x500.png`, #77) still serves a two-form-factor listing, *decided
rather than defaulted*. **Owner decision, 2026-09-05: it stands as-is.**

The reasoning, so it is not re-litigated: nothing in the graphic names a form factor — it is the
mark, the name, *"Never late to the start."* and the 5-4-1-Go strip, and #77 already rejected the
one tagline that did (*"on your wrist"*) for a different reason. The obvious revision would put a
phone beside a watch, and that depicts the **pair** — the two devices counting one gun — which the
listing copy deliberately does not claim until the link stories ship (#212's owner decision, in
[`../../listing.md`](../../listing.md)). A banner promising what the copy withholds would break
the listing's own rule from the top of the page. When the link ships and the listing takes its
third revision, the graphic is re-asked at the same time.
