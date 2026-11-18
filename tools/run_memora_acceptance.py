#!/usr/bin/env python3
"""Safe, emulator-only entry point for Memora's synthetic acceptance checks.

The initial device inventory is read-only. Every later ADB operation is pinned
to one emulator that is re-verified through Android system properties. The
runner never selects a physical device and never invokes live AI APIs.
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import re
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / "apps/android"
APP = ANDROID / "app"
GRADLEW = ANDROID / "gradlew"
PACKAGE = "ai.memora.app"
REPORT_ROOT = APP / "build/acceptance-lab"

QUICK_TESTS = [
    "ai.memora.app.agent.AgentRuntimeTest",
    "ai.memora.app.agent.CommandLifecycleTest",
    "ai.memora.app.agent.AgentFakeProviderFinanceTest",
    "ai.memora.app.agent.AgentFakeProviderCaseTest",
    "ai.memora.app.agent.AgentFakeProviderUnifiedMemoryTest",
    "ai.memora.app.agent.AgentFakeProviderEpisodeTest",
    "ai.memora.app.LocalProfileOnboardingTest",
    "ai.memora.app.EntityDetailScreensTest",
    "ai.memora.app.AppRouteTest",
    "ai.memora.app.data.EncryptedTaskStoreTest",
    "ai.memora.app.data.EncryptedCaptureStoreTest",
    "ai.memora.app.acceptance.SyntheticReliabilityProfileTest",
    "ai.memora.app.acceptance.SyntheticReliabilityJourneyTest",
    "ai.memora.app.acceptance.SyntheticReliabilityFailureMatrixTest",
]

FULL_TASKS = [
    ":app:testDebugUnitTest",
    ":app:assembleDebug",
    ":app:assembleDebugAndroidTest",
    ":app:compileDebugAndroidTestKotlin",
    ":app:lintDebug",
    ":app:componentVisualRegression",
]


def utc_stamp() -> str:
    return dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%S.%fZ")


def test_force_init_script(out_dir: Path) -> Path:
    """Force fresh JUnit execution while leaving compile/build tasks incremental."""
    path = out_dir / "acceptance-init.gradle"
    path.write_text(
        """gradle.projectsEvaluated {
  gradle.rootProject.allprojects {
    tasks.matching { it.name == 'testDebugUnitTest' }.configureEach {
      outputs.upToDateWhen { false }
    }
  }
}
""",
        encoding="utf-8",
    )
    return path


def scrub(text: str) -> str:
    """Remove common credential formats and keep diagnostics bounded."""
    patterns = [
        (r"(?i)(AIza[0-9A-Za-z_-]{20,})", "[REDACTED_API_KEY]"),
        (r"(?i)(Bearer\s+)[A-Za-z0-9._~+/-]{12,}", r"\1[REDACTED]"),
        (r"(?i)((?:api[_-]?key|token|secret|password)\s*[=:]\s*)[^\s,;]+", r"\1[REDACTED]"),
        (r"\b[A-Za-z0-9_+/=-]{32,}\b", "[REDACTED_LONG_TOKEN]"),
    ]
    for pattern, replacement in patterns:
        text = re.sub(pattern, replacement, text)
    return text[-5000:]


def run(command: list[str], *, timeout: int = 1800, env: dict[str, str] | None = None) -> dict[str, Any]:
    started = time.monotonic()
    proc = subprocess.run(
        command,
        cwd=ANDROID if command and command[0] == str(GRADLEW) else ROOT,
        env=env,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        timeout=timeout,
        check=False,
    )
    output = proc.stdout or ""
    return {
        "command": command,
        "exit_code": proc.returncode,
        "duration_seconds": round(time.monotonic() - started, 3),
        "output_tail": scrub(output),
    }


def adb(serial: str, *args: str, timeout: int = 90, binary: bool = False) -> subprocess.CompletedProcess[Any]:
    # Critical invariant: no post-inventory ADB command is ever unscoped.
    return subprocess.run(
        ["adb", "-s", serial, *args],
        cwd=ROOT,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        timeout=timeout,
        check=True,
        text=not binary,
    )


def connected_devices() -> list[tuple[str, str]]:
    output = subprocess.check_output(["adb", "devices", "-l"], cwd=ROOT, text=True)
    rows: list[tuple[str, str]] = []
    for line in output.splitlines()[1:]:
        fields = line.split()
        if len(fields) >= 2 and fields[1] == "device":
            rows.append((fields[0], " ".join(fields[2:])))
    return rows


def property_value(serial: str, key: str) -> str:
    return adb(serial, "shell", "getprop", key).stdout.strip()


def verify_emulator(serial: str) -> dict[str, str]:
    props = {
        "serial": serial,
        "qemu": property_value(serial, "ro.kernel.qemu"),
        "boot_qemu": property_value(serial, "ro.boot.qemu"),
        "hardware": property_value(serial, "ro.hardware"),
        "characteristics": property_value(serial, "ro.build.characteristics"),
        "fingerprint": property_value(serial, "ro.build.fingerprint"),
        "model": property_value(serial, "ro.product.model"),
        "manufacturer": property_value(serial, "ro.product.manufacturer"),
        "api_level": property_value(serial, "ro.build.version.sdk"),
    }
    hardware = props["hardware"].lower()
    chars = props["characteristics"].lower()
    fingerprint = props["fingerprint"].lower()
    emulator_markers = (
        hardware in {"ranchu", "goldfish"}
        or "emulator" in chars
        or any(marker in fingerprint for marker in ("generic", "sdk_gphone", "emulator"))
    )
    if props["qemu"] != "1" or not emulator_markers:
        raise RuntimeError("target failed emulator system-property verification")
    return props


def select_emulator(requested: str | None) -> tuple[dict[str, str], list[dict[str, str]]]:
    # Parse the initial inventory only to extract emulator serials. Do not
    # retain or write physical-device identifiers/descriptors into reports.
    candidates = [serial for serial, _descriptor in connected_devices() if serial.startswith("emulator-")]
    if requested:
        if requested not in candidates:
            raise RuntimeError("--serial must identify a connected emulator; physical devices are rejected")
        selected = requested
    elif len(candidates) == 1:
        selected = candidates[0]
    else:
        raise RuntimeError("connect exactly one Android Emulator or specify --serial for a verified emulator")
    emulator_candidates = [{"serial": serial} for serial in candidates]
    return verify_emulator(selected), emulator_candidates


def free_bytes(serial: str) -> int:
    output = adb(serial, "shell", "df", "-k", "/data").stdout
    lines = [line.split() for line in output.splitlines() if line.strip()]
    if len(lines) < 2:
        raise RuntimeError("could not read emulator /data free space")
    values = lines[-1]
    # POSIX df reports blocks, used, available; Android's toybox output does too.
    return int(values[3]) * 1024


def parse_junit(start_epoch: float) -> dict[str, int]:
    totals = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0, "suites": 0}
    results = APP / "build/test-results/testDebugUnitTest"
    if not results.exists():
        return totals
    for path in results.glob("TEST-*.xml"):
        try:
            if path.stat().st_mtime < start_epoch - 5:
                continue
            root = ET.parse(path).getroot()
            suite_nodes = [root] if root.tag == "testsuite" else list(root.iter("testsuite"))
            for suite in suite_nodes:
                totals["suites"] += 1
                for key in ("tests", "failures", "errors", "skipped"):
                    totals[key] += int(suite.attrib.get(key, "0"))
        except (OSError, ET.ParseError, ValueError):
            continue
    return totals


def test_environment(serial: str) -> dict[str, str]:
    env = os.environ.copy()
    env["ANDROID_SERIAL"] = serial
    env.setdefault("JAVA_TOOL_OPTIONS", "-XX:TieredStopAtLevel=1")
    return env


def screenshot(serial: str, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    result = adb(serial, "exec-out", "screencap", "-p", binary=True)
    data = result.stdout
    if not isinstance(data, bytes) or not data.startswith(b"\x89PNG\r\n\x1a\n"):
        raise RuntimeError("emulator screenshot was not a PNG")
    path.write_bytes(data)


def run_device_smoke(serial: str, env: dict[str, str], out_dir: Path) -> list[dict[str, Any]]:
    steps: list[dict[str, Any]] = []
    package_info = adb(serial, "shell", "dumpsys", "package", PACKAGE).stdout
    first_before = re.search(r"firstInstallTime=([^\s]+(?:\s+[^\s]+)?)", package_info)
    first_install_before = first_before.group(1).strip() if first_before else None
    build = run([str(GRADLEW), ":app:assembleDebug"], timeout=1200, env=env)
    steps.append({"name": "assemble_debug", **build})
    if build["exit_code"] != 0:
        return steps
    apk = APP / "build/outputs/apk/debug/app-debug.apk"
    install_started = time.monotonic()
    install = adb(serial, "install", "-r", str(apk), timeout=240)
    install_output = install.stdout.strip()
    steps.append({
        "name": "install_debug_update",
        "exit_code": 0,
        "duration_seconds": round(time.monotonic() - install_started, 3),
        "result": install_output,
    })
    launch_started = time.monotonic()
    launch = adb(serial, "shell", "am", "start", "-W", "-n", f"{PACKAGE}/.MainActivity", timeout=90)
    launch_output = launch.stdout.strip()
    launch_duration = round(time.monotonic() - launch_started, 3)
    activity_manager_time = re.search(r"TotalTime:\s*(\d+)", launch_output)
    steps.append({"name": "launch_main_activity", "exit_code": 0,
                  "duration_seconds": launch_duration,
                  "activity_manager_startup_ms": int(activity_manager_time.group(1)) if activity_manager_time else None})
    # Room/SQLCipher may still be opening and reconciling startup state just
    # after ActivityManager reports the Activity as launched. Emulator
    # observation showed the protected profile finishing its first read after
    # this point; capture after the initial screen has had time to settle.
    settle_seconds = 15
    time.sleep(settle_seconds)
    shot = out_dir / "screenshots" / "main-activity.png"
    screenshot(serial, shot)
    steps.append({"name": "capture_main_activity", "exit_code": 0,
                  "settle_wait_seconds": settle_seconds, "screenshot": str(shot)})
    pm = adb(serial, "shell", "dumpsys", "package", PACKAGE).stdout
    match = re.search(r"versionCode=(\d+).*?versionName=([^\s]+)", pm, re.S)
    first_after_match = re.search(r"firstInstallTime=([^\s]+(?:\s+[^\s]+)?)", pm)
    first_install_after = first_after_match.group(1).strip() if first_after_match else None
    lineage_unchanged = first_install_before is None or first_install_before == first_install_after
    steps.append({
        "name": "package_readback",
        "exit_code": 0 if lineage_unchanged and match else 1,
        "version_code": int(match.group(1)) if match else None,
        "version_name": match.group(2) if match else None,
        "first_install_time_before": first_install_before,
        "first_install_time_after": first_install_after,
        "first_install_time_unchanged": lineage_unchanged,
    })
    return steps


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("preflight", "quick", "full", "device-smoke"))
    parser.add_argument("--serial", help="explicit connected emulator serial; physical serials are rejected")
    parser.add_argument("--min-host-free-gib", type=float, default=2.0)
    parser.add_argument("--min-emulator-free-gib", type=float, default=1.0)
    args = parser.parse_args()

    run_id = utc_stamp()
    out_dir = REPORT_ROOT / run_id
    out_dir.mkdir(parents=True, exist_ok=True)
    report: dict[str, Any] = {
        "schema_version": 1,
        "run_id": run_id,
        "started_at_utc": dt.datetime.now(dt.timezone.utc).isoformat(),
        "mode": args.mode,
        "policy": {
            "emulator_only": True,
            "physical_devices_used": False,
            "live_ai_api_used": False,
            "real_user_data_used": False,
            "minimum_host_free_gib": args.min_host_free_gib,
            "minimum_emulator_free_gib": args.min_emulator_free_gib,
        },
        "steps": [],
    }
    host_free = shutil.disk_usage(ROOT).free
    report["host_free_bytes"] = host_free
    report["git"] = {}
    try:
        report["git"]["branch"] = subprocess.check_output(["git", "branch", "--show-current"], cwd=ROOT, text=True).strip()
        report["git"]["head"] = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
        if report["git"]["branch"] != "ui/redesign-v1":
            raise RuntimeError("acceptance is restricted to ui/redesign-v1")
        if host_free < int(args.min_host_free_gib * 1024**3):
            raise RuntimeError("host free space is below configured safety threshold")
        identity, inventory = select_emulator(args.serial)
        report["emulators_available"] = inventory
        report["emulator"] = identity
        emulator_free = free_bytes(identity["serial"])
        report["emulator_free_bytes"] = emulator_free
        if emulator_free < int(args.min_emulator_free_gib * 1024**3):
            raise RuntimeError("emulator /data free space is below configured safety threshold")
        report["steps"].append({"name": "emulator_and_disk_preflight", "status": "PASS"})
        if args.mode == "quick":
            init_script = test_force_init_script(out_dir)
            command = [str(GRADLEW), ":app:testDebugUnitTest"]
            for test in QUICK_TESTS:
                command.extend(["--tests", test])
            # Force only the test task so it refreshes JUnit XML every time;
            # keep compilation and resource tasks incremental.
            command.extend(["--init-script", str(init_script), "--no-daemon", "--console=plain", "--max-workers=1"])
            started = time.time()
            result = run(command, timeout=1800, env=test_environment(identity["serial"]))
            result["junit"] = parse_junit(started)
            junit = result["junit"]
            passed = result["exit_code"] == 0 and junit["tests"] > 0 and junit["failures"] == 0 and junit["errors"] == 0
            report["steps"].append({"name": "quick_synthetic_unit_suite", "status": "PASS" if passed else "FAIL", **result})
        elif args.mode == "full":
            init_script = test_force_init_script(out_dir)
            started = time.time()
            result = run([str(GRADLEW), *FULL_TASKS, "--init-script", str(init_script), "--no-daemon", "--console=plain", "--max-workers=1"], timeout=3600,
                         env=test_environment(identity["serial"]))
            result["junit"] = parse_junit(started)
            junit = result["junit"]
            passed = result["exit_code"] == 0 and junit["tests"] > 0 and junit["failures"] == 0 and junit["errors"] == 0
            report["steps"].append({"name": "full_gradle_acceptance", "status": "PASS" if passed else "FAIL", **result})
        elif args.mode == "device-smoke":
            report["steps"].extend(run_device_smoke(identity["serial"], test_environment(identity["serial"]), out_dir))
        report["status"] = "FAIL" if any(step.get("status") == "FAIL" or step.get("exit_code", 0) != 0 for step in report["steps"]) else "PASS"
    except Exception as error:
        report["status"] = "BLOCKED"
        report["blocker"] = scrub(f"{type(error).__name__}: {error}")
    report["finished_at_utc"] = dt.datetime.now(dt.timezone.utc).isoformat()
    report["report_path"] = str(out_dir / "report.json")
    (out_dir / "report.json").write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2, ensure_ascii=False))
    return 0 if report["status"] == "PASS" else 1


if __name__ == "__main__":
    sys.exit(main())
