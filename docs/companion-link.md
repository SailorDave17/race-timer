# The companion link

The phone app tells the race committee's companion app on the same phone about each start sequence
it runs, so the companion logs the start without the PRO tapping it in
([#294](https://github.com/SailorDave17/race-timer/issues/294)). The companion is
`SailorDave17/pro-companion`. The contract is its **ADR 004**, "The race-timer link", and this page
says how race-timer keeps its half. Read the ADR for why the mechanism is what it is.

race-timer owns the sequence and the companion only logs it, so nothing here changes what race-timer
does. With no companion installed, a race runs exactly as before.

## What is sent

One JSON object per event, as one raw binder transaction to the companion's link service. The code
is `phone/.../CompanionContract.kt`.

| Kind | When | Fields |
|---|---|---|
| `sequence_start` | Start is tapped on the phone, or the phone joins a race the watch started | `v`, `id`, `kind`, `at_ms`, `sequence` (its own `id`), `sequence_name` |
| `signal` | A horn cue at **one minute or more** before the gun sounds | `v`, `id`, `kind`, `at_ms`, `sequence`, `signal`, `seconds_to_start` |
| `start` | The gun sounds | `v`, `id`, `kind`, `at_ms`, `sequence` |

- `at_ms` is the wall clock at the moment the cue fired, not when the event was sent.
- `signal` is `warning`, `preparatory` or `one_minute`, found by position: the warning is the
  sequence's first signal, the one-minute signal is at 1:00, and the preparatory is a minute after the
  warning. Any other signal goes by its cue's label (US Sailing's `3 minutes`, Scholastic's `1 long 3
  short`), which the contract allows.
- **Not sent**: the sub-minute countdown calls, the wrist-only sync and prompt ticks, and anything in
  a lead-in. The floor is the owner's decision at #294's pickup: the companion buzzes once per stored
  event, and a sub-minute call every second near the gun would bury race-timer's own cues.
- **No fleet, class or race id.** The companion attaches each sequence to the fleet the PRO has armed.
- **Not built**: `postponement`, `individual_recall` and `general_recall`. race-timer has no control
  for any of them, so it never fires one. The PRO taps them on the companion. Adding them to
  race-timer is a story of its own, filed at #294's commit gate:
  [#339](https://github.com/SailorDave17/race-timer/issues/339).

## Who is trusted

race-timer binds `com.procompanion.app/com.procompanion.app.LinkService` by explicit component, and
only after `PackageManager.hasSigningCertificate` says the installed package is signed by a pinned
certificate. It checks again when the binding connects. `CompanionPins` holds the pins:

- **Every build** pins the companion's field key, the owner's upload key every field build is signed
  with: `8578eb05…bdc5506` (ADR 004's table; pro-companion `docs/field-builds.md`).
- **A debug build** also pins whatever `-PcompanionDebugCerts=<sha256>[,<sha256>…]` names, for a
  developer's debug-signed companion or the harness's stand-in. A release build adds nothing. A digest
  that is not 64 hex characters fails the build.

pro-companion #168 has still to decide how a volunteer installs the companion. A route through Play
would re-sign it with Play's key, and that digest would go beside the field key: one line in
`CompanionPins` and a race-timer release.

The manifest's `<queries>` entry for `com.procompanion.app` is what makes the companion visible at
all (Android 11+). It is not a permission, and `docs/declared-surface.lock` does not record it.

## Delivery

At least once, oldest first, kept until taken (ADR 004 decision 4):

- The cue path, on the main thread, reads the wall clock and posts to the link's own background
  thread. A cue that is not sent costs one comparison. Everything else (the id, the file, the trust
  check, the bind and the transaction) runs on that thread.
- Each event is written to `companion_outbox.json` in the app's private storage before it is sent,
  and leaves when the companion answers `accepted`, or `refused` or `malformed`, which a resend
  cannot change. `no_core`, a dead binder, no answer, or a bind the platform refuses keeps it, and
  it is resent under the same id after 1, 2, 5, 10, then every 30 s.
- The binding lasts from a sequence's start to its gun or its end, then up to five more minutes
  while anything is waiting. What is still waiting then is resent first at the next sequence's
  start: the companion orders a late backlog by `at_ms`.
- At each sequence's start, with no trusted companion, the link binds nothing, writes nothing and
  deletes any backlog.
- A race restored after a process death carries on under the sequence id it was saved with.

The outbox writes a temporary file and moves it over the old one in one atomic step. Not
`android.util.AtomicFile`: measured under Robolectric on Windows 11, its second write was lost
silently, so its unit tests could not have told a working outbox from a broken one here.

## Verifying it

**In CI**, `:phone:testDebugUnitTest` runs `CompanionContractTest`, `CompanionLinkTest`,
`CompanionOutboxTest`, `CompanionPinsTest` and `CompanionRaceTest`: the cue map, the wire shape, the
outbox, every delivery status, the linger, the backlog across a process, and where the runner calls
the link.

**On an emulator**, `harness/companion-link/run.sh` runs `CompanionLinkDeviceTest`, `:phone`'s only
instrumented test, against a stand-in companion (`harness/companion-link/stub`, the `:companion-stub`
module). CI has no emulator, so this is never a gate step. It installs the stand-in three ways:

| State | Methods | Criterion |
|---|---|---|
| TRUSTED, signed with the key this build pins | `trustedCompanionReceivesTheSequenceInOrder`, `noCoreIsResentUnderTheSameId`, `cueTimingHoldsWithTheLink` | 1, 2, 4, 5 |
| IMPOSTER, the same APK re-signed with a throwaway key | `imposterIsSentNothing` | 4 |
| ABSENT | `absentCompanionChangesNothing`, `cueTimingHoldsWithTheLink` | 2, 3 |

```
ANDROID_SERIAL=<emulator serial> sh harness/companion-link/run.sh
```

It uninstalls whatever holds `com.procompanion.app` first, so never point it at a phone carrying
the real companion. Each method asserts the state it was written for before anything else.

What it cannot show: a real phone's CPU sleeping, an OEM battery manager, and the real companion.
Those are pro-companion #34's, with the real race-timer on a personal phone.

### Measured

On 2026-11-03, on an API 34 `google_apis` x86_64 emulator (Pixel 5 profile), on #294's branch before its commit:
all six methods passed, every verdict read as the whole line `OK (1 test)`.

| Cue lateness, Custom 1:00, 16 cues | Worst | Run 2 worst |
|---|---|---|
| Companion installed, link sending | 22 ms | 12 ms |
| Companion absent | 20 ms | 13 ms |

Both are far inside #10's 250 ms ([timing-accuracy.md](timing-accuracy.md)), and the two arms are
indistinguishable. An emulator's clock never sleeps, so this shows the link costs the cue path
nothing measurable, not how a real phone keeps time in Doze.

**Proven able to fail.** Each red count was predicted before the run, and each mutation reverted and
its file hash checked before the next:

| Mutation | Predicted red | Actual |
|---|---|---|
| M1: the pre-bind certificate check returns true | device 3 (imposter, both ABSENT) | 3: the imposter received the `sequence_start` |
| M3: build without the stand-in's pin | device 3 (the TRUSTED methods) | 3 |
| M4: the transport writes the wrong interface token | device 3 (the TRUSTED methods) | 3: the stand-in recorded nothing |
| M5: `no_core` read as `accepted` | unit 4 | 4 |
| M6: the one-minute floor deleted | unit 11 | 11 |
| M7: `<queries>` deleted | device 4 | **3**, then 4 after the fix below |
| N1: `at_ms` read on the worker, not at the call | unit 1 | 1 |
| N2: the outbox written before the trust check | unit 1 | 1: by the outbox's write count, since the file is deleted again at once |
| N3: a refused bind not retried | unit 2 | **1**, then 2 after the fixture fix below |
| N4: the lead-in clause deleted | unit 1 | 1 |

N1 to N4 came from a `review-fanout` run on this change, which found those four claims held by no
test. N3's first run was shape 9 of the house's list of ways a test proves nothing: the gun's own
`finish()` schedules one retry, so a bind accepted at that retry was delivered with or without the
fix. The fixture now refuses past that first retry.

M7's first run was a finding about the test, not the code. `cueTimingHoldsWithTheLink` runs in two
states and asked only "TRUSTED or ABSENT?", so with the companion made invisible its TRUSTED run read
ABSENT, ran the other arm, and passed: "with and without a receiver" had become "without, twice".
The script now passes the state it installed (`-e stand_in`) and every method requires exactly that.

Not proven by any test: the re-check of the certificate when the binding connects. Nothing replaces
the companion between the check before the bind and the connection, so no test can reach it.
