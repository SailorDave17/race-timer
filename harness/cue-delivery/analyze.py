"""Analyse one race captured by race.sh for the phone cue-delivery measurement (#210).

    python analyze.py <race-dir> [--shift-ms N]

Procedure, instrument and the measured baseline: docs/phone-cue-delivery.md. Three readings per race:

- AUDIO, heard or not, per cue. The mic recording is band-passed to each voice's own pitch (blast,
  sync tick, gun), reduced to a 20 ms RMS series, and each issued cue (a `ToneManager: cue` line, on
  the phone's clock) is mapped onto it via the measured clock offset plus the mic's start latency
  (`--shift-ms`). A cue is heard when its own band peaks above that band's line in the cue's window.
  Each race sets its own lines from its own silence and tests them on held-out silence; see `main`.
- HAPTIC, per cue, from `dumpsys vibrator_manager`: the record's status (`finished`, `ignored_app_ops`).
- PLAYER, from the 1 s `dumpsys audio` sampler: this app's player usage, state and `mutedState`.

Needs ffmpeg on PATH. Standard library only.
"""
import datetime as dt
import re
import subprocess
import sys
from pathlib import Path

PKG = "io.github.sailordave17.racetimer"
WIN_MS = 20
# The floor under every band's line. Calibrated on #210's quiet-room races: real cues read -27..-47 dB
# in their own band, silence at most -61.
HEARD_DB = -55.0

BANDS = {
    # Each voice has its own pitch (shared CueWaveform): a blast warbles 3700/4000 Hz, a sync tick is
    # DTMF 697+1209, the gun DTMF 941+1633. Each is read around its UPPER partial, which clears speech
    # and TV energy better than the lower one. A blast-only band was tried first and read every
    # short tick as missing — they are a different pitch, not a quieter blast.
    "blast": "highpass=f=3300,highpass=f=3300,lowpass=f=4400,lowpass=f=4400",
    "tick": "bandpass=f=1209:width_type=h:w=120,bandpass=f=1209:width_type=h:w=120",
    "gun": "bandpass=f=1633:width_type=h:w=120,bandpass=f=1633:width_type=h:w=120",
}


def kv(path: Path) -> dict:
    out = {}
    for line in path.read_text(errors="replace").splitlines():
        for m in re.finditer(r"(\w+)=(-?\d+)", line):
            out[m.group(1)] = int(m.group(2))
    return out


def voice_of(dur_ms: int) -> str:
    return "tick" if dur_ms <= 130 else "gun" if dur_ms >= 2500 else "blast"


def band_series(wav: Path, band: str) -> list[float]:
    """20 ms RMS level (dBFS) of the recording band-passed to one voice's pitch."""
    af = (
        f"{BANDS[band]},"
        f"asetnsamples=n={48 * WIN_MS}:p=0,astats=metadata=1:reset=1,"
        "ametadata=print:key=lavfi.astats.Overall.RMS_level:file=-"
    )
    res = subprocess.run(
        ["ffmpeg", "-hide_banner", "-loglevel", "error", "-i", str(wav), "-af", af, "-f", "null", "-"],
        capture_output=True, text=True, check=True,
    )
    levels = []
    for line in res.stdout.splitlines():
        if line.startswith("lavfi.astats.Overall.RMS_level="):
            v = line.split("=", 1)[1]
            levels.append(-120.0 if v in ("-inf", "inf", "nan") else float(v))
    return levels


def issued_cues(logcat: Path) -> list[tuple[int, int]]:
    """(phone_epoch_ms, duration_ms) per cue. Duration from the next `delivered` line when present."""
    cues = []
    for line in logcat.read_text(errors="replace").splitlines():
        m = re.match(r"\s*(\d+)\.(\d{3})\s.*ToneManager: (cue lateMs|delivered (\d+) frames = (\d+)ms)", line)
        if not m:
            continue
        t = int(m.group(1)) * 1000 + int(m.group(2))
        if m.group(3).startswith("cue"):
            cues.append([t, None])
        elif cues and cues[-1][1] is None:
            cues[-1][1] = int(m.group(5))
    return [(t, d if d is not None else 400) for t, d in cues]


def auto_shift(blast: list[float], rel: list[tuple[int, int]]) -> tuple[int, int]:
    """Best mic start latency by aligning blast-band onsets to cue times. Only meaningful on a race
    that delivered: with nothing to align to it wanders (measured: 2040 ms on the DND race)."""
    thr = max(HEARD_DB, sorted(blast)[len(blast) // 2] + 15.0)
    starts = [i * WIN_MS for i in range(1, len(blast)) if blast[i] >= thr > blast[i - 1]]

    def score(shift: int) -> int:
        return sum(1 for r, _ in rel if any(-60 <= s - (r - shift) <= 250 for s in starts))

    best = max(range(-500, 2501, 10), key=score)
    return best, score(best)


def vib_records(vib: Path, t0_phone_ms: int, t1_phone_ms: int) -> list[tuple[int, str, str]]:
    """(phone_epoch_ms, status, usage) for our package's vibrations in the window, de-duplicated:
    the dump carries each vibration in up to two sections (Recent, Aggregated)."""
    day = dt.datetime.fromtimestamp(t0_phone_ms / 1000)
    recs = {}
    for line in vib.read_text(errors="replace").splitlines():
        if PKG not in line:
            continue
        m = re.match(r"\s*(?:\d\d-\d\d )?(\d\d):(\d\d):(\d\d)\.(\d{3})", line)
        if not m:
            continue
        t = day.replace(hour=int(m.group(1)), minute=int(m.group(2)), second=int(m.group(3)),
                        microsecond=int(m.group(4)) * 1000)
        ms = int(t.timestamp() * 1000)
        if not t0_phone_ms - 2000 <= ms <= t1_phone_ms:
            continue
        s = re.search(r"\|\s*(finished|ignored_\w+|cancelled_\w+|\w+)\s*\|\s*duration", line)
        u = re.search(r"usage: (\w+)", line)
        recs[ms] = (s.group(1) if s else "?", u.group(1) if u else "?")
    return sorted((ms, st, us) for ms, (st, us) in recs.items())


def main() -> None:
    d = Path(sys.argv[1])
    args = sys.argv[2:]
    clock, mic = kv(d / "clock.txt"), kv(d / "mic.txt")
    off = clock["phone_minus_local_ms"]
    series = {b: band_series(d / "mic.wav", b) for b in BANDS}

    cues = issued_cues(d / "logcat.txt")
    # Cue time on the recording, before the mic's start latency: phone -> local -> since mic start.
    rel = [(t - off - mic["mic_start_local"], dur) for t, dur in cues]
    if "--shift-ms" in args:
        shift_ms = int(args[args.index("--shift-ms") + 1])
        print(f"[{d.name}] issued cues={len(cues)}  mic latency pinned at {shift_ms} ms")
    else:
        shift_ms, aligned = auto_shift(series["blast"], rel)
        print(f"[{d.name}] issued cues={len(cues)}  mic latency auto={shift_ms} ms ({aligned} cues aligned)"
              + ("  -- FEW ALIGNED: pin --shift-ms from a race that delivered" if aligned < len(cues) // 2 else ""))
    exp = [r - shift_ms for r, _ in rel]

    def level(band: str, at: int, dur: int) -> float:
        # The loudest 20 ms in the cue's own window, in its own voice's band. A reading relative to
        # the window before was tried first and FAILED its negative control: the MV7 gates silence to
        # digital zero, so any room sound stands >= 10 dB clear of -120.
        seg = series[band][max(0, (at - 20) // WIN_MS):max(0, (at + min(dur, 400) + 60) // WIN_MS)]
        return max(seg) if seg else -120.0

    # Phantom cues: moments the sequence plays nothing (>= 3 s from any real cue). The +20 s set
    # CALIBRATES each band's line from this race's own background (loudest phantom + 6 dB, never below
    # HEARD_DB); the independent +27 s set TESTS that line and must produce zero hits. A single fixed
    # line gave 25 false hits in #210's focus race, where another app's music raised every band.
    def phantoms_at(delta: int) -> list[int]:
        return [e + delta for e in exp if all(abs(e + delta - r) > 3_000 for r in exp)]

    cal, ctl = phantoms_at(20_000), phantoms_at(27_000)
    line = {b: max(HEARD_DB, max((level(b, p, 400) for p in cal), default=-120.0) + 6.0) for b in BANDS}

    heard, by_voice, margins = 0, {}, []
    for (t, dur), at in zip(cues, exp):
        v = voice_of(dur)
        lv = level(v, at, dur)
        ok = lv >= line[v]
        heard += ok
        margins.append(lv - line[v])
        tot, hit = by_voice.get(v, (0, 0))
        by_voice[v] = (tot + 1, hit + ok)
        stamp = dt.datetime.fromtimestamp(t / 1000).strftime("%H:%M:%S.%f")[:-3]
        print(f"    {stamp} (phone) {v:5s} {dur:5d}ms  level={lv:6.1f} dB  line={line[v]:6.1f}  "
              f"{'heard' if ok else '-- NOT HEARD --'}")
    ctl_margin = [level(b, p, 400) - line[b] for p in ctl for b in BANDS]
    print(f"  AUDIO heard {heard} of {len(cues)}  by voice={by_voice}  lines={ {b: round(x, 1) for b, x in line.items()} }")
    print(f"  negative control: {len(ctl)} held-out phantoms x {len(BANDS)} bands, "
          f"false hits={sum(1 for x in ctl_margin if x >= 0)}; quietest cue {min(margins, default=-99):+.1f} dB "
          f"vs its line, loudest phantom {max(ctl_margin, default=-99):+.1f} dB vs its line")

    tap_phone = mic["tap_local"] + off
    recs = vib_records(d / "vibrator.txt", tap_phone, tap_phone + 315_000)
    statuses, folded, missing, prev = {}, 0, 0, None
    for t, _ in cues:  # the buzz is requested just before the tone, on the same callback
        near = [st for ms, st, _ in recs if -800 <= ms - t <= 100]
        if near:
            statuses[near[0]] = statuses.get(near[0], 0) + 1
        elif prev is not None and t - prev < 1000:
            folded += 1  # the dump folds an identical buzz < 1000 ms after the last into one record
        else:
            missing += 1
        prev = t
    print(f"  HAPTIC per cue: {statuses}  folded-into-previous-record={folded}  no-record={missing}  "
          f"usages={sorted({us for *_, us in recs})}")

    samples = (d / "audio-samples.txt").read_text(errors="replace").split("=== ")[1:]
    states, lists = {}, {"ducked": 0, "faded out": 0, "muted player piids due to call": 0}
    for s in samples:
        for m in re.finditer(r"piid:(\d+) deviceIds:\S* type:\S+ u/pid:\S+ state:(\w+) attr:AudioAttributes: "
                             r"usage=(\w+).*?mutedState:(\S+)", s):
            key = f"{m.group(3)} {m.group(2)} mutedState={m.group(4)}"
            states[key] = states.get(key, 0) + 1
            for name in lists:
                lm = re.search(re.escape(name) + r"[^\n]*\n([^\n]*)", s)
                if lm and re.search(rf"\b{m.group(1)}\b", lm.group(1)):
                    lists[name] += 1
    print(f"  PLAYER samples={len(samples)}  {states}  sampled in ducked/faded/call-muted lists={lists}")


if __name__ == "__main__":
    main()
