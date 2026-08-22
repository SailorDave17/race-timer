# The Console Phone's Start Day — Scenario, Instrument and Result

Whether a phone propped on a committee-boat console survives a whole scholastic start day on one
charge, and what the count-up costs the panel while it does.

Answers [#216](https://github.com/SailorDave17/race-timer/issues/216), which is epic
[#196](https://github.com/SailorDave17/race-timer/issues/196)'s verification of bar condition 1 —
*a scholastic regatta runs its whole start day off the console phone*. It also carries
[#279](https://github.com/SailorDave17/race-timer/issues/279)'s AC 1, deferred here because this
story owns the unplugged day, the budget and the instrument.

**Status: the scenario below is written and the run has not happened.** The Results section says so
in as many words, and it is the only section a reader should treat as pending.

## The scenario comes first, and that is the point

The scenario in the next section was authored **before** any instrument existed to run it against —
it is the first commit on this story's branch, and the recorder and the parser are the second. That
ordering is not ceremony. A scenario written after the tool relaxes to fit the tool, keeps the name
of the criterion, passes, and never once exercises the number the criterion contains; cairn's
`a-scenario-can-relax-to-fit-its-instrument` measured exactly that on
[#125](https://github.com/SailorDave17/race-timer/issues/125), where *"the under-10s-to-gun edge is
confirmed on device"* became a comfortable 20–30 second kill because that is what the tooling could
manage, with a sentence of engineering judgement explaining why.

So: the numbers below were fixed by the owner against a real regatta shape, and anything the
instrument turns out to be unable to deliver is recorded as a **gap in the instrument**, never as a
revision of the scenario. If a paragraph in this file ever reads *"which is why we measure X
instead"*, that clause is where the criterion leaked out and it is worth one minute to find out.

## The scenario

**A full ICSA two-division day: 14 sequences over 6 hours, unplugged throughout, with the battery
above 20% at the last gun and no cue missed.**

Owner-set, 2026-08-22, from the shape of a real scholastic regatta: A and B divisions sailing
alternately in one course area, the Scholastic Race Manager sequence, a 3:00 head to each start and
an unbounded count-up from each gun to End Race.

| | |
|---|---|
| Sequences | **14**, all `Scholastic - Race Manager` |
| Span unplugged | **6 hours**, from arming to the last End Race |
| Battery floor | **20%** at the last gun |
| Missed cues permitted | **zero** |
| Phone | the owner's Galaxy S23 Ultra (`SM-S918U`) — recorded by the instrument, not assumed |

The timetable is a cadence rather than a clock: a race cycle is a 3:00 sequence, a gun, roughly
eighteen to twenty-two minutes of count-up, End Race, and three to five minutes of reset before the
next warning. Fourteen of those is a little under six hours. **Nothing here requires the cadence to
be hit exactly** — the instrument timestamps everything, so a day that runs long or short is still a
day, and the parse reports what actually happened rather than checking it against a plan.

What the day must not contain: a charger, an attached adb session (see *What this cannot see*), or
the app being force-stopped anywhere except the three block boundaries below.

### The four blocks, and why the day is cut into them

[#279](https://github.com/SailorDave17/race-timer/issues/279) shipped a question at the gun — *keep
the screen bright through the count-up?* — and its answer is deliberately **once per process**:
`DisplayChoiceViewModel.countUpKeepsBrightness` is set once and never reset, because the right answer
is a property of the day rather than of the race. That is correct for the product and it is the one
thing standing between this run and AC 4, which wants the count-up's own panel cost measured. A cost
needs two arms, and one process only ever produces one.

So the day is four blocks with the app **force-stopped between them**, which is what starts a fresh
process and re-asks both questions:

| Block | Races | Count-up answer |
|---|---|---|
| 1 | 1–4 | **Keep bright** |
| 2 | 5–7 | **Dim** |
| 3 | 8–11 | **Keep bright** |
| 4 | 12–14 | **Dim** |

Alternating rather than split in half, because a battery gauge does not fall linearly: two arms taken
from the top and the bottom of the curve are not comparable, and `docs/battery-baseline.md` already
records that confound spoiling the watch's two runs. Interleaved, each arm samples both halves of the
day.

"Dim" may be given either by tapping **Dim** or by letting the fifteen-second dwell expire — silence
dims, by design. Both are the same answer and the instrument records which one arrived.

### The configuration to record (AC 2)

The claim is only reproducible if the conditions are on the record with the numbers, so the
instrument writes all of these itself rather than relying on anyone's memory:

- phone model, Android release and SDK level;
- the app's `versionName`, `versionCode` and build type, so the artefact under test is named;
- the launch **Screen for today** answers — screen-on and full-brightness, independently;
- the count-up brightness answer for each block, and whether it was tapped or timed out;
- whether the pair link was up (recorded as *no link* until the Data Layer story lands, which is what
  it will be on this run);
- the sequence loaded for every race;
- battery status at every sample, so a charger plugged in by accident is visible rather than silently
  flattering the result.

Two things the app cannot read and the owner writes down by hand, per block: **sky** (overcast /
bright / direct sun) and rough **air temperature**. Both matter to an OLED and both are named in *What
this cannot see* as limits on the count-up answer.

## The instrument

`DayJournal` in the phone module: an append-only text journal on the device, one record per line,
inert unless armed.

### What it records

| Record | When | Why it is there |
|---|---|---|
| `session` | process start | Names the phone, the build and the journal's own version — the artefact under test |
| `battery` | every `ACTION_BATTERY_CHANGED` | Level, scale, **charge counter in µAh**, status, temperature |
| `display` | the effective display properties change | Screen-on and brightness as applied, and the count-up answer that narrowed them |
| `race` | load, start, gun, end race, stop | With the sequence id, and at start the **whole expected cue schedule** |
| `cue` | every cue the engine fires | Its label, its intended offset, and how late it actually landed |

**The charge counter is the reason this story is worth instrumenting at all.**
`BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER` reports remaining charge in microamp-hours, which on
a 5000 mAh phone is roughly five million counts against the 100 the percentage gives. The watch
baseline in `docs/battery-baseline.md` was stuck reading whole percents off a watch face, which is why
it could report that a sequence costs "at most 5 points" and could not separate a count-up hour from
the countdown that preceded it. This can.

**Cue lateness costs no extra clock.** A cue's intended moment *is* its offset from the gun, and the
engine's `remainingMs` is live and monotonic-anchored at the instant the cue fires, so lateness is
`offsetMs - remainingMs` computed entirely inside one device clock. Cairn's
`a-scenario-can-relax-to-fit-its-instrument` records a comparison across two clocks turning a 51 ms
result into a 1.2-second one; there is no cross-clock subtraction anywhere in this instrument.

### It adds no wake-ups of its own

Battery sampling rides `ACTION_BATTERY_CHANGED`, a broadcast the system is already sending. Nothing
here polls, schedules an alarm, or holds a wake lock, so the instrument does not create the drain it
is measuring. Everything else it records is an event that was going to happen anyway.

Records are buffered in memory and written on a background thread, never on the cue path. That is not
tidiness: the cue path is the one thing in this app with a deadline, and a synchronous file write at
the moment of a gun would be an instrument that changes the thing it measures.

### Arming it, and proving it is armed

The journal is compiled into every build and does nothing until the log tag is turned on:

```
adb shell setprop log.tag.RaceDayJournal DEBUG
```

Read once, at construction, so an armed run pays one boolean and a race never re-checks. The pattern
and its trap are cairn's `android-forcing-a-failure-you-cannot-observe`: **`setprop log.tag.X ""` is
rejected outright and silently leaves the old value**, so to disarm use `ASSERT`, never an empty
string.

**An unarmed journal produces an empty file, and an empty file is what a day with nothing wrong also
produces.** That is the failure this whole section exists to prevent, so arming is not finished until
the instrument has been seen to work:

```
adb shell setprop log.tag.RaceDayJournal DEBUG
adb shell am force-stop io.github.sailordave17.racetimer
# launch the app, tap through the display surface, start and stop one race
python tools/parse-start-day.py --preflight
```

`--preflight` pulls the journal, refuses an empty or stale one, and prints the session record it
found. Do that at the dock, with the laptop, before the phone goes anywhere. A day run against an
unarmed build cannot be recovered.

### Retrieval

The journal is written to the app's external files directory, which needs no permission and is
readable over adb from a release build as well as a debug one:

```
adb pull /sdcard/Android/data/io.github.sailordave17.racetimer/files/race-day-journal.log
```

On a debug build `adb shell run-as io.github.sailordave17.racetimer cat files/race-day-journal.log`
also works. Note the path is the **applicationId**, which the phone shares with the watch — not the
`com.racetimer.phone` namespace. `docs/process-kill-test.md` records the same trap.

### The parse

```
python tools/parse-start-day.py race-day-journal.log --floor-pct 20 --sequences 14 --hours 6
```

The scenario's three numbers are **arguments, not defaults**: the script hard-codes none of them, so
this document is their only home and a change here cannot leave a second copy behind. The script
reports the configuration, the drain and its rate, the µAh per minute attributed to each phase, the
bright-versus-dim count-up comparison, every cue against the schedule recorded at that race's start,
and whether the floor held. It exits non-zero if the scenario was not met.

`python tools/parse-start-day.py --selftest` runs its known-answer cases and is wired into CI, so the
arithmetic above is not trusted on the strength of having been read.

## What this cannot see

Written before the run, so it cannot be trimmed to fit a result.

- **Ambient light is the confound on the count-up answer, and the instrument is blind to it.** With
  the brightness override released the panel falls back to the system's own brightness, which under a
  bright sky auto-brightness may drive nearly as hard as the override does. If the two arms come back
  close, *"the override is cheap"* and *"the sky was doing the override's job"* are the same
  observation and this instrument cannot separate them. The per-block sky note is the only thing that
  will distinguish them, and it is a human writing on paper. **A close result under direct sun is not
  an answer**, and the re-run condition is a day under overcast.
- **The charge counter is a gauge, not a coulometer bench.** It is far finer than the percentage and
  it is still the phone's own estimate, subject to its own smoothing. Treat a difference of a few
  percent between arms as noise.
- **One phone, one day, one battery, at whatever state of health it is in.** Nothing here is an
  average and nothing transfers to another device.
- **No adb during the day.** An attached session can itself suppress deep sleep and change what is
  being measured, which `docs/battery-baseline.md` and `docs/timing-accuracy.md` both record for the
  watch. The journal exists precisely so nothing has to be attached.
- **A day is not the worst day.** Fourteen sequences under one sky. A general recall, a postponement
  ashore with the app running, or a cold bright day are all harsher and none is covered.
- **The instrument reports cues the engine fired, not sounds the boat heard.** A cue that dispatched
  on time and was swallowed by the audio stack is a delivered cue here. That question is the watch's
  `docs/timing-accuracy.md` and `docs/dnd-haptics-recheck.md`, and this run does not answer it.

## How to run the day

1. **The night before**: charge to 100%. Install the build under test and record which it is; the
   journal will record it too, and the two must agree.
2. **At the dock**: arm the journal and run the preflight above until it prints a session record.
   Unplug. Note the time.
3. **Each block**: launch the app, answer *Screen for today* with **screen-on on, full brightness
   on** — the same answers every block, so the blocks differ in one variable only. Run the block's
   races. At the first gun of the block, give the count-up answer the table calls for.
4. **Between blocks**: force-stop the app from the launcher, then relaunch. Write down the sky and
   the temperature for the block just finished.
5. **At the end**: note the time and the battery percentage off the phone, before plugging in. Those
   two readings are the outside check on the journal's own arithmetic — an instrument agreeing with
   itself is not evidence.
6. **Back at the machine**: pull the journal and run the parse.

## Results

**Not yet run.** This section is where the numbers go, and nothing above should be read as though
they exist.

When the run happens, add a dated row rather than replacing this section — two dated results that
disagree are worth more than one that has been overwritten, which is the same rule
`docs/battery-baseline.md` closes on.

The run has to answer four things, and the fourth is a question rather than a number:

1. Did the battery stay above the floor, with zero missed cues?
2. What is the drain rate, and what does it project to for a longer day?
3. What does a count-up minute cost bright, and what does it cost dimmed?
4. **If the difference is small, was #279 worth building at all?** That is
   [#216](https://github.com/SailorDave17/race-timer/issues/216)'s AC 5, and it is deliberately a
   return route rather than a formality: #279 chose its shape *before* this measurement existed, and
   a count-up that turns out to be cheap leaves the officer interrupted at the first gun of every day
   for nothing. Removing the prompt is on the table when the answer comes back.

## Its relationship to the watch's baseline (#16)

[#16](https://github.com/SailorDave17/race-timer/issues/16) asked the same question of the watch and
is answered by `docs/battery-baseline.md`. **This run does not discharge it and nothing here should be
read as covering the watch** — different hardware, a different display policy, a different battery,
and a different app.

The relationship runs both ways and it is a template, not a dependency. **No duplicate issue is filed
in either direction.**

- What this file takes from the watch: its *Limits of these measurements* and *How to re-run this*
  sections are the shape the two sections above are written in, and the two confounds it names —
  surface charge after a full charge, and two arms taken from different points on the curve — are why
  this scenario interleaves its blocks and starts the day from a full charge and an unplugged wait.
- What this file offers back: the watch's instrument was the percentage on a watch face, and its own
  limits section says plainly that this made the comparison it wanted — a count-up hour against no
  count-up hour — impossible. The recorder here is a phone module, so it does not transfer as code;
  the **method** does, and if the watch's numbers are ever re-taken this is the design to copy. #16 is
  closed, so that would be a new story rather than a reopening.

*(#216's AC 3 was written on 2026-08-13, when #16 was open and unmeasured; it closed COMPLETED on
2026-08-16. The criterion's substance — record the method reusably, file no duplicate, do not claim to
discharge the watch — is what this section does, and only its premise clause aged.)*
