#!/usr/bin/env python3
"""Generate or check docs/declared-surface.lock -- the app's externally-visible surface.

WHY THIS EXISTS (#83). docs/privacy-policy.md is PUBLISHED, and Play fetches it. It asserts that
neither app requests INTERNET, that there are no third-party SDKs, no analytics and no ad networks,
that the phone disables Auto Backup while the watch does not, and it carries a permission-by-
permission table that has to match what ships. docs/play-store-fgs-justification.md argues at
length that no standard foreground-service type applies. Every one of those claims is true only
because of what the manifests and the dependency graph currently say.

Until this script existed, that dependency was guarded by a COMMENT in each document -- which only
works if the person adding a permission happens to open a file they had no reason to open. The
failure mode is silent, and the consequence is a live public privacy policy that lies about the
shipped app: a Play policy violation, not a documentation bug.

    python3 .github/scripts/declared-surface.py --write     regenerate the lock file
    python3 .github/scripts/declared-surface.py --check      fail if the tree no longer matches it

THE MERGED MANIFEST IS THE SUBJECT, NOT THE SOURCE MANIFEST, and that is the whole reason this
reads through Gradle instead of parsing wear/src/main/AndroidManifest.xml directly. #83 originally
proposed the source file, reasoning that a standalone parser avoids needing a full Android build.
Measured 2026-08-18: the two manifest-merge tasks take ~5 s -- they are a merge, not a build, so the
objection does not apply -- and the source manifests declare ZERO receivers and ZERO providers while
the merged ones carry an androidx `InitializationProvider`, an EXPORTED `ProfileInstallReceiver`,
and an injected signature permission. Three of the five fields #83 asked to lock are empty in the
source and populated after merging, so a source-only lock would have recorded empty sets for them
and been blind by construction to the case that matters most: a dependency injecting a permission
the source never names. Both Play documents already say the merged manifest is the check
(privacy-policy.md maintainer note 2; play-app-content-declarations.md, the Advertising ID row).

WHY IT INVOKES GRADLE RATHER THAN READING WHAT IS ON DISK. The merged manifests live under
build/intermediates/, which survives across branches. A run that read whatever was there would
compare the working tree against an artefact built from different sources and be self-consistent
while doing it -- every field would agree with every other field, because one real merge wrote them
all. So the tasks are re-run with --rerun-tasks on every invocation and the freshness question is
removed rather than checked. --no-watch-fs is not optional either: Gradle's file watcher goes stale
on the maintainer's machine and reports success on work it never did.

WHAT IS DELIBERATELY NOT LOCKED, so an absence here reads as a decision:

  * Test-only dependencies. The lock reads each module's RELEASE RUNTIME classpath, so junit,
    Robolectric and the Compose test artifacts are absent -- correctly, because the privacy claims
    are about what ships. Adding an analytics SDK as `testImplementation` would not trip this guard
    and would not falsify the policy either.
  * Dependency VERSIONS (#83's own instruction): coordinates are recorded as `group:artifact`, so a
    version bump is silent and a NEW dependency is not. A version bump that drags in a new
    transitive does trip it, which is correct -- a new dependency appeared.
  * Dependency CONSTRAINTS, the `(c)` lines in Gradle's report. A BOM bump rewrites the constraint
    set without changing what resolves, and #83 AC 3 is explicit that a noisy guard gets disabled
    within a month. Anything genuinely on the classpath also appears as a non-constraint line, and
    `dependency_coordinates` refuses rather than assuming so: it collects the constraint rows
    separately and fails if any coordinate appears ONLY as a constraint.
  * Inter-module `project :foo` edges. Not third-party, and #83 scoped the dependency half to
    third-party coordinates.
  * `uses-sdk`. `targetSdk` is load-bearing for the phone upload deadline recorded in
    docs/play-app-content-declarations.md, but it is not a permission-shaped claim and it was not in
    scope for this story. Named here so the gap is on the record.
"""

import argparse
import os
import re
import subprocess
import xml.etree.ElementTree as ElementTree
from pathlib import Path

ANDROID = "{http://schemas.android.com/apk/res/android}"

LOCK_PATH = "docs/declared-surface.lock"
LOCK = Path(LOCK_PATH)
REGENERATE = "python3 .github/scripts/declared-surface.py --write"

# The documents whose claims this lock exists to protect. The failure message names all of them
# rather than reporting a bare diff: the guard's job is to stop the change until a human has
# re-opened the question, not to work out which sentence broke.
RECHECK = [
    ("docs/privacy-policy.md", 'permission table, "no INTERNET", "no third-party SDKs", Device backup'),
    ("docs/play-store-fgs-justification.md", "the why-no-standard-type argument, and the subtype string"),
    ("docs/play-app-content-declarations.md", "Play Console > App content, incl. Data safety and Advertising ID"),
]

# Every app artifact that ships, and every module whose dependencies reach one. The app modules'
# release runtime classpaths already contain the library modules' external dependencies
# transitively; the library sections exist so a diff says WHICH module gained something.
#
# #83 named `:wear` and `:shared`, which was the whole repo when it was filed on 2026-08-02. #197
# founded `:phone` and #200 founded `:shared-android`, and the published policy has covered both
# apps since #212 -- so locking the 2026-08-02 set would leave phone/src/main/AndroidManifest.xml
# guarded by nothing, which is the exact silent failure this story was filed to prevent. Widened by
# owner decision 2026-08-18.
APP_MODULES = ["wear", "phone"]
DEPENDENCY_MODULES = [
    ("shared", "runtimeClasspath"),
    ("shared-android", "releaseRuntimeClasspath"),
    ("wear", "releaseRuntimeClasspath"),
    ("phone", "releaseRuntimeClasspath"),
]

MERGED_MANIFEST = "{module}/build/intermediates/merged_manifest/release/processReleaseMainManifest/AndroidManifest.xml"

# A Gradle dependency-report row: any depth of `|   ` / spaces, then `+---` or `\---`, then the
# coordinate. Anything else in that output (the header, the blank lines, the (c)/(*) legend, the
# web-report footer) fails to match, which is what keeps the parser from inventing coordinates.
TREE_ROW = re.compile(r"^[ |]*[+\\]--- (?P<rest>\S.*)$")


def run_gradle(tasks, extra_args):
    """Run Gradle and return stdout, refusing to treat a failed build as an empty result.

    A task that did not run and a subject with nothing in it produce the same empty output, and the
    second is the one a reader believes. So the exit code is checked and the failure carries
    Gradle's own text.
    """
    # Two Windows traps here, and both report as something other than what they are.
    # CreateProcess cannot execute a .bat at all, so calling the wrapper directly raises
    # FileNotFoundError on a file sitting in the cwd -- which reads as "no Gradle installed". And
    # `cmd /c gradlew.bat` then fails to find it, because a shell launched from Git Bash inherits
    # NoDefaultCurrentDirectoryInExePath and stops searching the working directory. An absolute path
    # sidesteps both.
    if os.name == "nt":
        wrapper = ["cmd", "/c", str(Path("gradlew.bat").resolve())]
    else:
        wrapper = [str(Path("gradlew").resolve())]
    command = [*wrapper, *tasks, "--no-watch-fs", "--rerun-tasks", "-q", *extra_args]
    completed = subprocess.run(
        command,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    if completed.returncode != 0:
        raise SystemExit(
            f"gradle failed ({' '.join(command)}) with exit {completed.returncode}:\n"
            f"{completed.stdout}\n{completed.stderr}"
        )
    return completed.stdout


def merge_manifests(extra_args):
    """Produce every app module's merged release manifest, and assert each one arrived."""
    run_gradle([f":{module}:processReleaseMainManifest" for module in APP_MODULES], extra_args)
    paths = {}
    for module in APP_MODULES:
        path = Path(MERGED_MANIFEST.format(module=module))
        if not path.is_file():
            raise SystemExit(
                f"no merged manifest at {path} after :{module}:processReleaseMainManifest -- "
                "the task's output path has moved, and a missing file must not read as an empty "
                "declaration set"
            )
        paths[module] = path
    return paths


def manifest_facts(module, path):
    """One sorted line per externally-visible declaration in a merged manifest.

    Explicit UTF-8: the FGS subtype strings contain an em dash, and Python on Windows would
    otherwise decode this file as cp1252 and silently mangle it into the lock.
    """
    root = ElementTree.fromstring(path.read_text(encoding="utf-8"))
    if root.tag != "manifest":
        raise SystemExit(f"{path} root element is <{root.tag}>, expected <manifest>")

    facts = []

    for element in root.findall("uses-permission"):
        facts.append(f"uses-permission {element.get(ANDROID + 'name')}")

    for element in root.findall("permission"):
        facts.append(
            f"permission {element.get(ANDROID + 'name')} "
            f"protectionLevel={element.get(ANDROID + 'protectionLevel')}"
        )

    for element in root.findall("uses-feature"):
        # An absent `required` means true. Normalised so the lock never changes shape because
        # somebody spelled the default out.
        required = element.get(ANDROID + "required", "true")
        facts.append(f"uses-feature {element.get(ANDROID + 'name')} required={required}")

    application = root.find("application")
    if application is None:
        raise SystemExit(f"{path} has no <application> element")

    # Not one of the five fields #83 enumerated, and load-bearing anyway: the published policy's
    # Device backup section says the phone disables Auto Backup and the watch does not. The platform
    # default is true, so flipping the phone's flag -- or deleting the attribute -- falsifies a
    # section of a live document with no other guard on it. Added by owner decision 2026-08-18.
    facts.append(f"allow-backup {application.get(ANDROID + 'allowBackup')}")

    for kind in ("service", "receiver", "provider"):
        for element in application.findall(kind):
            name = element.get(ANDROID + "name")
            line = f"{kind} {name} exported={element.get(ANDROID + 'exported')}"
            fgs_type = element.get(ANDROID + "foregroundServiceType")
            if fgs_type is not None:
                line += f" foregroundServiceType={fgs_type}"
            facts.append(line)

            # The subtype string is what a Play reviewer reads, and it is quoted verbatim in
            # docs/play-store-fgs-justification.md. Editing it there or here silently desynchronises
            # the declaration from the artifact. Added by owner decision 2026-08-18.
            for prop in element.findall("property"):
                if prop.get(ANDROID + "name") == "android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE":
                    facts.append(f"fgs-subtype {name} = {prop.get(ANDROID + 'value')}")

    # A parser sanity check, not a claim about the app. Both apps declare foreground-service
    # permissions and one specialUse service; a run that produced neither has failed to parse
    # rather than found a permissionless app, and the whole point of a guard whose good outcome is
    # "nothing changed" is that it must not be able to report that by accident.
    if not any(fact.startswith("uses-permission ") for fact in facts):
        raise SystemExit(f"parsed no uses-permission from {path} -- refusing to lock an empty set")
    if not any("foregroundServiceType=" in fact for fact in facts):
        raise SystemExit(f"parsed no foreground service from {path} -- refusing to lock an empty set")

    return sorted(facts)


def dependency_coordinates(module, configuration, extra_args):
    """Resolved third-party `group:artifact` coordinates, versions and constraints dropped."""
    report = run_gradle([f":{module}:dependencies", "--configuration", configuration], extra_args)

    # A positive control on the parse: this is the one line that proves the output being read
    # belongs to the module that was asked for. Without it, a report for the wrong project -- or an
    # empty one -- would parse to a plausible set.
    if f"Project ':{module}'" not in report:
        raise SystemExit(
            f"the dependency report for :{module} does not name that project -- refusing to parse it"
        )

    coordinates = set()
    constraints = set()
    for line in report.splitlines():
        match = TREE_ROW.match(line)
        if match is None:
            continue
        rest = match.group("rest")
        if rest.startswith("project "):
            continue
        token = rest.split()[0]
        parts = token.split(":")
        if len(parts) < 2 or not parts[0] or not parts[1]:
            raise SystemExit(
                f"could not read a group:artifact coordinate from {line!r} in the :{module} report"
            )
        coordinate = f"{parts[0]}:{parts[1]}"
        # `(c)` marks a constraint rather than a dependency. Recorded separately so the assertion
        # below can prove nothing is lost by dropping them.
        if rest.endswith("(c)"):
            constraints.add(coordinate)
        else:
            coordinates.add(coordinate)

    if not coordinates:
        raise SystemExit(
            f"parsed no dependencies for :{module} ({configuration}) -- refusing to lock an empty set"
        )

    constraint_only = sorted(constraints - coordinates)
    if constraint_only:
        raise SystemExit(
            f"these coordinates appear in :{module} ({configuration}) ONLY as constraints, so "
            f"dropping constraint rows would lose them: {', '.join(constraint_only)}\n"
            "Constraints are dropped because a BOM bump rewrites them without changing what "
            "resolves. If this fires, that reasoning no longer holds for this build -- decide "
            "deliberately rather than widening the filter to make it pass."
        )

    return sorted(coordinates)


def render(extra_args):
    """Build the whole lock file as text. Every section sorted; no alignment padding, because a
    long name would otherwise reflow lines that did not change."""
    manifests = merge_manifests(extra_args)

    lines = [
        "# The declared surface of Mad Cow Race Timer -- GENERATED, never hand-edited.",
        "#",
        f"# Regenerate with:  {REGENERATE}",
        "# Checked in CI by the same script with --check.",
        "#",
        "# This file exists so that adding a permission, an exported component or a dependency",
        "# cannot silently falsify the PUBLISHED privacy policy. Before updating it, re-check:",
    ]
    for document, what in RECHECK:
        lines.append(f"#   {document}  ({what})")
    lines += [
        "#",
        "# Read from each app's MERGED release manifest, not from its source manifest: a dependency",
        "# can inject a permission or an exported receiver the source never names, and two of the",
        "# entries below are exactly that. Dependencies are release-runtime only, so nothing",
        "# test-only appears; coordinates carry no version, so a version bump is silent and a new",
        "# dependency is not. See the script's docstring for what is deliberately absent.",
    ]

    for module in APP_MODULES:
        lines += [
            "",
            f"[manifest :{module}]",
            # The template, not str(Path). A Path renders with backslashes on Windows and forward
            # slashes on Linux, so writing the resolved path here would make the committed file
            # differ by platform and fail the byte-comparison in CI for a reason that has nothing to
            # do with the app -- #83 AC 3's noise failure, introduced by the check meant to prevent it.
            f"# {MERGED_MANIFEST.format(module=module)}",
        ]
        lines += manifest_facts(module, manifests[module])

    for module, configuration in DEPENDENCY_MODULES:
        lines += [
            "",
            f"[dependencies :{module} {configuration}]",
        ]
        lines += dependency_coordinates(module, configuration, extra_args)

    return "\n".join(lines) + "\n"


def write(content):
    LOCK.parent.mkdir(parents=True, exist_ok=True)
    # newline="" so "\n" is written literally on Windows too -- the committed copy must be
    # byte-identical whichever platform regenerated it, and .gitattributes pins it to LF.
    with LOCK.open("w", encoding="utf-8", newline="") as handle:
        handle.write(content)


def check(content):
    if not LOCK.is_file():
        raise SystemExit(
            f"{LOCK_PATH} does not exist. It is a committed artifact, not build output.\n"
            f"Generate it with:  {REGENERATE}"
        )
    committed = LOCK.read_text(encoding="utf-8", newline="")
    if committed == content:
        print(f"ok - {LOCK_PATH} matches the tree")
        return

    import difflib

    diff = "".join(
        difflib.unified_diff(
            committed.splitlines(keepends=True),
            content.splitlines(keepends=True),
            fromfile=f"{LOCK_PATH} (committed)",
            tofile="regenerated from the tree",
        )
    )
    message = [
        "",
        "The app's declared surface changed. Before updating this lock file, re-check:",
    ]
    for document, what in RECHECK:
        message.append(f"  {document:<40}({what})")
    message += [
        "",
        f"Then regenerate:  {REGENERATE}",
        "",
        "docs/privacy-policy.md is PUBLISHED and Play fetches it. A permission, an exported",
        "component or a dependency that contradicts it is a policy violation, not a stale doc --",
        "so this step refuses the change rather than reporting it. Updating the lock without",
        "re-reading those documents defeats the entire point of the file.",
        "",
        diff.rstrip(),
        "",
    ]
    raise SystemExit("\n".join(message))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--write", action="store_true", help="regenerate the lock file")
    group.add_argument("--check", action="store_true", help="fail if the tree no longer matches")
    args = parser.parse_args()

    if not Path("settings.gradle.kts").is_file():
        raise SystemExit("run this from the repository root (no settings.gradle.kts here)")

    # CI needs -Porg.gradle.java.installations.fromEnv=JAVA_HOME_8_X64 so :shared's JVM 8 toolchain
    # resolves from disk rather than being downloaded. Passed by environment rather than as a flag
    # so the two invocations in .github/workflows/ci.yml stay one string.
    extra_args = os.environ.get("GRADLE_ARGS", "").split()

    content = render(extra_args)
    if args.write:
        write(content)
        print(f"wrote {LOCK_PATH}")
    else:
        check(content)


if __name__ == "__main__":
    main()
