#!/usr/bin/env python3
"""Turn a DayJournal into the numbers #216 asks for.

The scenario, the procedure and the limits are `docs/start-day-battery.md`. This file is the
arithmetic and nothing else: it argues no product question and it hard-codes none of the scenario's
numbers, which arrive as required arguments so the document stays their only home.

Three modes:

    .github/scripts/parse-start-day.py <journal> --floor-pct N --sequences N --hours N
    .github/scripts/parse-start-day.py --preflight            # pull the journal and prove the instrument is armed
    .github/scripts/parse-start-day.py --selftest             # known-answer cases; wired into CI

Exit status is the verdict: 0 only when the scenario was met.

Why the selftest exists at all: a start day is unrepeatable, so the parse gets exactly one chance at
a real journal. A green run against a journal nobody can produce twice is not a thing to discover a
bug in. Every case below states its expected outcome before it runs.
"""

from __future__ import annotations

import argparse
import subprocess
import sys
from dataclasses import dataclass, field

# The journal format this parser understands. DayJournal.JOURNAL_FORMAT is the other half of the
# handshake, and a mismatch is refused rather than guessed at: a field this cannot find would
# otherwise become a plausible wrong number on a day nobody can re-run.
KNOWN_FORMAT = "1"

PACKAGE = "io.github.sailordave17.racetimer"
JOURNAL_FILE = "race-day-journal.log"
DEVICE_PATH = "/sdcard/Android/data/{}/files/{}".format(PACKAGE, JOURNAL_FILE)

# android.os.BatteryManager. Written out because the parser runs nowhere near Android.
STATUS_CHARGING = 2
STATUS_FULL = 5


class JournalError(Exception):
    """The journal cannot be read as a journal. Never a finding about the day."""


@dataclass
class Record:
    iso: str
    elapsed: int
    kind: str
    fields: dict

    def num(self, key, default=None):
        raw = self.fields.get(key)
        if raw is None:
            return default
        try:
            return int(raw)
        except ValueError:
            return default


@dataclass
class Span:
    """A stretch of the day in one phase, on the monotonic clock."""

    kind: str  # countdown | countup | idle
    start: int
    end: int
    bright: bool
    seq: str = ""
    uah: int = 0  # charge consumed, filled in by attribute_charge

    @property
    def minutes(self):
        return (self.end - self.start) / 60000.0


@dataclass
class Race:
    seq: str
    start: int
    schedule: list
    restored: bool
    fired: list = field(default_factory=list)
    end: int = None
    gun_at: int = None

    @property
    def missing(self):
        if self.restored:
            # A restored race resumes mid-sequence, so the cues already spent before the process
            # died are absent by construction. Counting them as misses would report an instrument
            # working correctly as a product failure.
            return []
        return [off for off in self.schedule if off not in self.fired]


def parse_line(line):
    """One record, or None for a blank line.

    Deliberately split-based rather than regular expressions: DayJournal sanitises every value to
    contain no whitespace and no `=`, so there is nothing here for an escaping scheme to get wrong.
    """
    line = line.strip()
    if not line:
        return None
    parts = line.split(" ")
    if len(parts) < 3:
        raise JournalError("malformed record: {!r}".format(line))
    try:
        elapsed = int(parts[1])
    except ValueError:
        raise JournalError("record has no monotonic stamp: {!r}".format(line))
    fields = {}
    for token in parts[3:]:
        if "=" not in token:
            raise JournalError("malformed field {!r} in record: {!r}".format(token, line))
        key, value = token.split("=", 1)
        fields[key] = value
    return Record(iso=parts[0], elapsed=elapsed, kind=parts[2], fields=fields)


def parse_records(text):
    records = []
    for line in text.splitlines():
        record = parse_line(line)
        if record is not None:
            records.append(record)
    if not records:
        # The whole reason this check exists. An unarmed build writes an empty file, and so does a
        # perfect day with nothing to report — the two are byte-identical, and the reassuring
        # reading is the wrong one. See docs/start-day-battery.md, "Arming it".
        raise JournalError(
            "the journal is empty. That is what an UNARMED build produces, and it is "
            "indistinguishable from a day that went perfectly. Arm it "
            "(setprop log.tag.RaceDayJournal DEBUG), re-run the preflight, and do not read this as "
            "a clean run."
        )
    return records


def check_format(records):
    sessions = [r for r in records if r.kind == "session"]
    if not sessions:
        raise JournalError("no session record: this journal does not name the build it came from")
    for session in sessions:
        seen = session.fields.get("journal")
        if seen != KNOWN_FORMAT:
            raise JournalError(
                "journal format {!r}, this parser understands {!r}".format(seen, KNOWN_FORMAT)
            )
    return sessions


def check_monotonic(records):
    """Refuse a journal whose monotonic clock went backwards.

    `elapsedRealtime` restarts at a reboot, so a journal spanning one would silently produce
    negative durations and attribute a day's charge to a few seconds. There is no repair here worth
    guessing at: say so and stop.
    """
    for previous, current in zip(records, records[1:]):
        if current.elapsed < previous.elapsed:
            raise JournalError(
                "the monotonic clock went backwards at {} ({} -> {}). The phone rebooted mid-day; "
                "this journal spans two boots and cannot be read as one run.".format(
                    current.iso, previous.elapsed, current.elapsed
                )
            )


def build_spans(records):
    """Cut the day into countdown / count-up / idle stretches, carrying the applied brightness."""
    spans = []
    bright = False
    phase = "idle"
    seq = ""
    mark = records[0].elapsed

    def close(at):
        nonlocal mark
        if at > mark:
            spans.append(Span(kind=phase, start=mark, end=at, bright=bright, seq=seq))
        mark = at

    for record in records:
        if record.kind == "display":
            # A brightness change cuts the span, so charge either side is attributed to the value
            # that was actually applied rather than to whichever happened to be last.
            close(record.elapsed)
            bright = record.fields.get("bright") == "true"
        elif record.kind == "race_start":
            close(record.elapsed)
            phase, seq = "countdown", record.fields.get("seq", "")
        elif record.kind == "cue" and record.fields.get("gun") == "1":
            close(record.elapsed)
            phase = "countup"
        elif record.kind in ("race_end", "race_stop"):
            close(record.elapsed)
            phase, seq = "idle", ""
    close(records[-1].elapsed)
    return spans


def attribute_charge(records, spans):
    """Split each battery interval's consumption across the spans it overlaps, by time.

    Consumption is `previous uah - current uah`, because the counter reports charge REMAINING. An
    interval that gained charge is left at zero here and reported separately by the charger check —
    a day that charged is not a day whose rates mean anything.
    """
    samples = [r for r in records if r.kind == "battery" and r.num("uah") is not None]
    for previous, current in zip(samples, samples[1:]):
        used = previous.num("uah") - current.num("uah")
        window = current.elapsed - previous.elapsed
        if used <= 0 or window <= 0:
            continue
        for span in spans:
            overlap = min(span.end, current.elapsed) - max(span.start, previous.elapsed)
            if overlap > 0:
                span.uah += used * overlap / window
    return samples


def build_races(records):
    races = []
    for record in records:
        if record.kind == "race_start":
            raw = record.fields.get("schedule", "")
            schedule = [int(x) for x in raw.split(":") if x]
            races.append(
                Race(
                    seq=record.fields.get("seq", ""),
                    start=record.elapsed,
                    schedule=schedule,
                    restored=record.fields.get("restored") == "1",
                )
            )
        elif record.kind == "cue" and races:
            offset = record.num("offsetMs")
            if offset is not None:
                races[-1].fired.append(offset)
            if record.fields.get("gun") == "1":
                races[-1].gun_at = record.elapsed
        elif record.kind in ("race_end", "race_stop") and races:
            races[-1].end = record.elapsed
    return races


def rate(spans, kind, bright=None):
    """Microamp-hours per minute across every span of one kind, or None where there are none."""
    chosen = [s for s in spans if s.kind == kind and (bright is None or s.bright == bright)]
    minutes = sum(s.minutes for s in chosen)
    if minutes <= 0:
        return None
    return sum(s.uah for s in chosen) / minutes


def percent(record):
    level, scale = record.num("level", -1), record.num("scale", -1)
    if level < 0 or scale <= 0:
        return None
    return 100.0 * level / scale


def analyse(records, floor_pct, sequences, hours):
    """Everything the report says, as data. Pure, so the selftest can drive it."""
    sessions = check_format(records)
    check_monotonic(records)

    spans = build_spans(records)
    samples = attribute_charge(records, spans)
    races = build_races(records)

    percents = [p for p in (percent(s) for s in samples) if p is not None]
    charging = [
        s for s in samples
        if s.num("status") in (STATUS_CHARGING, STATUS_FULL) or s.num("plugged", 0) not in (0, -1)
    ]
    guns = [r for r in races if r.gun_at is not None]
    span_hours = (records[-1].elapsed - records[0].elapsed) / 3600000.0
    missed = {r.seq + "@" + str(r.start): r.missing for r in races if r.missing}

    failures = []
    if not percents:
        failures.append("no battery sample carried a readable level")
    elif min(percents) < floor_pct:
        failures.append(
            "battery reached {:.1f}%, below the {:g}% floor".format(min(percents), floor_pct)
        )
    if missed:
        failures.append("{} race(s) missed a cue".format(len(missed)))
    if charging:
        failures.append(
            "{} sample(s) show the phone on charge — the run was not unplugged "
            "throughout, so no rate here is a rate".format(len(charging))
        )
    if len(guns) < sequences:
        failures.append("{} sequences reached the gun, the scenario asks for {}".format(len(guns), sequences))
    if span_hours < hours:
        failures.append("the run spans {:.2f} h, the scenario asks for {}".format(span_hours, hours))

    return {
        "sessions": sessions,
        "spans": spans,
        "races": races,
        "samples": samples,
        "percents": percents,
        "charging": charging,
        "guns": guns,
        "span_hours": span_hours,
        "missed": missed,
        "failures": failures,
    }


def report(result, records, floor_pct, sequences, hours, out=sys.stdout):
    def line(text=""):
        print(text, file=out)

    session = result["sessions"][0]
    line("Configuration")
    line("  phone       {} ({})".format(session.fields.get("model", "?"), session.fields.get("device", "?")))
    line("  android     {} / SDK {}".format(session.fields.get("release", "?"), session.fields.get("sdk", "?")))
    line("  build       {} ({})".format(session.fields.get("versionName", "?"), session.fields.get("versionCode", "?")))
    line("  blocks      {} process(es)".format(len(result["sessions"])))
    models = {s.fields.get("model") for s in result["sessions"]}
    builds = {s.fields.get("versionCode") for s in result["sessions"]}
    if len(models) > 1 or len(builds) > 1:
        line("  WARNING     this journal spans more than one device or build: {} / {}".format(models, builds))
    line()

    line("The day")
    line("  span        {:.2f} h (scenario: {})".format(result["span_hours"], hours))
    line("  guns        {} (scenario: {})".format(len(result["guns"]), sequences))
    if result["percents"]:
        line("  battery     {:.1f}% -> {:.1f}%, low water {:.1f}% (floor: {}%)".format(
            result["percents"][0], result["percents"][-1], min(result["percents"]), floor_pct))
    charge = [s.num("uah") for s in result["samples"] if s.num("uah") is not None]
    if len(charge) >= 2:
        used = charge[0] - charge[-1]
        line("  charge      {:.0f} mAh used".format(used / 1000.0))
        if result["span_hours"] > 0:
            line("  rate        {:.0f} mA average over the run".format(used / 1000.0 / result["span_hours"]))
    line()

    line("Where the charge went (uAh per minute)")
    for kind in ("countdown", "countup", "idle"):
        value = rate(result["spans"], kind)
        minutes = sum(s.minutes for s in result["spans"] if s.kind == kind)
        line("  {:<11} {:>10}   over {:.0f} min".format(
            kind, "{:.0f}".format(value) if value is not None else "n/a", minutes))
    line()

    line("The count-up's own panel cost (#216 AC 4, #279 AC 1)")
    bright = rate(result["spans"], "countup", bright=True)
    dim = rate(result["spans"], "countup", bright=False)
    for label, value in (("bright", bright), ("dimmed", dim)):
        minutes = sum(s.minutes for s in result["spans"]
                      if s.kind == "countup" and s.bright == (label == "bright"))
        line("  {:<11} {:>10}   over {:.0f} min".format(
            label, "{:.0f}".format(value) if value is not None else "n/a", minutes))
    if bright is not None and dim is not None:
        line("  difference  {:>10}   ({:.0f} mAh over a 6 h day of count-up)".format(
            "{:.0f}".format(bright - dim), (bright - dim) * 360 / 1000.0))
        line("  NOTE: a small difference is NOT yet 'the override is cheap'. With the override")
        line("        released the panel falls back to auto-brightness, which under a bright sky")
        line("        drives nearly as hard. Read the per-block sky note before concluding, and")
        line("        see docs/start-day-battery.md, 'What this cannot see'.")
    else:
        line("  Only one arm is present. The day must contain both (see the block table in")
        line("  docs/start-day-battery.md) or AC 4 has no answer.")
    line()

    line("Cues")
    line("  races       {} started, {} reached the gun".format(len(result["races"]), len(result["guns"])))
    line("  missed      {}".format(len(result["missed"])))
    for key, offsets in result["missed"].items():
        line("    {} missing offsets {}".format(key, offsets))
    lateness_report(records, out=out)
    line()

    if result["failures"]:
        line("SCENARIO NOT MET")
        for failure in result["failures"]:
            line("  - {}".format(failure))
    else:
        line("SCENARIO MET: {} sequences over {:.2f} h, floor held, no cue missed.".format(
            len(result["guns"]), result["span_hours"]))
    return 1 if result["failures"] else 0


def lateness_report(records, out=sys.stdout):
    """Worst and typical cue lateness, which is a separate question from whether one was missed."""
    late = sorted(r.num("lateMs", 0) for r in records if r.kind == "cue")
    if not late:
        return
    print("  lateness    median {} ms, worst {} ms, over {} cues".format(
        late[len(late) // 2], late[-1], len(late)), file=out)


def preflight(adb="adb", out=sys.stdout):
    """Prove the instrument is armed, at the dock, before the day.

    Pulls the journal and refuses an empty one. This is the positive control: an unarmed run and a
    clean run produce the same empty file, and only one of them can be recovered.
    """
    try:
        pulled = subprocess.run(
            [adb, "exec-out", "cat", DEVICE_PATH],
            capture_output=True, timeout=60,
        )
    except (OSError, subprocess.SubprocessError) as exc:
        print("could not reach the phone: {}".format(exc), file=out)
        return 2
    text = pulled.stdout.decode("utf-8", "replace")
    try:
        records = parse_records(text)
        sessions = check_format(records)
    except JournalError as exc:
        print("PREFLIGHT FAILED: {}".format(exc), file=out)
        return 1
    latest = sessions[-1]
    print("PREFLIGHT OK", file=out)
    print("  {} records, {} session(s)".format(len(records), len(sessions)), file=out)
    print("  latest session: {} on {} build {}".format(
        latest.iso, latest.fields.get("model", "?"), latest.fields.get("versionName", "?")), file=out)
    kinds = sorted({r.kind for r in records})
    print("  record kinds seen: {}".format(", ".join(kinds)), file=out)
    for needed in ("battery", "cue"):
        if needed not in kinds:
            print("  NOT READY: no {!r} record yet — run one race before leaving".format(needed), file=out)
            return 1
    return 0


# ---------------------------------------------------------------------------
# Selftest
# ---------------------------------------------------------------------------

def _journal(*lines):
    return "\n".join(lines) + "\n"


def _session(elapsed=0, fmt="1"):
    return ("2026-08-22T09:00:00Z {} session model=SM-S918U device=dm3q release=15 sdk=36 "
            "versionName=1.4.0 versionCode=42 journal={}").format(elapsed, fmt)


def _battery(elapsed, uah, level=100, status=3, plugged=0):
    return ("2026-08-22T09:00:00Z {} battery level={} scale=100 uah={} status={} plugged={} "
            "tempDeciC=250").format(elapsed, level, uah, status, plugged)


def _display(elapsed, bright):
    return "2026-08-22T09:00:00Z {} display screenOn=true bright={}".format(
        elapsed, "true" if bright else "false")


def _start(elapsed, schedule="180000:120000:0", seq=None, restored=False):
    seq = seq or "scholastic_race_manager"
    text = "2026-08-22T09:00:00Z {} race_start seq={} schedule={}".format(elapsed, seq, schedule)
    return text + " restored=1" if restored else text


def _cue(elapsed, offset, gun=False):
    return "2026-08-22T09:00:00Z {} cue seq=scholastic_race_manager offsetMs={} lateMs=3 gun={} label=1_long".format(
        elapsed, offset, 1 if gun else 0)


def _end(elapsed):
    return "2026-08-22T09:00:00Z {} race_end seq=scholastic_race_manager elapsedMs=600000".format(elapsed)


SELFTEST_CASES = []


def case(name, expect):
    def wrap(fn):
        SELFTEST_CASES.append((name, expect, fn))
        return fn
    return wrap


@case("a well-formed line round-trips, spaces already collapsed by the writer", "pass")
def _c1():
    record = parse_line("2026-08-22T09:00:00Z 1234 cue offsetMs=180000 label=3_long")
    assert record.kind == "cue", record.kind
    assert record.elapsed == 1234, record.elapsed
    assert record.num("offsetMs") == 180000
    assert record.fields["label"] == "3_long"


@case("an empty journal is refused, not read as a clean day", "refuse")
def _c2():
    try:
        parse_records("\n\n")
    except JournalError as exc:
        assert "UNARMED" in str(exc), str(exc)
        return
    raise AssertionError("an empty journal was accepted")


@case("an unknown journal format is refused rather than guessed at", "refuse")
def _c3():
    records = parse_records(_journal(_session(fmt="2")))
    try:
        check_format(records)
    except JournalError as exc:
        assert "format" in str(exc)
        return
    raise AssertionError("a future format was accepted")


@case("a journal spanning a reboot is refused", "refuse")
def _c4():
    records = parse_records(_journal(_session(0), _battery(60000, 5000000), _battery(500, 4990000)))
    try:
        check_monotonic(records)
    except JournalError as exc:
        assert "backwards" in str(exc)
        return
    raise AssertionError("a reboot was accepted as one run")


@case("charge is attributed to the phase that was running, not to the whole day", "pass")
def _c5():
    # 3 min countdown then 10 min count-up. One battery interval per phase, so the arithmetic is
    # checkable by hand: 3000 uAh over 3 min = 1000/min; 20000 uAh over 10 min = 2000/min.
    records = parse_records(_journal(
        _session(0),
        _display(0, True),
        _battery(0, 5000000),
        _start(0),
        _cue(180000, 0, gun=True),
        _battery(180000, 4997000),
        _battery(780000, 4977000),
        _end(780000),
    ))
    spans = build_spans(records)
    attribute_charge(records, spans)
    assert abs(rate(spans, "countdown") - 1000) < 1, rate(spans, "countdown")
    assert abs(rate(spans, "countup") - 2000) < 1, rate(spans, "countup")


@case("the bright and dim count-up arms are separated", "pass")
def _c6():
    # Two count-ups of 10 min each, one bright at 2000 uAh/min and one dimmed at 1000.
    records = parse_records(_journal(
        _session(0),
        _display(0, True),
        _battery(0, 5000000),
        _start(0),
        _cue(0, 0, gun=True),
        _battery(600000, 4980000),
        _end(600000),
        _display(600000, False),
        _start(600000),
        _cue(600000, 0, gun=True),
        _battery(1200000, 4970000),
        _end(1200000),
    ))
    spans = build_spans(records)
    attribute_charge(records, spans)
    assert abs(rate(spans, "countup", bright=True) - 2000) < 1, rate(spans, "countup", bright=True)
    assert abs(rate(spans, "countup", bright=False) - 1000) < 1, rate(spans, "countup", bright=False)


@case("a cue in the schedule that never fired is a miss", "refuse")
def _c7():
    records = parse_records(_journal(
        _session(0), _battery(0, 5000000), _start(0, "180000:120000:0"),
        _cue(0, 180000), _cue(60000, 0, gun=True), _battery(60000, 4999000), _end(60000),
    ))
    races = build_races(records)
    assert races[0].missing == [120000], races[0].missing


@case("a restored race is not blamed for the cues it resumed past", "pass")
def _c8():
    records = parse_records(_journal(
        _session(0), _battery(0, 5000000), _start(0, "180000:120000:0", restored=True),
        _cue(0, 0, gun=True), _battery(60000, 4999000), _end(60000),
    ))
    races = build_races(records)
    assert races[0].restored is True
    assert races[0].missing == [], races[0].missing


@case("a phone that went on charge fails the run", "refuse")
def _c9():
    records = parse_records(_journal(
        _session(0), _battery(0, 5000000), _start(0, "0"), _cue(0, 0, gun=True),
        _battery(60000, 5001000, status=STATUS_CHARGING, plugged=1), _end(60000),
    ))
    result = analyse(records, floor_pct=20, sequences=1, hours=0)
    assert any("on charge" in f for f in result["failures"]), result["failures"]


@case("a run that breaches the floor fails", "refuse")
def _c10():
    records = parse_records(_journal(
        _session(0), _battery(0, 5000000, level=100), _start(0, "0"), _cue(0, 0, gun=True),
        _battery(60000, 900000, level=18), _end(60000),
    ))
    result = analyse(records, floor_pct=20, sequences=1, hours=0)
    assert any("below the 20% floor" in f for f in result["failures"]), result["failures"]


@case("a short day fails on both counts it is short on", "refuse")
def _c11():
    records = parse_records(_journal(
        _session(0), _battery(0, 5000000, level=90), _start(0, "0"), _cue(0, 0, gun=True),
        _battery(60000, 4999000, level=89), _end(60000),
    ))
    result = analyse(records, floor_pct=20, sequences=14, hours=6)
    assert any("sequences reached the gun" in f for f in result["failures"]), result["failures"]
    assert any("spans" in f for f in result["failures"]), result["failures"]


@case("a clean minimal day passes", "pass")
def _c12():
    records = parse_records(_journal(
        _session(0), _display(0, True), _battery(0, 5000000, level=100),
        _start(0, "180000:0"), _cue(0, 180000), _cue(180000, 0, gun=True),
        _battery(780000, 4900000, level=95), _end(780000),
    ))
    result = analyse(records, floor_pct=20, sequences=1, hours=0)
    assert result["failures"] == [], result["failures"]


@case("an interval straddling the gun is split between the two phases by time", "pass")
def _c13():
    # One battery interval covering 2 min of countdown and 8 min of count-up, 10000 uAh over the
    # ten minutes. Split by time that is 2000 to the countdown and 8000 to the count-up; attributed
    # whole to each overlapping span it would be 10000 and 10000. The two are far enough apart that
    # this case cannot pass on the wrong arithmetic.
    #
    # It exists because the mutation that removes the split reddened NOTHING against the cases
    # above: every interval there sat wholly inside one span, so the proportional half of
    # attribute_charge had no case that could fail. Found by predicting the red count first.
    records = parse_records(_journal(
        _session(0), _display(0, True), _battery(0, 5000000),
        _start(0, "120000:0"), _cue(0, 120000), _cue(120000, 0, gun=True),
        _battery(600000, 4990000), _end(600000),
    ))
    spans = build_spans(records)
    attribute_charge(records, spans)
    countdown = [s for s in spans if s.kind == "countdown"]
    countup = [s for s in spans if s.kind == "countup"]
    assert abs(sum(s.uah for s in countdown) - 2000) < 1, sum(s.uah for s in countdown)
    assert abs(sum(s.uah for s in countup) - 8000) < 1, sum(s.uah for s in countup)


@case("a charger that reports 'discharging' is still a charger", "refuse")
def _c14():
    # A phone on a weak supply can be plugged in and still losing charge, so `status` alone does
    # not answer the question the run needs answered. Without this case the `plugged` half of the
    # check has nothing that could fail it.
    records = parse_records(_journal(
        _session(0), _battery(0, 5000000), _start(0, "0"), _cue(0, 0, gun=True),
        _battery(60000, 4999000, status=3, plugged=2), _end(60000),
    ))
    result = analyse(records, floor_pct=20, sequences=1, hours=0)
    assert any("on charge" in f for f in result["failures"]), result["failures"]


def selftest(out=sys.stdout):
    failed = 0
    for name, expect, fn in SELFTEST_CASES:
        try:
            fn()
            print("  ok   [{}] {}".format(expect, name), file=out)
        except AssertionError as exc:
            failed += 1
            print("  FAIL [{}] {}: {}".format(expect, name, exc), file=out)
        except Exception as exc:  # noqa: BLE001 - a crash is a failure, and it must be named
            failed += 1
            print("  FAIL [{}] {}: unexpected {}: {}".format(expect, name, type(exc).__name__, exc), file=out)
    print("{} case(s), {} failed".format(len(SELFTEST_CASES), failed), file=out)
    # A run that printed fewer cases than it has is a harness that stopped, not a clean pass.
    if len(SELFTEST_CASES) < 14:
        print("FAIL: fewer cases than expected — this harness did not finish", file=out)
        return 1
    return 1 if failed else 0


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("journal", nargs="?", help="the pulled race-day-journal.log")
    parser.add_argument("--floor-pct", type=float, help="the scenario's battery floor")
    parser.add_argument("--sequences", type=int, help="the scenario's sequence count")
    parser.add_argument("--hours", type=float, help="the scenario's unplugged span")
    parser.add_argument("--preflight", action="store_true", help="pull the journal and prove it is armed")
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--selftest", action="store_true")
    args = parser.parse_args(argv)

    if args.selftest:
        return selftest()
    if args.preflight:
        return preflight(adb=args.adb)
    if not args.journal:
        parser.error("a journal file is required (or --preflight / --selftest)")
    for name in ("floor_pct", "sequences", "hours"):
        if getattr(args, name) is None:
            parser.error(
                "--{} is required: the scenario's numbers live in docs/start-day-battery.md and "
                "this script deliberately keeps no copy of them".format(name.replace("_", "-"))
            )

    with open(args.journal, "r", encoding="utf-8") as handle:
        text = handle.read()
    try:
        records = parse_records(text)
        result = analyse(records, args.floor_pct, args.sequences, args.hours)
    except JournalError as exc:
        print("CANNOT READ THIS JOURNAL: {}".format(exc))
        return 2
    return report(result, records, args.floor_pct, args.sequences, args.hours)


if __name__ == "__main__":
    sys.exit(main())
