# Watch Setup — Deploying to a Physical Wear OS Watch

How to get a debug build of Race Timer onto a real watch, and how to confirm the build that is running
is the one you just made. Written so re-onboarding a new watch or a new dev machine takes minutes.

Everything here was performed on hardware on 2026-07-30 unless a step is marked **UNVERIFIED**.

## Inventory

### Watch

| | |
|---|---|
| Model | Samsung Galaxy Watch 5 Pro (`SM-R925U`) |
| Platform | Wear OS on Android 16 (API 36), One UI Watch 8.0 |
| adb guid | `adb-RFATA20KL3F-vv8Xj6` |
| Connect address | `192.168.1.73:41017` — DHCP, expect this to change |

### Paired phone

Pairing a Samsung watch requires the **Galaxy Wearable** app, not the generic Wear OS companion app.
The phone is only needed for initial pairing and system settings — Race Timer runs standalone on the
watch, and no phone is needed at race time.

| | |
|---|---|
| Phone model | **TODO — record when next paired** |
| Galaxy Wearable version | **TODO — record when next paired** |

### Dev machine

| | |
|---|---|
| OS | Windows 11 Pro |
| adb | `C:\Users\HSCCo\AppData\Local\Android\Sdk\platform-tools\adb.exe`, with `ANDROID_HOME` set |
| JDK | Any modern JDK. `:shared` declares a JVM 8 toolchain, which is rarely installed; `settings.gradle.kts` registers the Foojay resolver so Gradle downloads a matching JDK instead of failing with *No matching toolchains found*. |
| Gradle | Wrapper-pinned to 8.4 — use `./gradlew`, never a system Gradle |

No Android Studio install is required for deploying. It is only convenient for creating emulator
images via the AVD Manager, and the emulator is **not** a substitute for this procedure — see
[Why hardware](#why-hardware) below.

## One-time watch setup

1. **Enable Developer options.** Settings → System → About → tap **Build number** ×7.

   Some Samsung/One UI versions route this through Settings → About watch → Software info → tap
   **Software version** ×7 instead. If Build number is not visible under About, look for Software
   info.

2. **Enable debugging.** Settings → Developer options → enable **ADB debugging** *and* **Wireless
   debugging**.

## Pairing over adb-over-Wi-Fi

The watch and dev machine must be on the same network with client isolation off.

1. `ping <watch-ip>` first. The watch answers ICMP, so this separates a network or AP-isolation
   problem from a pairing problem *before* you spend a pairing attempt.

2. On the watch, open Developer options → Wireless debugging → **Pair new device**, and leave that
   screen up. The pairing service only advertises while it is open.

3. Read **both** ports off the watch now:
   - the **connect** port, on the main *Wireless debugging* screen;
   - the **pair** port, on the *Pair new device* sub-screen, next to the 6-digit code.

   These are different, and this is the single biggest time sink in this whole procedure. `adb pair`
   needs the **pair** port. Pairing against the connect port fails with:

   ```
   error: protocol fault (couldn't read status message): No error
   ```

   which reads like a wrong or expired code but means the wrong port.

4. Pair, passing the code as an argument rather than answering the prompt — an agent or CI shell has
   no stdin for it:

   ```bash
   adb pair <watch-ip>:<pair-port> <6-digit-code>
   ```

5. `adb mdns services` — this discovers the connect address and auto-connects. Confirm with
   `adb devices -l`; the device should flip from `offline` to `device`.

Pairing survives reboots; the connect address does not. After a watch reboot or DHCP lease change,
step 5 alone is usually enough.

## Build and install

```bash
./gradlew :shared:test        # pure-JVM engine tests, no device needed — run these first
./gradlew :wear:installDebug  # build and deploy to the connected watch
```

The app installs as `com.racetimer.wear`.

The SDK-34 build installed cleanly on this Android 16 (API 36) watch; the app has since moved to
`compileSdk`/`targetSdk` 35 (#69) with no install change expected.

### Confirm what actually landed

A successful build log is not evidence the watch is running that APK — a stale install, a failed
incremental push, or a second connected device all look like success. Compare hashes:

```bash
adb shell pm path com.racetimer.wear     # -> package:/data/app/.../base.apk
adb pull <that-path> ./on-device.apk
sha256sum ./on-device.apk wear/build/outputs/apk/debug/wear-debug.apk
```

Do this whenever a change *appears* to have had no effect. It is faster than re-debugging a fix that
was never installed.

## Smoke check on hardware

Run these after a deploy. They are the minimum that says the watch build works, and they are
deliberately about the countdown surviving real device behaviour rather than about correctness of the
timer math — the math is covered by `:shared:test`.

- [ ] App launches on the watch, no crash on the sequence picker.
- [ ] One full 3-minute (Club) sequence runs to the gun, with the expected cues.
- [ ] The sequence survives a screen-off cycle: let the display dim, tap to wake, confirm it is still
      counting and still correct.

Haptic verification is deliberately **not** part of this check. Haptics are validated last in the
MVP.

## Why hardware

An emulator showing no symptom is evidence the emulator does not model the subsystem, not evidence
the bug is absent. The Wear emulator has no real accelerometer, so it cannot reproduce the
orientation bug that shipped in `82c8283` — and the revert that followed cited an emulator test as
proof the attribute was inert, which was wrong.

Verify anything touching sensors, orientation, power, radios, or haptics on this watch, and design
the check so a buggy and a fixed build can actually differ. For orientation specifically that means
testing with a **non-zero** wrist/button placement setting: at factory default (`0`) both builds look
identical.

See [CLAUDE.md](../CLAUDE.md) for the orientation and foreground-service constraints themselves.

## Display stuck upside down

The watch's display rotation can get stuck 180 degrees off, leaving the race screen upside down. **This
is a device fault, not an app defect** — Race Timer declares no `screenOrientation` and holds no
settings permission, so it can neither cause nor clear it (see [CLAUDE.md](../CLAUDE.md)). `pm clear`
does not help either.

**Remedy, confirmed on hardware 2026-08-06. Both steps are required:**

```bash
adb shell settings put system user_rotation 0    # persist the correct value
adb reboot                                       # apply it; silent + exit 0 means accepted
```

After this the watch booted upright *and* stayed upright when lifted off the charger. Wireless
debugging is off after a reboot and needs re-enabling by hand.

Do this before a regatta, not at one — the remedy needs adb and a machine to run it from.

### What does not work

- **A bare reboot.** Measured — the watch booted straight back into the fault and sat upside down for
  about 50 minutes. The setting write is what makes the reboot work; the reboot alone re-applies the
  same wrong persisted value.
- **`wm user-rotation lock 0`.** Exits 0 and changes nothing. Skip it.
- **A tilt, off the charger.** On the charger a tilt corrects it temporarily; off the charger nothing
  does, and a race is always the undocked case.

### Reading the state

```bash
adb shell dumpsys window | grep mCurrentRotation
```

`ROTATION_180` is **upright** on this watch and `ROTATION_0` is the **fault** — inverted from what the
names suggest, because the panel is mounted 180 degrees to the framebuffer and software compensates.
Three instruments look authoritative here and are not:

| Instrument | Why not |
| --- | --- |
| `screencap` | Renders the buffer upright in *both* states. Measured twice against a panel confirmed upside down by eye. Any conclusion resting on "the screenshot looked fine" is no evidence either way |
| `mRotation`, `mDisplayRotation` | Constants on this device. Both read a value naming "180" on a perfectly healthy watch |
| `settings get system user_rotation` | The right thing to check as a *configuration* — `2` is the fault condition — but useless as a change-detector: it stayed at `2` across 240 samples and two complete flip cycles, including the sample the display flipped in |

`adb shell 'logcat -d -v time | grep "Computed rotation="'` timestamps every rotation decision the
system makes, which is the instrument that finally settled this.

Tracked as [#115](https://github.com/SailorDave17/race-timer/issues/115). What writes `user_rotation = 2`
is still unknown after five sightings, so the remedy above is a repair and not a prevention — expect
it to recur.

> **The cause is under investigation, and this section may be developer-only guidance.** Every sighting
> of this fault was recorded on the development watch, which carries wireless ADB debugging as its
> normal state, so **it has never been observed on a device with no computer attached**. The owner's
> assessment (2026-08-10) is that wireless debugging control is the likely cause.
> [#147](https://github.com/SailorDave17/race-timer/issues/147) runs the missing control arm. If it
> confirms that, this section becomes a note for people developing against a watch rather than advice
> for anyone taking one to a regatta — and it will be rewritten as such.

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `error: protocol fault (couldn't read status message): No error` on `adb pair` | Used the connect port | Use the pair port from the *Pair new device* screen |
| Watch not in `adb devices` after a reboot | Connect address changed | `adb mdns services` to rediscover; pairing itself persists |
| `ping` fails | Different network, or AP client isolation | Fix the network before touching adb |
| *No matching toolchains found* | JVM 8 toolchain absent and resolver not applied | Confirm the Foojay plugin block in `settings.gradle.kts` is intact |
| Watch display is upside down and stays that way off the charger | Device-level rotation stuck; `user_rotation = 2`. **Cause under investigation — see [#147](https://github.com/SailorDave17/race-timer/issues/147); it may be wireless-debugging-only** | Write the setting, then reboot — see [Display stuck upside down](#display-stuck-upside-down). A bare reboot does not clear it |
| Change seems to have no effect on the watch | Stale APK on device | Compare hashes as above |
| Service dies shortly after start with `ForegroundServiceDidNotStartInTimeException` | A sticky restart delivering a null intent | `TimerService` returns `START_NOT_STICKY` on purpose — see CLAUDE.md before changing it |
