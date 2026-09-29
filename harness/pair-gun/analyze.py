"""Time the pair's gun with one microphone (#223).

    python analyze.py --calibrate phone=<single.wav>,watch=<single.wav>
    python analyze.py <mic.wav> --levels phone=DB,watch=DB --excess phone=MS,watch=MS
    python analyze.py --selftest

Both devices sound the same gun: one 3000 ms sustained DTMF-D tone (941 + 1633 Hz, 2 ms ramps at
each end, shared CueWaveform). One microphone cannot tell two onsets of one waveform apart, but it
hears the FIRST onset and the LAST end cleanly, because each of them borders silence. So

    |skew| = (last end - first onset) - 3000 ms - excess of the device that sounded last

where a device's `excess` is what the acoustic path adds to its gun on its own: the band filter, the
speaker, and the room ringing on after a loud tone stops. It is almost all at the end, and it differs
by device (27 ms for the phone 40 cm from the mic against 1 ms for the watch at 3 cm, measured), so
subtracting one averaged figure would bias every reading by half the difference.

Both excesses and each device's level come from `--calibrate`: one gun from each device with the other
one silent, at the placement the session keeps. The edges are cut at one fixed level for the whole
session, rel-db under the quieter device, and never at a level read off the pair recording: two
identical tones partly cancel where they overlap (by up to 29 dB at equal levels, at a 10.1 or 46.2 ms
offset), so a threshold that followed the overlap would move the edges with the phase.

Which device led is read from the level of the stretch where only the first gun sounds, checked
against the stretch where only the last one does. Place the two devices 6-12 dB apart at the mic.
When the leader cannot be named, the reading subtracts the mean excess and says how far that can be
out (half the difference between the two). That includes a leader whose own ring outlasts the other
device's gun: the recording then ends on the ring, and the skew is only bounded, never read.

Method, placement and the measured results: docs/pair-hardware.md. Standard library and ffmpeg only.
"""
import argparse
import array
import math
import operator
import random
import statistics
import subprocess
import sys
import tempfile
import wave
from dataclasses import dataclass
from pathlib import Path

RATE = 48_000
WIN = RATE // 1000  # 1 ms windows
GUN_MS = 3000
# Both of the gun's partials, and nothing of a blast (3700/4000 Hz). The sync tick's 1209 Hz partial
# passes too, but the last tick ends ~0.9 s before the gun, far outside the merge gap.
BAND = "highpass=f=800,highpass=f=800,lowpass=f=1900,lowpass=f=1900"
FLOOR_DB = -150.0
# Bridges the flicker where a ringing gun decays through the cut: its two partials beat at 692 Hz, so
# a 1 ms level rises and falls across the cut for a few ms, and without this an edge lands on the
# first dip rather than the last crossing, differently in a calibration and a race (3 ms, one selftest
# case). The overlap never dips below the cut (MIN_APART_DB). Kept short on purpose: at 300 ms it
# swallowed room noise into a gun, and a sound just after the gun would have moved its end.
MERGE_MS = 20
MIN_GUN_MS = 2500
COARSE_DB = 40.0  # a gun is anything sustained within this of the loudest window; wider than 29
# A gun is the loudest thing in its band. A long run of room noise merged across its gaps is not, and
# the first calibration in a real room found one 38 dB under the gun.
PLATEAU_WITHIN_DB = 20.0
QUIET_MS = 300  # the stretch either side of a gun that must stay under the cut for it to read clean
QUIET_GAP_MS = 20
EDGE_MARGIN_MS = 2
MIN_STRETCH_MS = 2 * EDGE_MARGIN_MS + 4  # shortest alone-stretch a level is read from
# The edges are cut this far under the quieter device. Measured on the phone 40 cm from the mic: its
# gun ends in a 6-8 dB step and then the room rings on at ~0.4 dB/ms, so a cut 20 dB down landed in
# the slow ring, where 1 dB of level moves the edge ~2 ms (the same gun read 3027 and 3041 ms). 10 dB
# down stays nearer the step, and is still clear of the overlap under MIN_APART_DB.
REL_DB = 10.0
# At this far apart the overlap of two identical guns never falls below the quieter one alone, so it
# never falls below the cut, whatever the phase between them.
MIN_APART_DB = 6.0
NAMEABLE_DB = 3.0


@dataclass
class Gun:
    onset_ms: int
    end_ms: int
    plateau_db: float
    clean: bool = True  # nothing else in the band within QUIET_MS either side

    @property
    def span_ms(self) -> int:
        return self.end_ms - self.onset_ms


def pcm(wav: Path, band: str | None = BAND) -> array.array:
    cmd = ["ffmpeg", "-hide_banner", "-loglevel", "error", "-i", str(wav)]
    if band:
        cmd += ["-af", band]
    cmd += ["-ac", "1", "-ar", str(RATE), "-f", "s16le", "-"]
    raw = subprocess.run(cmd, capture_output=True, check=True).stdout
    out = array.array("h")
    out.frombytes(raw)
    if sys.byteorder != "little":
        out.byteswap()
    return out


def envelope(samples: array.array) -> list[float]:
    """1 ms RMS level in dBFS."""
    full = 32768.0**2
    env = []
    for i in range(0, len(samples) - WIN + 1, WIN):
        chunk = samples[i : i + WIN]
        mean_sq = sum(map(operator.mul, chunk, chunk)) / WIN
        env.append(10 * math.log10(mean_sq / full) if mean_sq > 0 else FLOOR_DB)
    return env


def runs_above(env: list[float], threshold: float, merge_ms: int) -> list[tuple[int, int]]:
    """[start, end) runs of windows at or above threshold, bridging gaps up to merge_ms."""
    out: list[list[int]] = []
    for i, level in enumerate(env):
        if level < threshold:
            continue
        if out and i - out[-1][1] <= merge_ms:
            out[-1][1] = i + 1
        else:
            out.append([i, i + 1])
    return [(s, e) for s, e in out]


def find_guns(env: list[float], threshold: float | None = None) -> list[Gun]:
    """Every sustained tone at least MIN_GUN_MS long, cut at `threshold` (or plateau - REL_DB)."""
    if not env:
        return []
    loudest = max(env)
    coarse = loudest - COARSE_DB
    guns = []
    for s, e in runs_above(env, coarse, MERGE_MS):
        if e - s < MIN_GUN_MS:
            continue
        plateau = statistics.median(env[s + 500 : min(e, s + 2500)])
        if plateau < loudest - PLATEAU_WITHIN_DB:
            continue
        cut = plateau - REL_DB if threshold is None else threshold
        lo, hi = max(0, s - 400), min(len(env), e + 400)
        fine = [r for r in runs_above(env[lo:hi], cut, MERGE_MS) if r[1] - r[0] >= MIN_GUN_MS]
        if fine:
            onset, end = lo + fine[0][0], lo + fine[0][1]
            around = env[max(0, onset - QUIET_MS) : max(0, onset - QUIET_GAP_MS)] + env[end + QUIET_GAP_MS : end + QUIET_MS]
            guns.append(Gun(onset, end, plateau, clean=all(level < cut for level in around)))
    return guns


def nearest(level: float, levels: dict[str, float]) -> str:
    return min(levels, key=lambda name: abs(levels[name] - level))


def measure(gun: Gun, env: list[float], excess: dict[str, float], levels: dict[str, float]) -> dict:
    """The skew, who led, and how far the reading can be out."""
    raw = gun.span_ms - GUN_MS
    ex = excess or {"-": 0.0}
    out = {"led": "?", "lead_db": None, "tail_db": None}
    nameable = len(levels) == 2 and max(levels.values()) - min(levels.values()) >= NAMEABLE_DB
    # Try each device as the leader. "L led" fixes the skew (the other device's excess comes off),
    # which fixes both alone-stretches; it holds only if the lead stretch sounds like L and the tail
    # stretch, stopped short of the other device's ring, sounds like the other. Exactly one must hold.
    fits = []
    for led in levels if nameable else ():
        last = next(name for name in levels if name != led)
        if raw <= excess.get(led, 0.0) + EDGE_MARGIN_MS:
            continue  # the leader's own ring could be what ends the recording: the end is not the last's
        skew = raw - excess.get(last, 0.0)
        k = int(round(skew))
        if k < MIN_STRETCH_MS:
            continue
        stop = gun.end_ms - int(round(excess.get(last, 0.0)))
        lead_db = statistics.median(env[gun.onset_ms + EDGE_MARGIN_MS : gun.onset_ms + k - EDGE_MARGIN_MS])
        tail_db = statistics.median(env[stop - k + EDGE_MARGIN_MS : stop - EDGE_MARGIN_MS])
        if nearest(lead_db, levels) == led and nearest(tail_db, levels) == last:
            fits.append({"led": led, "skew": skew, "within": 0.0, "lead_db": lead_db, "tail_db": tail_db})
    if len(fits) == 1:
        return fits[0]
    out["skew"] = raw - statistics.mean(ex.values())
    out["within"] = (max(ex.values()) - min(ex.values())) / 2
    return out


def threshold_for(levels: dict[str, float]) -> float | None:
    return min(levels.values()) - REL_DB if levels else None


def calibrate(singles: dict[str, Path]) -> tuple[dict[str, float], dict[str, float]]:
    """Each device's level alone, then each single gun's excess span at the session's threshold."""
    envs = {name: envelope(pcm(p)) for name, p in singles.items()}
    levels = {}
    for name, env in envs.items():
        guns = find_guns(env)
        if len(guns) != 1:
            raise SystemExit(f"{singles[name]}: expected one gun, found {len(guns)}")
        if not guns[0].clean:
            raise SystemExit(f"{singles[name]}: another sound within {QUIET_MS} ms of the gun; record it again")
        levels[name] = guns[0].plateau_db
    if len(levels) == 2 and max(levels.values()) - min(levels.values()) < MIN_APART_DB:
        raise SystemExit(
            f"levels {levels} are under {MIN_APART_DB:.0f} dB apart: two identical guns can cancel below "
            "the cut where they overlap. Move one device and calibrate again."
        )
    cut = threshold_for(levels)
    excess = {}
    for name, env in envs.items():
        (g,) = find_guns(env, cut)
        excess[name] = float(g.span_ms - GUN_MS)
    return levels, excess


def analyze(wav: Path, excess: dict[str, float], levels: dict[str, float]) -> list[dict]:
    env = envelope(pcm(wav))
    out = []
    for gun in find_guns(env, threshold_for(levels)):
        m = measure(gun, env, excess, levels)
        out.append(
            {
                "onset_s": gun.onset_ms / 1000,
                "span_ms": gun.span_ms,
                "skew_ms": round(m["skew"], 1),
                "within_ms": round(m["within"], 1),
                "led": m["led"],
                "clean": gun.clean,
                "plateau_db": round(gun.plateau_db, 1),
                "lead_db": None if m["lead_db"] is None else round(m["lead_db"], 1),
                "tail_db": None if m["tail_db"] is None else round(m["tail_db"], 1),
            }
        )
    return out


# --- selftest: synthetic recordings whose answer is known ------------------------------------------


@dataclass
class Device:
    """A synthetic speaker: its level at the mic, and how long the room rings on after its gun."""

    amp: float
    # After the gun stops the tone steps down 3 dB and decays at 0.25 dB/ms for this long: the shape
    # of the phone's end 40 cm from the mic, slower than it, so the ring outlasts short skews.
    ring_ms: int = 0


def _tone(n_ms: int, freqs: tuple[float, float], amp: float, ring_ms: int = 0) -> list[float]:
    n, ramp, ring = n_ms * RATE // 1000, 2 * RATE // 1000, ring_ms * RATE // 1000
    out = []
    for k in range(n + ring):
        if k < ramp:
            g = 0.5 * (1 - math.cos(math.pi * k / ramp))
        elif k < n - ramp:
            g = 1.0
        elif k < n:
            g = 0.5 * (1 - math.cos(math.pi * (n - 1 - k) / ramp))
        else:
            g = 10 ** ((-3 - 0.25 * (k - n) * 1000 / RATE) / 20)
        t = k / RATE
        out.append(amp * g * 0.5 * (math.sin(2 * math.pi * freqs[0] * t) + math.sin(2 * math.pi * freqs[1] * t)))
    return out


def _write(path: Path, parts: list[tuple[int, list[float]]], total_ms: int, noise_db: float, seed: int):
    rng = random.Random(seed)
    buf = [rng.gauss(0, 10 ** (noise_db / 20)) for _ in range(total_ms * RATE // 1000)]
    for at, samples in parts:
        for i, v in enumerate(samples):
            buf[at + i] += v
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(array.array("h", (max(-32767, min(32767, int(v * 32767))) for v in buf)).tobytes())


def _race(
    path: Path,
    skew_ms: float,
    first: Device,
    second: Device | None,
    seed: int,
    blip_after_ms: int | None = None,
    hum: bool = False,
):
    """Two devices' last sync tick and gun: the second device sounds everything skew_ms later.

    `blip_after_ms` adds a 50 ms in-band sound that long after the last gun ends, at the loud level: a
    voice or a door. `hum` adds 3.5 s of steady in-band sound 32 dB under the gun, after it: a fridge.
    """
    tick, gun = (697.0, 1209.0), (941.0, 1633.0)
    at = lambda ms: int(round(ms * RATE / 1000))  # noqa: E731 - sample-accurate offsets
    parts = [(at(200), _tone(100, tick, first.amp)), (at(1200), _tone(GUN_MS, gun, first.amp, first.ring_ms))]
    if second is not None:
        parts += [
            (at(200 + skew_ms), _tone(100, tick, second.amp)),
            (at(1200 + skew_ms), _tone(GUN_MS, gun, second.amp, second.ring_ms)),
        ]
    if blip_after_ms is not None:
        parts.append((at(1200 + GUN_MS + skew_ms + blip_after_ms), _tone(50, (1300.0, 1500.0), 0.5)))
    if hum:
        parts.append((at(5000), _tone(3500, (1300.0, 1300.0), 0.5 * 10 ** (-32 / 20))))
    _write(path, parts, 9000, -70.0, seed)


def selftest() -> int:
    """Known offsets in, the same offsets out. Every case's answer is written before it runs."""
    # Two devices 8 dB apart, as the placement asks. The quiet one rings on like a phone across a
    # room; the loud one close to the mic barely does, as measured on the real pair.
    ringer, close = Device(0.5 * 10 ** (-8 / 20), ring_ms=120), Device(0.5)
    tolerance = 2.0
    fails = 0
    with tempfile.TemporaryDirectory() as tmp:
        tmp = Path(tmp)
        singles = {}
        for i, (name, dev) in enumerate((("ringer", ringer), ("close", close))):
            singles[name] = tmp / f"{name}.wav"
            # The close one's single carries a hum after its gun: a calibration must still find one gun.
            _race(singles[name], 0, dev, None, 1 + i, hum=name == "close")
        try:
            levels, excess = calibrate(singles)
        except SystemExit as refused:
            print(f"FAIL calibration refused the synthetic singles: {refused}\nselftest: 1 failure(s)")
            return 1
        print(f"calibration: levels {levels}, excess {excess} ms")
        if excess["ringer"] - excess["close"] < 10:
            print("FAIL the synthetic ring did not separate the two excesses; the cases below prove nothing")
            fails += 1
        # A placement with the two devices at one level must be refused, not calibrated.
        _race(tmp / "close2.wav", 0, Device(close.amp * 10 ** (-3 / 20)), None, 3)
        try:
            calibrate({"close": singles["close"], "close2": tmp / "close2.wav"})
            print("FAIL calibration accepted two devices 3 dB apart")
            fails += 1
        except SystemExit as refused:
            print(f"ok   calibration refused 3 dB apart: {str(refused)[:40]}...")
        cases = [
            # skew ms, first, second, expected leader (None: no gun may be read at all)
            (0, close, close, "?"),
            (20, close, ringer, "close"),  # the ringer sounds last: its ring must come off
            # The ringer leads by less than its own ring, so the recording ends on the ring and not on
            # the close one's gun. The skew can only be bounded; naming a leader here reads it as 28.
            (20, ringer, close, "?"),
            (10, ringer, close, "?"),
            (50, ringer, close, "ringer"),  # past its ring: the close one's end is the end, nothing comes off
            (73, close, ringer, "close"),
            (100, close, ringer, "close"),
            (35, close, close, "?"),  # equal levels, overlap +5.4 dB: the skew holds, no leader named
            # Equal levels at the two deepest cancellations there are (-29 dB over the overlap) sink
            # below the cut, and must read as NO gun, never as a wrong one. Calibration refuses this
            # placement; these two prove what it would cost if it were allowed.
            (46.229, close, close, None),
            (10.104, close, close, None),
            (46.229, close, ringer, "close"),  # the same phase 8 dB apart: the overlap stays above the cut
            (4, close, ringer, "?"),  # too short to read a level from: mean excess, and says how far out
            (150, ringer, close, "ringer"),  # the negative control: must read over a 100 ms budget
            # A sound 120 ms after the gun: the reading must hold, and must say it was not clean.
            (73, close, ringer, "close", 120),
        ]
        for i, (skew, first, second, leader, *blip) in enumerate(cases):
            p = tmp / f"case{i}.wav"
            _race(p, skew, first, second, 10 + i, blip_after_ms=blip[0] if blip else None)
            res = analyze(p, excess, levels)
            if leader is None:
                ok = not res
            else:
                ok = (
                    len(res) == 1
                    and abs(res[0]["skew_ms"] - skew) <= tolerance + res[0]["within_ms"]
                    and res[0]["led"] == leader
                    and res[0]["clean"] == (not blip)
                )
            got = (
                f"{res[0]['skew_ms']:>7} ms +/-{res[0]['within_ms']}, led {res[0]['led']}"
                f"{'' if res[0]['clean'] else ', NOT CLEAN'}"
                if len(res) == 1
                else f"{len(res)} guns"
            )
            fails += not ok
            print(f"{'ok  ' if ok else 'FAIL'} skew {skew:>7} ms -> {got} (want {leader})")
            if skew > 100 and len(res) == 1 and res[0]["skew_ms"] <= 100:
                print("FAIL a skew over the budget read inside it")
                fails += 1
    print(f"selftest: {fails} failure(s)")
    return 1 if fails else 0


def parse_pairs(text: str) -> dict[str, str]:
    return dict(kv.split("=", 1) for kv in text.split(",") if kv)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("wav", nargs="?", type=Path)
    ap.add_argument("--calibrate", default="", help="phone=<wav>,watch=<wav>: one gun each, the other silent")
    ap.add_argument("--levels", default="", help="phone=DB,watch=DB, from --calibrate")
    ap.add_argument("--excess", default="", help="phone=MS,watch=MS, from --calibrate")
    ap.add_argument("--selftest", action="store_true")
    args = ap.parse_args()
    if args.selftest:
        return selftest()
    if args.calibrate:
        levels, excess = calibrate({k: Path(v) for k, v in parse_pairs(args.calibrate).items()})
        lv = ",".join(f"{k}={v:.1f}" for k, v in levels.items())
        ex = ",".join(f"{k}={v:.0f}" for k, v in excess.items())
        print(f"--levels {lv} --excess {ex}")
        return 0
    if not args.wav:
        ap.error("a recording, --calibrate, or --selftest")
    levels = {k: float(v) for k, v in parse_pairs(args.levels).items()}
    excess = {k: float(v) for k, v in parse_pairs(args.excess).items()}
    guns = analyze(args.wav, excess, levels)
    if not guns:
        print("no gun found: nothing sustained for 2.5 s in the gun's band")
        return 1
    for g in guns:
        print(
            f"gun at {g['onset_s']:.3f} s: skew {g['skew_ms']} ms +/-{g['within_ms']}, led {g['led']}"
            f"{'' if g['clean'] else ', NOT CLEAN: another sound near the gun'} "
            f"(span {g['span_ms']} ms, plateau {g['plateau_db']} dB, lead {g['lead_db']} dB, tail {g['tail_db']} dB)"
        )
    return 0


if __name__ == "__main__":
    sys.exit(main())
