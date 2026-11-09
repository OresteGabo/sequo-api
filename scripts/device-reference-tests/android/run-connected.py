#!/usr/bin/env python3
"""Guard the dedicated emulator, then run normal Gradle connected instrumentation.

The only adb operations here are read-only inventory and emulator AVD-name checks.
App/UI automation, screenshots and installation belong to AndroidX/Gradle testing.
"""
import argparse
import os
from pathlib import Path
import subprocess
import sys

EXPECTED_SERIAL = "emulator-5554"
EXPECTED_AVD = "Sequo_References_API_36_1"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sheet", type=Path, default=Path("/tmp/sequo-device-reference-sheet.json"))
    parser.add_argument("--class", dest="test_class", help="Optional official runner class or class#method selector")
    parser.add_argument("--resolve-online", action="store_true", help="First public Maven dependency resolution only; default is offline")
    args = parser.parse_args()
    project = Path(__file__).resolve().parent
    workspace = project.parents[3]
    sdk = Path(os.environ.get("ANDROID_HOME", str(Path.home() / "Library/Android/sdk")))
    adb = sdk / "platform-tools/adb"
    if os.environ.get("ANDROID_SERIAL", EXPECTED_SERIAL) != EXPECTED_SERIAL:
        parser.error("Refusing any serial except the task-owned emulator-5554")
    if not adb.is_file() or not args.sheet.is_file():
        parser.error("The installed Android SDK and authorized sanitized reference sheet are required")

    def read_adb(*arguments):
        return subprocess.check_output([str(adb), *arguments], text=True, timeout=15)

    lines = read_adb("devices").splitlines()
    devices = [line.split()[:2] for line in lines if line.strip() and not line.startswith("List of devices")]
    if devices != [[EXPECTED_SERIAL, "device"]]:
        parser.error("Refusing to install/run tests unless emulator-5554 is the sole connected device")
    avd_lines = [line.strip() for line in read_adb("-s", EXPECTED_SERIAL, "emu", "avd", "name").splitlines() if line.strip()]
    if avd_lines != [EXPECTED_AVD, "OK"]:
        parser.error("Refusing a different emulator AVD; expected exactly Sequo_References_API_36_1")
    print("Verified dedicated serial and exact AVD; delegating to official Gradle instrumentation", flush=True)
    wrapper = workspace / "Sequo/gradlew"
    if not wrapper.is_file():
        parser.error("Expected the existing native Gradle wrapper in sibling Sequo")
    command = [str(wrapper), "-p", str(project)]
    if not args.resolve_online:
        command.append("--offline")
    command += ["-PreferenceSheet=" + str(args.sheet.resolve())]
    if args.test_class:
        if not args.test_class.startswith("dev.orestegabo.sequo.devicereferencetests.") or any(c.isspace() for c in args.test_class):
            parser.error("The optional selector must name this harness's JUnit class/method")
        command.append("-Pandroid.testInstrumentationRunnerArguments.class=" + args.test_class)
    command.append(":harness:connectedDebugAndroidTest")
    environment = dict(os.environ, ANDROID_SERIAL=EXPECTED_SERIAL, ANDROID_HOME=str(sdk))
    return subprocess.call(command, env=environment)


if __name__ == "__main__":
    raise SystemExit(main())
