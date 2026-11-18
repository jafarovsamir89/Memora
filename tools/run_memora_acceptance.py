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
import math
import os
import re
import shutil
import struct
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
TEST_PACKAGE = f"{PACKAGE}.test"
LINEAGE_RUNNER = f"{TEST_PACKAGE}/ai.memora.app.GeminiSyntheticProbe"
SYNTHETIC_USER_NAME = "MemoraReliabilityLab"
SYNTHETIC_VOLUME_USER_NAME = "MemoraCleanInstallLab"
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


def volume_probe_measurements(fields: dict[str, str]) -> dict[str, Any]:
    numeric_suffixes = ("_ms", "_us", "_kib", "_bytes", "_rows", "_count", "_turns", "_pages", "_run")
    measurements: dict[str, Any] = {
        key: int(value) for key, value in fields.items()
        if key.endswith(numeric_suffixes) and value.isdigit()
    }
    if "conversation_pagination_exact" in fields:
        measurements["conversation_pagination_exact"] = fields["conversation_pagination_exact"].lower() == "true"
    return measurements


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


def ui_tree(serial: str, out_dir: Path, name: str) -> ET.Element:
    """Capture the current Compose accessibility tree from the selected emulator."""
    out_dir.mkdir(parents=True, exist_ok=True)
    remote_path = "/sdcard/memora-acceptance-window.xml"
    local_path = out_dir / f"{name}.xml"
    adb(serial, "shell", "uiautomator", "dump", remote_path, timeout=60)
    adb(serial, "pull", remote_path, str(local_path), timeout=60)
    try:
        return ET.parse(local_path).getroot()
    except (OSError, ET.ParseError) as error:
        raise RuntimeError("could not parse emulator UI hierarchy") from error


def ui_texts(root: ET.Element) -> set[str]:
    return {node.attrib.get("text", "") for node in root.iter("node") if node.attrib.get("text", "")}


def ui_bounds(value: str) -> tuple[int, int, int, int]:
    match = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", value)
    if not match:
        raise RuntimeError("emulator UI node has invalid bounds")
    return tuple(int(part) for part in match.groups())


def tap_ui_text(serial: str, out_dir: Path, name: str, text: str,
                minimum_center_y_fraction: float = 0.0,
                maximum_center_y_fraction: float = 1.0) -> None:
    root = ui_tree(serial, out_dir, name)
    root_bounds_value = root.attrib.get("bounds", "")
    if not root_bounds_value:
        root_bounds_value = next((node.attrib.get("bounds", "") for node in root.iter("node")
                                  if node.attrib.get("bounds")), "")
    root_bounds = ui_bounds(root_bounds_value)
    screen_height = root_bounds[3] - root_bounds[1]
    candidates: list[tuple[int, int]] = []
    for node in root.iter("node"):
        if node.attrib.get("text") != text:
            continue
        left, top, right, bottom = ui_bounds(node.attrib.get("bounds", ""))
        center_y = (top + bottom) // 2
        center_fraction = center_y / screen_height if screen_height > 0 else 0.0
        if minimum_center_y_fraction <= center_fraction <= maximum_center_y_fraction:
            candidates.append(((left + right) // 2, center_y))
    if len(candidates) != 1:
        raise RuntimeError(f"expected one visible UI target for {text!r}; found {len(candidates)}")
    x, y = candidates[0]
    adb(serial, "shell", "input", "tap", str(x), str(y))


def swipe_ui(serial: str, out_dir: Path, name: str,
             start_x_fraction: float, start_y_fraction: float,
             end_x_fraction: float, end_y_fraction: float) -> None:
    """Swipe within the current emulator UI using screen-relative coordinates."""
    root = ui_tree(serial, out_dir, name)
    root_bounds_value = root.attrib.get("bounds", "")
    if not root_bounds_value:
        root_bounds_value = next((node.attrib.get("bounds", "") for node in root.iter("node")
                                  if node.attrib.get("bounds")), "")
    left, top, right, bottom = ui_bounds(root_bounds_value)
    width, height = right - left, bottom - top
    if width <= 0 or height <= 0:
        raise RuntimeError("emulator UI has invalid dimensions for swipe")
    coordinates = (
        left + round(width * start_x_fraction), top + round(height * start_y_fraction),
        left + round(width * end_x_fraction), top + round(height * end_y_fraction),
    )
    if any(value < 0 for value in coordinates) or not (
        0.0 <= start_x_fraction <= 1.0 and 0.0 <= end_x_fraction <= 1.0
        and 0.0 <= start_y_fraction <= 1.0 and 0.0 <= end_y_fraction <= 1.0
    ):
        raise RuntimeError("emulator UI swipe fractions are out of bounds")
    adb(serial, "shell", "input", "swipe", *(str(value) for value in coordinates), "500")


def scroll_to_top_and_wait_for_ui_texts(serial: str, out_dir: Path, name: str,
                                        expected: set[str]) -> ET.Element:
    """Restore the top of a saved detail scroll position before checking header fields."""
    swipe_ui(serial, out_dir, f"{name}-scroll-to-top", 0.5, 0.34, 0.5, 0.76)
    return wait_for_ui_texts(serial, out_dir, name, expected)


def wait_for_ui_texts(serial: str, out_dir: Path, name: str, expected: set[str],
                      timeout_seconds: float = 25.0) -> ET.Element:
    deadline = time.monotonic() + timeout_seconds
    last_texts: set[str] = set()
    last_error: Exception | None = None
    attempt = 0
    while time.monotonic() < deadline:
        attempt += 1
        try:
            root = ui_tree(serial, out_dir, f"{name}-{attempt}")
            last_texts = ui_texts(root)
            if expected.issubset(last_texts):
                return root
            last_error = None
        except (RuntimeError, subprocess.CalledProcessError, subprocess.TimeoutExpired) as error:
            last_error = error
        time.sleep(0.35)
    if last_error:
        raise RuntimeError(f"emulator UI did not stabilize for {name}: {type(last_error).__name__}") from last_error
    missing = sorted(expected - last_texts)
    raise RuntimeError(f"expected emulator UI text not visible for {name}: {missing}")


def capture_finance_ui_journey(serial: str, out_dir: Path) -> list[str]:
    """Drive exact Finance and linked Case → Person → Finance → Task → source routes."""
    screenshots = out_dir / "screenshots"
    screenshot_names: list[str] = []
    adb(serial, "shell", "am", "force-stop", "--user", "10", PACKAGE)
    adb(serial, "shell", "am", "start", "--user", "10", "-n", f"{PACKAGE}/.MainActivity")
    wait_for_ui_texts(serial, out_dir, "today-ready", {"Katibə"})
    tap_ui_text(serial, out_dir, "today-before-secretary", "Katibə", minimum_center_y_fraction=0.8)
    bmw_user_message = "Sintetik ssenari: Synthetic Elçin BMW təmiri üçün 100 AZN avans verdi; servis görüşünü təsdiqlə."
    bmw_model_message = "Sintetik ssenari: 300 AZN razılaşdırılıb, 100 AZN avans alınıb və 200 AZN qalıb; görüş tapşırığı yaradılıb."
    wait_for_ui_texts(serial, out_dir, "secretary-bmw-advance-turn", {
        "Bölməni aç Maliyyə",
        "Bölməni aç Tapşırıqlar",
    })
    screenshot_path = screenshots / "p0-3-bmw-conversation-actions.png"
    screenshot(serial, screenshot_path)
    screenshot_names.append(str(screenshot_path.relative_to(ROOT)))

    tap_ui_text(serial, out_dir, "secretary-before-bmw-advance", "Bölməni aç Maliyyə")
    finance_expected = {
        "100,00 AZN",
        "Synthetic P0.3 Receipt Person",
        "Daxil olan pul",
        "Synthetic BMW repair advance received.",
        "İlkin söhbət",
    }
    scroll_to_top_and_wait_for_ui_texts(serial, out_dir, "bmw-advance-detail", finance_expected)
    screenshot_path = screenshots / "p0-3-bmw-advance-detail.png"
    screenshot(serial, screenshot_path)
    screenshot_names.append(str(screenshot_path.relative_to(ROOT)))

    swipe_ui(serial, out_dir, "advance-finance-scroll-to-case", 0.5, 0.72, 0.5, 0.34)
    wait_for_ui_texts(serial, out_dir, "advance-finance-linked-case", {"Synthetic BMW repair"})
    tap_ui_text(serial, out_dir, "advance-finance-before-case", "Synthetic BMW repair")
    case_expected = {"Synthetic BMW repair", "Synthetic P0.3 Receipt Person",
                     "Növbəti addım: Review the linked synthetic receipt"}
    wait_for_ui_texts(serial, out_dir, "case-detail", case_expected)
    screenshot_path = screenshots / "p0-3-case-detail.png"
    screenshot(serial, screenshot_path)
    screenshot_names.append(str(screenshot_path.relative_to(ROOT)))

    tap_ui_text(serial, out_dir, "case-before-person", "Synthetic P0.3 Receipt Person",
                maximum_center_y_fraction=0.7)
    person_expected = {"Synthetic P0.3 Receipt Person", "Maliyyə"}
    wait_for_ui_texts(serial, out_dir, "person-detail", person_expected)
    screenshot_path = screenshots / "p0-3-person-detail.png"
    screenshot(serial, screenshot_path)
    screenshot_names.append(str(screenshot_path.relative_to(ROOT)))

    tap_ui_text(serial, out_dir, "person-before-finance-section", "Maliyyə")
    person_finance_expected = {
        "100 AZN",
        "Synthetic BMW repair advance received.",
    }
    wait_for_ui_texts(serial, out_dir, "person-finance", person_finance_expected)
    screenshot_path = screenshots / "p0-3-person-finance.png"
    screenshot(serial, screenshot_path)
    screenshot_names.append(str(screenshot_path.relative_to(ROOT)))

    tap_ui_text(serial, out_dir, "person-finance-before-advance", "Synthetic BMW repair advance received.")
    scroll_to_top_and_wait_for_ui_texts(
        serial, out_dir, "case-linked-bmw-advance-detail", finance_expected
    )
    screenshot_path = screenshots / "p0-3-case-linked-finance-detail.png"
    screenshot(serial, screenshot_path)
    screenshot_names.append(str(screenshot_path.relative_to(ROOT)))

    task_title = "Synthetic confirm BMW repair appointment"
    swipe_ui(serial, out_dir, "finance-scroll-to-related-task", 0.5, 0.72, 0.5, 0.36)
    swipe_ui(serial, out_dir, "finance-scroll-to-related-task-again", 0.5, 0.72, 0.5, 0.42)
    wait_for_ui_texts(serial, out_dir, "finance-related-task", {task_title})
    screenshot_path = screenshots / "p0-3-finance-related-task.png"
    screenshot(serial, screenshot_path)
    screenshot_names.append(str(screenshot_path.relative_to(ROOT)))

    tap_ui_text(serial, out_dir, "finance-before-related-task", task_title)
    task_expected = {task_title, "Açıq", "İlkin söhbət"}
    wait_for_ui_texts(serial, out_dir, "exact-task-detail", task_expected)
    screenshot_path = screenshots / "p0-3-task-detail.png"
    screenshot(serial, screenshot_path)
    screenshot_names.append(str(screenshot_path.relative_to(ROOT)))

    tap_ui_text(serial, out_dir, "task-before-exact-source", "İlkin söhbət",
                minimum_center_y_fraction=0.52)
    task_source_expected = {
        bmw_user_message,
        bmw_model_message,
        "Yadda saxlanıldı",
    }
    wait_for_ui_texts(serial, out_dir, "task-exact-source", task_source_expected)
    screenshot_path = screenshots / "p0-3-task-exact-source.png"
    screenshot(serial, screenshot_path)
    screenshot_names.append(str(screenshot_path.relative_to(ROOT)))

    swipe_ui(serial, out_dir, "task-source-scroll-to-conversation", 0.5, 0.76, 0.5, 0.3)
    wait_for_ui_texts(serial, out_dir, "task-source-open-conversation", {"Söhbəti katibədə aç"})
    tap_ui_text(serial, out_dir, "task-source-open-exact-conversation", "Söhbəti katibədə aç")
    wait_for_ui_texts(serial, out_dir, "task-source-exact-conversation", task_source_expected)
    screenshot_path = screenshots / "p0-3-task-source-exact-conversation.png"
    screenshot(serial, screenshot_path)
    screenshot_names.append(str(screenshot_path.relative_to(ROOT)))

    adb(serial, "shell", "input", "keyevent", "4")
    wait_for_ui_texts(serial, out_dir, "task-conversation-back-to-source", {"Söhbəti katibədə aç"})
    screenshot_path = screenshots / "p0-3-task-conversation-back-to-source.png"
    screenshot(serial, screenshot_path)
    screenshot_names.append(str(screenshot_path.relative_to(ROOT)))
    adb(serial, "shell", "input", "keyevent", "4")
    wait_for_ui_texts(serial, out_dir, "task-source-back-to-task", task_expected)
    adb(serial, "shell", "input", "keyevent", "4")
    swipe_ui(serial, out_dir, "finance-return-scroll-to-top", 0.5, 0.34, 0.5, 0.76)
    wait_for_ui_texts(serial, out_dir, "task-back-to-finance", finance_expected)
    adb(serial, "shell", "input", "keyevent", "4")
    wait_for_ui_texts(serial, out_dir, "finance-back-to-person-finance", person_finance_expected | {"Maliyyə"})
    adb(serial, "shell", "input", "keyevent", "4")
    wait_for_ui_texts(serial, out_dir, "person-back-to-case", case_expected)
    screenshot_path = screenshots / "p0-3-back-to-case.png"
    screenshot(serial, screenshot_path)
    screenshot_names.append(str(screenshot_path.relative_to(ROOT)))
    adb(serial, "shell", "input", "keyevent", "4")
    scroll_to_top_and_wait_for_ui_texts(
        serial, out_dir, "case-back-to-origin-finance", finance_expected
    )
    screenshot_path = screenshots / "p0-3-case-back-to-origin-finance.png"
    screenshot(serial, screenshot_path)
    screenshot_names.append(str(screenshot_path.relative_to(ROOT)))
    adb(serial, "shell", "input", "keyevent", "4")
    wait_for_ui_texts(serial, out_dir, "case-back-to-origin-secretary", {
        bmw_user_message,
        bmw_model_message,
    })
    screenshot_path = screenshots / "p0-3-case-back-to-origin-secretary.png"
    screenshot(serial, screenshot_path)
    screenshot_names.append(str(screenshot_path.relative_to(ROOT)))
    return screenshot_names


def package_metadata(package_info: str) -> tuple[int, str]:
    version_code = re.search(r"^[ \t]*versionCode=(\d+)", package_info, re.M)
    version_name = re.search(r"^[ \t]*versionName=([^\s]+)", package_info, re.M)
    if not version_code or not version_name:
        raise RuntimeError("could not read installed Memora package version")
    return int(version_code.group(1)), version_name.group(1)


def package_user_first_install_time(package_info: str, user_id: int) -> str | None:
    user_block = re.search(
        rf"^[ \t]*User {user_id}:.*?(?=^[ \t]*User \d+:|\Z)", package_info, re.M | re.S
    )
    first_install = re.search(r"^[ \t]*firstInstallTime=([^\n]+)", user_block.group(0), re.M) if user_block else None
    return first_install.group(1).strip() if first_install else None


def acceptance_debug_version(serial: str) -> tuple[int | None, str | None, list[str]]:
    package_info = adb(serial, "shell", "dumpsys", "package", PACKAGE).stdout
    try:
        installed_code, _installed_name = package_metadata(package_info)
    except RuntimeError:
        return None, None, []
    next_code = max(106, installed_code + 1)
    next_name = f"acceptance-lab-v{next_code}"
    return next_code, next_name, [
        f"-PmemoraAcceptanceVersionCode={next_code}",
        f"-PmemoraAcceptanceVersionName={next_name}",
    ]


def run_device_smoke(serial: str, env: dict[str, str], out_dir: Path) -> list[dict[str, Any]]:
    steps: list[dict[str, Any]] = []
    current_user = int(adb(serial, "shell", "am", "get-current-user").stdout.strip())
    package_info = adb(serial, "shell", "dumpsys", "package", PACKAGE).stdout
    first_install_before = package_user_first_install_time(package_info, current_user)
    version_code, version_name, version_args = acceptance_debug_version(serial)
    build = run([str(GRADLEW), ":app:assembleDebug", *version_args], timeout=1200, env=env)
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
    try:
        installed_code, installed_name = package_metadata(pm)
    except RuntimeError:
        installed_code, installed_name = None, None
    first_install_after = package_user_first_install_time(pm, current_user)
    expected_code = version_code if version_code is not None else installed_code
    expected_name = version_name if version_name is not None else installed_name
    lineage_unchanged = first_install_before is None or first_install_before == first_install_after
    steps.append({
        "name": "package_readback",
        "exit_code": 0 if lineage_unchanged and installed_code == expected_code and installed_name == expected_name else 1,
        "version_code": installed_code,
        "version_name": installed_name,
        "first_install_time_before": first_install_before,
        "first_install_time_after": first_install_after,
        "first_install_time_unchanged": lineage_unchanged,
    })
    return steps


def parse_activity_startup_ms(output: str) -> int:
    status = re.search(r"^Status:\s*(\S+)", output, re.M)
    total = re.search(r"^TotalTime:\s*(\d+)", output, re.M)
    if status is None or status.group(1).lower() != "ok" or total is None:
        raise RuntimeError("MainActivity start did not return Status: ok and TotalTime")
    return int(total.group(1))


def nearest_rank_percentile(values: list[int], percentile: int) -> int:
    if not values or percentile < 1 or percentile > 100:
        raise ValueError("percentile requires samples and a value in 1..100")
    ordered = sorted(values)
    return ordered[math.ceil(percentile * len(ordered) / 100) - 1]


def run_startup_probe(serial: str, user_id: int, sample_count: int,
                      out_dir: Path) -> dict[str, Any]:
    if user_id <= 0:
        raise RuntimeError("startup probe requires a non-owner synthetic Android user")
    if sample_count < 5 or sample_count > 50:
        raise RuntimeError("startup sample count must be between 5 and 50")
    users = adb(serial, "shell", "pm", "list", "users").stdout
    expected_users = (SYNTHETIC_USER_NAME, SYNTHETIC_VOLUME_USER_NAME)
    if not any(re.search(rf"UserInfo\{{{user_id}:{re.escape(name)}:", users) for name in expected_users):
        raise RuntimeError("startup probe accepts only a non-owner user with an exact synthetic test-user name")
    current_user = adb(serial, "shell", "am", "get-current-user").stdout.strip()
    if current_user != str(user_id):
        raise RuntimeError("switch the verified emulator to the selected synthetic user before startup probe")
    packages = adb(serial, "shell", "cmd", "package", "list", "packages", "--user", str(user_id)).stdout
    if f"package:{PACKAGE}\n" not in packages:
        raise RuntimeError("Memora must already be installed for the selected synthetic user")

    before = package_install_state(adb(serial, "shell", "dumpsys", "package", PACKAGE).stdout, user_id)
    if not before["installed_for_user"] or not before["first_install_time"]:
        raise RuntimeError("selected synthetic user has no readable Memora install lineage")

    samples: list[dict[str, int]] = []
    failure: str | None = None
    screenshot_path = out_dir / "screenshots" / "startup-probe-final.png"
    for sample_index in range(sample_count):
        adb(serial, "shell", "am", "force-stop", "--user", str(user_id), PACKAGE)
        started = time.monotonic()
        output = adb(serial, "shell", "am", "start", "-W", "--user", str(user_id),
                     "-n", f"{PACKAGE}/.MainActivity", timeout=120).stdout
        round_trip_ms = int(round((time.monotonic() - started) * 1000))
        try:
            activity_manager_ms = parse_activity_startup_ms(output)
        except RuntimeError as error:
            failure = str(error)
            break
        wait_time = re.search(r"^WaitTime:\s*(\d+)", output, re.M)
        samples.append({
            "sample": sample_index + 1,
            "activity_manager_total_ms": activity_manager_ms,
            "activity_manager_wait_ms": int(wait_time.group(1)) if wait_time else -1,
            "adb_round_trip_ms": round_trip_ms,
        })
        # Let the first frame and local profile read settle before the next
        # force-stop. This is not part of ActivityManager TotalTime.
        time.sleep(2)
    if samples:
        screenshot(serial, screenshot_path)

    after_user = adb(serial, "shell", "am", "get-current-user").stdout.strip()
    after = package_install_state(adb(serial, "shell", "dumpsys", "package", PACKAGE).stdout, user_id)
    install_lineage_unchanged = after["first_install_time"] == before["first_install_time"]
    startup_values = [sample["activity_manager_total_ms"] for sample in samples]
    passed = (failure is None and len(samples) == sample_count and after_user == str(user_id)
              and install_lineage_unchanged and after["version_code"] == before["version_code"])
    return {
        "name": "synthetic_cold_process_startup_probe",
        "status": "PASS" if passed else "FAIL",
        "synthetic_user_id": user_id,
        "sample_count_requested": sample_count,
        "sample_count_completed": len(samples),
        "activity_manager_total_p50_ms": nearest_rank_percentile(startup_values, 50) if startup_values else None,
        "activity_manager_total_p95_ms": nearest_rank_percentile(startup_values, 95) if startup_values else None,
        "activity_manager_wait_p50_ms": nearest_rank_percentile(
            [sample["activity_manager_wait_ms"] for sample in samples if sample["activity_manager_wait_ms"] >= 0], 50
        ) if any(sample["activity_manager_wait_ms"] >= 0 for sample in samples) else None,
        "activity_manager_wait_p95_ms": nearest_rank_percentile(
            [sample["activity_manager_wait_ms"] for sample in samples if sample["activity_manager_wait_ms"] >= 0], 95
        ) if any(sample["activity_manager_wait_ms"] >= 0 for sample in samples) else None,
        "adb_round_trip_p50_ms": nearest_rank_percentile([sample["adb_round_trip_ms"] for sample in samples], 50)
            if samples else None,
        "adb_round_trip_p95_ms": nearest_rank_percentile([sample["adb_round_trip_ms"] for sample in samples], 95)
            if samples else None,
        "samples": samples,
        "version_code": before["version_code"],
        "first_install_time_unchanged": install_lineage_unchanged,
        "current_synthetic_user_unchanged": after_user == str(user_id),
        "screenshot": str(screenshot_path) if samples else None,
        "screenshot_visual_review": "REQUIRED" if samples else "NOT CAPTURED",
        "measurement_scope": "MainActivity ActivityManager TotalTime after per-sample force-stop; process-cold, emulator storage/OS cache warm; no assistant request",
        "network_used_by_harness": False,
        "model_used": "NOT APPLICABLE",
        "cache_condition": "force-stop before each launch; no app data or OS caches cleared",
        "failure": failure,
    }


def package_install_state(package_info: str, user_id: int) -> dict[str, Any]:
    version_code = re.search(r"^[ \t]*versionCode=(\d+)", package_info, re.M)
    version_name = re.search(r"^[ \t]*versionName=([^\s]+)", package_info, re.M)
    user_block = re.search(
        rf"^[ \t]*User {user_id}:.*?(?=^[ \t]*User \d+:|\Z)", package_info, re.M | re.S
    )
    first_install = re.search(r"^[ \t]*firstInstallTime=([^\n]+)", user_block.group(0), re.M) if user_block else None
    if not version_code or not version_name or not user_block or not first_install:
        raise RuntimeError("could not read package install lineage for the selected synthetic user")
    return {
        "version_code": int(version_code.group(1)),
        "version_name": version_name.group(1),
        "first_install_time": first_install.group(1).strip(),
        "installed_for_user": "installed=true" in user_block.group(0),
    }


def screenshot_dimensions(path: Path) -> tuple[int, int]:
    data = path.read_bytes()
    if len(data) < 24 or not data.startswith(b"\x89PNG\r\n\x1a\n"):
        raise RuntimeError("emulator screenshot was not a PNG")
    return struct.unpack(">II", data[16:24])


def run_lineage_probe(serial: str, user_id: int, phase: str) -> dict[str, Any]:
    result = adb(
        serial,
        "shell", "am", "instrument", "--user", str(user_id), "-w",
        "-e", "allowSyntheticLocalInstallLineage", "true",
        "-e", "localInstallLineagePhase", phase,
        LINEAGE_RUNNER,
        timeout=180,
    )
    output = (result.stdout or "") + (result.stderr or "")
    found = re.search(r"INSTRUMENTATION_RESULT: status=([^\s]+)", output)
    expected = {
        "seed": "synthetic_lineage_seed_passed",
        "verify": "synthetic_lineage_verify_passed",
        "verify-new-install": "synthetic_lineage_new_install_passed",
    }[phase]
    fields = {
        match.group(1): match.group(2)
        for match in re.finditer(r"INSTRUMENTATION_RESULT: ([a-z_]+)=([^\s]+)", output)
    }
    sentinel_counts = {
        key: int(fields[key])
        for key in ("profile_facts", "cases", "people", "money_events", "task_events", "action_cards")
        if fields.get(key, "").isdigit()
    }
    return {
        "name": f"synthetic_lineage_{phase}",
        "status": "PASS" if found and found.group(1) == expected else "FAIL",
        "probe_status": found.group(1) if found else None,
        "sentinel_counts": sentinel_counts,
        "markerless_existing_profile_preserved":
            fields.get("markerless_existing_profile_preserved") == "true",
        "fresh_install_profile_empty": fields.get("profile_empty") == "true",
        "fresh_install_marker_new": fields.get("onboarding_marker_new") == "true",
    }


def run_user_launch(serial: str, user_id: int, out_dir: Path, screenshot_name: str) -> dict[str, Any]:
    started = time.monotonic()
    launch = adb(
        serial, "shell", "am", "start", "-W", "--user", str(user_id),
        "-n", f"{PACKAGE}/.MainActivity", timeout=120,
    )
    output = launch.stdout.strip()
    total = re.search(r"TotalTime:\s*(\d+)", output)
    time.sleep(15)
    shot = out_dir / "screenshots" / screenshot_name
    screenshot(serial, shot)
    return {
        "name": "launch_main_activity",
        "status": "PASS" if "Status: ok" in output and total else "FAIL",
        "duration_seconds": round(time.monotonic() - started, 3),
        "activity_manager_startup_ms": int(total.group(1)) if total else None,
        "screenshot": str(shot),
        "screenshot_visual_review": "REQUIRED",
    }


def run_rotation_recreation(serial: str, user_id: int, out_dir: Path) -> list[dict[str, Any]]:
    steps: list[dict[str, Any]] = []
    auto_value = adb(serial, "shell", "settings", "--user", str(user_id), "get", "system", "accelerometer_rotation").stdout.strip()
    rotation_value = adb(serial, "shell", "settings", "--user", str(user_id), "get", "system", "user_rotation").stdout.strip()
    if auto_value not in {"0", "1"} or rotation_value not in {"0", "1", "2", "3"}:
        return [{"name": "rotation_recreation", "status": "NOT TESTED", "reason": "original_rotation_settings_unavailable"}]

    original_auto = int(auto_value)
    original_rotation = int(rotation_value)
    landscape_rotation = 3 if original_rotation == 1 else 1
    restore_errors: list[str] = []
    try:
        launch = adb(
            serial, "shell", "am", "start", "-W", "--user", str(user_id),
            "-n", f"{PACKAGE}/.MainActivity", timeout=120,
        )
        if "Status: ok" not in launch.stdout:
            raise RuntimeError("could not launch MainActivity before rotation")
        time.sleep(5)
        adb(serial, "shell", "settings", "--user", str(user_id), "put", "system", "accelerometer_rotation", "0")
        adb(serial, "shell", "settings", "--user", str(user_id), "put", "system", "user_rotation", str(landscape_rotation))
        time.sleep(3)
        shot = out_dir / "screenshots" / "rotation-landscape.png"
        screenshot(serial, shot)
        width, height = screenshot_dimensions(shot)
        probe = run_lineage_probe(serial, user_id, "verify")
        steps.append({
            "name": "rotation_recreation",
            "status": "PASS" if width > height and probe["status"] == "PASS" else "FAIL",
            "screenshot": str(shot),
            "width": width,
            "height": height,
            "profile_readback": probe["status"],
            "screenshot_visual_review": "REQUIRED",
        })
    finally:
        for key, value in (("user_rotation", original_rotation), ("accelerometer_rotation", original_auto)):
            try:
                adb(serial, "shell", "settings", "--user", str(user_id), "put", "system", key, str(value))
                restored = adb(serial, "shell", "settings", "--user", str(user_id), "get", "system", key).stdout.strip()
                if restored != str(value):
                    restore_errors.append(key)
            except Exception:
                restore_errors.append(key)
    steps.append({
        "name": "restore_rotation_settings",
        "status": "PASS" if not restore_errors else "FAIL",
        "failed_settings": restore_errors,
    })
    return steps


def run_device_lineage(serial: str, user_id: int, seed_profile: bool,
                       env: dict[str, str], out_dir: Path, steps: list[dict[str, Any]]) -> None:
    if user_id <= 0:
        raise RuntimeError("synthetic user id must be greater than owner user 0")
    users = adb(serial, "shell", "pm", "list", "users").stdout
    if not re.search(rf"UserInfo\{{{user_id}:{re.escape(SYNTHETIC_USER_NAME)}:", users):
        raise RuntimeError(f"user id {user_id} is not the explicitly named {SYNTHETIC_USER_NAME} test user")
    current_user = adb(serial, "shell", "am", "get-current-user").stdout.strip()
    if current_user != str(user_id):
        raise RuntimeError("switch the verified emulator to the named synthetic user before the lineage run")
    packages = adb(serial, "shell", "cmd", "package", "list", "packages", "--user", str(user_id)).stdout
    if f"package:{PACKAGE}\n" not in packages or f"package:{TEST_PACKAGE}\n" not in packages:
        raise RuntimeError("both Memora and its AndroidTest package must be installed for the synthetic user")
    steps.append({"name": "synthetic_user_preflight", "status": "PASS", "synthetic_user_id": user_id})

    if seed_profile:
        seed = run_lineage_probe(serial, user_id, "seed")
        steps.append(seed)
        if seed["status"] != "PASS":
            return

    if not seed_profile:
        before = run_lineage_probe(serial, user_id, "verify")
        steps.append(before)
        if before["status"] != "PASS":
            return

    launch = run_user_launch(serial, user_id, out_dir, "main-before-update.png")
    steps.append(launch)
    if launch["status"] != "PASS":
        return
    readback = run_lineage_probe(serial, user_id, "verify")
    steps.append(readback)
    if readback["status"] != "PASS":
        return

    initial_info = adb(serial, "shell", "dumpsys", "package", PACKAGE).stdout
    initial_state = package_install_state(initial_info, user_id)
    if not initial_state["installed_for_user"]:
        raise RuntimeError("Memora is not installed for the selected synthetic user")
    next_code = initial_state["version_code"] + 1
    next_name = f"acceptance-lab-v{next_code}"
    command = [
        str(GRADLEW), ":app:assembleDebug",
        f"-PmemoraAcceptanceVersionCode={next_code}",
        f"-PmemoraAcceptanceVersionName={next_name}",
        "--no-daemon", "--console=plain", "--max-workers=1",
    ]
    build = run(command, timeout=1200, env=env)
    steps.append({"name": "assemble_higher_debug_version", **build})
    if build["exit_code"] != 0:
        return
    metadata_path = APP / "build/outputs/apk/debug/output-metadata.json"
    metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
    element = metadata["elements"][0]
    apk = APP / "build/outputs/apk/debug" / element["outputFile"]
    if metadata["applicationId"] != PACKAGE or int(element["versionCode"]) != next_code:
        steps.append({"name": "debug_apk_metadata", "status": "FAIL"})
        return
    steps.append({"name": "debug_apk_metadata", "status": "PASS",
                  "version_code": next_code, "version_name": element["versionName"]})

    verify_emulator(serial)
    install_started = time.monotonic()
    install = adb(serial, "install", "--user", str(user_id), "-r", str(apk), timeout=300)
    steps.append({"name": "install_higher_version_with_r", "status": "PASS",
                  "duration_seconds": round(time.monotonic() - install_started, 3),
                  "result": scrub(install.stdout.strip())})
    after_info = adb(serial, "shell", "dumpsys", "package", PACKAGE).stdout
    after_state = package_install_state(after_info, user_id)
    lineage_passed = after_state["first_install_time"] == initial_state["first_install_time"]
    version_passed = after_state["version_code"] == next_code and after_state["version_name"] == next_name
    steps.append({
        "name": "package_lineage_readback",
        "status": "PASS" if lineage_passed and version_passed else "FAIL",
        "version_code_before": initial_state["version_code"],
        "version_code_after": after_state["version_code"],
        "first_install_time_before": initial_state["first_install_time"],
        "first_install_time_after": after_state["first_install_time"],
        "first_install_time_unchanged": lineage_passed,
    })
    if not lineage_passed or not version_passed:
        return

    launch = run_user_launch(serial, user_id, out_dir, "main-after-update.png")
    steps.append(launch)
    if launch["status"] != "PASS":
        return
    readback = run_lineage_probe(serial, user_id, "verify")
    steps.append(readback)
    if readback["status"] != "PASS":
        return

    adb(serial, "shell", "am", "force-stop", "--user", str(user_id), PACKAGE)
    launch = run_user_launch(serial, user_id, out_dir, "main-after-process-restart.png")
    steps.append({**launch, "name": "force_stop_and_restart"})
    if launch["status"] != "PASS":
        return
    readback = run_lineage_probe(serial, user_id, "verify")
    steps.append(readback)
    steps.extend(run_rotation_recreation(serial, user_id, out_dir))


def run_synthetic_volume_probe(serial: str, user_id: int, env: dict[str, str],
                               volume_counts: list[int],
                               steps: list[dict[str, Any]]) -> None:
    if user_id <= 0:
        raise RuntimeError("synthetic user id must be greater than owner user 0")
    users = adb(serial, "shell", "pm", "list", "users").stdout
    if not re.search(rf"UserInfo\{{{user_id}:{re.escape(SYNTHETIC_VOLUME_USER_NAME)}:", users):
        raise RuntimeError(f"user id {user_id} is not the explicitly named {SYNTHETIC_VOLUME_USER_NAME} test user")
    current_user = adb(serial, "shell", "am", "get-current-user").stdout.strip()
    if current_user != str(user_id):
        raise RuntimeError("switch the verified emulator to the named synthetic volume user before the volume run")
    packages = adb(serial, "shell", "cmd", "package", "list", "packages", "--user", str(user_id)).stdout
    if f"package:{PACKAGE}\n" not in packages or f"package:{TEST_PACKAGE}\n" not in packages:
        raise RuntimeError("both Memora and its AndroidTest package must be installed for the synthetic user")
    steps.append({"name": "synthetic_user_preflight", "status": "PASS", "synthetic_user_id": user_id})

    before = package_install_state(adb(serial, "shell", "dumpsys", "package", PACKAGE).stdout, user_id)
    next_code = before["version_code"] + 1
    next_name = f"acceptance-lab-v{next_code}"
    build = run([
        str(GRADLEW), ":app:assembleDebug", ":app:assembleDebugAndroidTest",
        f"-PmemoraAcceptanceVersionCode={next_code}",
        f"-PmemoraAcceptanceVersionName={next_name}",
        "--no-daemon", "--console=plain", "--max-workers=1",
    ], timeout=1200, env=env)
    steps.append({"name": "assemble_debug_and_test_apks", **build})
    if build["exit_code"] != 0:
        return

    app_apk = APP / "build/outputs/apk/debug/app-debug.apk"
    test_apk = APP / "build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
    if not app_apk.is_file() or not test_apk.is_file():
        steps.append({"name": "debug_apk_outputs", "status": "FAIL", "reason": "expected_apk_missing"})
        return
    install_steps = []
    for name, apk in (("install_app_for_synthetic_user", app_apk), ("install_android_test_for_synthetic_user", test_apk)):
        started = time.monotonic()
        installed = adb(serial, "install", "--user", str(user_id), "-r", str(apk), timeout=300)
        install_steps.append({"name": name, "status": "PASS", "duration_seconds": round(time.monotonic() - started, 3),
                              "result": scrub(installed.stdout.strip())})
    steps.extend(install_steps)
    after = package_install_state(adb(serial, "shell", "dumpsys", "package", PACKAGE).stdout, user_id)
    lineage_ok = after["version_code"] == next_code and after["version_name"] == next_name and \
        after["first_install_time"] == before["first_install_time"]
    steps.append({"name": "package_update_lineage", "status": "PASS" if lineage_ok else "FAIL",
                  "version_code_before": before["version_code"], "version_code_after": after["version_code"],
                  "first_install_time_unchanged": after["first_install_time"] == before["first_install_time"]})
    if not lineage_ok:
        return

    for count in volume_counts:
        started = time.monotonic()
        probe = adb(
            serial, "shell", "am", "instrument", "--user", str(user_id), "-w",
            "-e", "allowSyntheticVolumeProbe", "true",
            "-e", "syntheticVolumeCount", str(count),
            "ai.memora.app.test/ai.memora.app.GeminiSyntheticProbe",
            timeout=300,
        )
        output = (probe.stdout or "") + (probe.stderr or "")
        fields = {match.group(1): match.group(2) for match in
                  re.finditer(r"INSTRUMENTATION_RESULT: ([a-z0-9_]+)=([^\s]+)", output)}
        passed = fields.get("status") == "synthetic_volume_probe_passed"
        numeric = volume_probe_measurements(fields)
        steps.append({
            "name": f"synthetic_cross_domain_volume_{count}",
            "status": "PASS" if passed else "FAIL",
            "duration_seconds": round(time.monotonic() - started, 3),
            "probe_status": fields.get("status"),
            "probe_stage": fields.get("stage"),
            "stage_error": fields.get("stage_error"),
            "measurements": numeric,
            "measurement_scope": "warm local Room/repository and encrypted task-store reads; 20 samples per path; sub-ms paths in microseconds; no UI/network",
            "network_used": False,
            "model_used": "NOT APPLICABLE",
            "cache_condition": "one warmup read before timed samples",
            "fixture_digest_sha256": fields.get("fixture_digest_sha256"),
            "cross_domain_fixture_digest_sha256": fields.get("cross_domain_fixture_digest_sha256"),
            "task_fixture_digest_sha256": fields.get("task_fixture_digest_sha256"),
            "person_fixture_digest_sha256": fields.get("person_fixture_digest_sha256"),
            "conversation_volume_status": fields.get("conversation_volume_status"),
            "conversation_fixture_digest_sha256": fields.get("conversation_fixture_digest_sha256"),
            "conversation_oldest_search_expected_turn": fields.get("conversation_oldest_search_expected_turn"),
            "conversation_measurement_scope": fields.get("conversation_measurement_scope"),
        })
        if not passed:
            break


def run_synthetic_finance_ui_journey_probe(serial: str, user_id: int,
                                           out_dir: Path,
                                           steps: list[dict[str, Any]]) -> None:
    """Idempotently prepare and exercise finance/case/person/source fixtures in lineage user 10 only."""
    if user_id <= 0:
        raise RuntimeError("synthetic user id must be greater than owner user 0")
    users = adb(serial, "shell", "pm", "list", "users").stdout
    if not re.search(rf"UserInfo\{{{user_id}:{re.escape(SYNTHETIC_USER_NAME)}:", users):
        raise RuntimeError(f"user id {user_id} is not the explicitly named {SYNTHETIC_USER_NAME} test user")
    if user_id != 10:
        raise RuntimeError("persistent finance UI journey fixture is restricted to the lineage synthetic user 10")
    current_user = adb(serial, "shell", "am", "get-current-user").stdout.strip()
    if current_user != str(user_id):
        raise RuntimeError("switch the verified emulator to synthetic user 10 before the UI journey fixture probe")
    packages = adb(serial, "shell", "cmd", "package", "list", "packages", "--user", str(user_id)).stdout
    if f"package:{PACKAGE}\n" not in packages or f"package:{TEST_PACKAGE}\n" not in packages:
        raise RuntimeError("Memora and its AndroidTest package must already be installed for synthetic user 10")
    package_state = package_install_state(adb(serial, "shell", "dumpsys", "package", PACKAGE).stdout, user_id)
    if not package_state["installed_for_user"]:
        raise RuntimeError("Memora is not installed for the selected synthetic user")
    steps.append({"name": "synthetic_ui_journey_user_preflight", "status": "PASS",
                  "synthetic_user_id": user_id, "version_code": package_state["version_code"],
                  "version_name": package_state["version_name"]})

    next_code = package_state["version_code"] + 1
    next_name = f"acceptance-lab-v{next_code}"
    build = run([
        str(GRADLEW), ":app:assembleDebug", ":app:assembleDebugAndroidTest",
        f"-PmemoraAcceptanceVersionCode={next_code}",
        f"-PmemoraAcceptanceVersionName={next_name}",
        "--no-daemon", "--console=plain", "--max-workers=1",
    ], timeout=1200, env=test_environment(serial))
    steps.append({"name": "assemble_current_debug_and_android_test_apks", **build})
    if build["exit_code"] != 0:
        return
    metadata_path = APP / "build/outputs/apk/debug/output-metadata.json"
    app_apk = APP / "build/outputs/apk/debug/app-debug.apk"
    test_apk = APP / "build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
    if not metadata_path.is_file() or not app_apk.is_file() or not test_apk.is_file():
        steps.append({"name": "debug_apk_outputs", "status": "FAIL", "reason": "expected_apk_missing"})
        return
    metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
    element = metadata.get("elements", [{}])[0]
    if metadata.get("applicationId") != PACKAGE or int(element.get("versionCode", -1)) != next_code or \
            element.get("versionName") != next_name:
        steps.append({"name": "debug_apk_metadata", "status": "FAIL", "reason": "unexpected_package_or_version"})
        return
    steps.append({"name": "debug_apk_metadata", "status": "PASS",
                  "version_code": next_code, "version_name": next_name})
    verify_emulator(serial)
    for name, apk in (("install_current_app_for_synthetic_user", app_apk),
                      ("install_current_android_test_for_synthetic_user", test_apk)):
        install_started = time.monotonic()
        install = adb(serial, "install", "--user", str(user_id), "-r", str(apk), timeout=300)
        steps.append({"name": name, "status": "PASS",
                      "duration_seconds": round(time.monotonic() - install_started, 3),
                      "result": scrub(install.stdout.strip())})
    after_state = package_install_state(adb(serial, "shell", "dumpsys", "package", PACKAGE).stdout, user_id)
    update_passed = after_state["version_code"] == next_code and after_state["version_name"] == next_name and \
        after_state["first_install_time"] == package_state["first_install_time"]
    steps.append({"name": "package_update_lineage", "status": "PASS" if update_passed else "FAIL",
                  "version_code_before": package_state["version_code"],
                  "version_code_after": after_state["version_code"],
                  "first_install_time_unchanged": after_state["first_install_time"] == package_state["first_install_time"]})
    if not update_passed:
        return
    started = time.monotonic()
    probe = adb(
        serial, "shell", "am", "instrument", "--user", str(user_id), "-w",
        "-e", "syntheticFinanceUiJourneyProbe", "true",
        "-e", "allowSyntheticFinanceUiJourney", "true",
        LINEAGE_RUNNER,
        timeout=180,
    )
    output = (probe.stdout or "") + (probe.stderr or "")
    fields = {match.group(1): match.group(2) for match in
              re.finditer(r"INSTRUMENTATION_RESULT: ([a-z0-9_]+)=([^\s]+)", output)}
    passed, fixture_checks = validate_bmw_ui_fixture_readback(fields)
    steps.append({
        "name": "synthetic_finance_ui_journey_fixture",
        "status": "PASS" if passed else "FAIL",
        "duration_seconds": round(time.monotonic() - started, 3),
        "probe_status": fields.get("status"),
        "expected_readback_checks": fixture_checks,
        "stage_error": fields.get("stage_error"),
        "finance_event_id": fields.get("finance_event_id"),
        "conversation_turn_id": fields.get("conversation_turn_id"),
        "source_turn_id": fields.get("source_turn_id"),
        "case_id": fields.get("case_id"),
        "person_id": fields.get("person_id"),
        "task_id": fields.get("task_id"),
        "obligation_event_id": fields.get("obligation_event_id"),
        "advance_event_id": fields.get("advance_event_id"),
        "advance_turn_id": fields.get("advance_turn_id"),
        "minor_units": int(fields["minor_units"]) if fields.get("minor_units", "").isdigit() else None,
        "obligation_minor_units": int(fields["obligation_minor_units"])
        if fields.get("obligation_minor_units", "").isdigit() else None,
        "advance_minor_units": int(fields["advance_minor_units"])
        if fields.get("advance_minor_units", "").isdigit() else None,
        "outstanding_minor_units": int(fields["outstanding_minor_units"])
        if fields.get("outstanding_minor_units", "").isdigit() else None,
        "advance_applied_minor_units": int(fields["advance_applied_minor_units"])
        if fields.get("advance_applied_minor_units", "").isdigit() else None,
        "finance_event_count": int(fields["finance_event_count"]) if fields.get("finance_event_count", "").isdigit() else None,
        "conversation_message_count": int(fields["conversation_message_count"]) if fields.get("conversation_message_count", "").isdigit() else None,
        "action_result_count": int(fields["action_result_count"]) if fields.get("action_result_count", "").isdigit() else None,
        "task_event_count": int(fields["task_event_count"]) if fields.get("task_event_count", "").isdigit() else None,
        "case_evidence_count": int(fields["case_evidence_count"]) if fields.get("case_evidence_count", "").isdigit() else None,
        "bmw_conversation_message_count": int(fields["bmw_conversation_message_count"])
        if fields.get("bmw_conversation_message_count", "").isdigit() else None,
        "bmw_action_result_count": int(fields["bmw_action_result_count"])
        if fields.get("bmw_action_result_count", "").isdigit() else None,
        "network_used": False,
        "model_used": "NOT APPLICABLE",
        "data_scope": "named synthetic Android user 10 only; app repository readback; idempotent additive fixture; no deletion",
    })
    if passed:
        try:
            screenshots = capture_finance_ui_journey(serial, out_dir)
            steps.append({
                "name": "persistent_conversation_case_person_finance_task_source_back_ui_journey",
                "status": "PASS",
                "route": "Secretary conversation → BMW advance (300 AZN total, 100 AZN paid, 200 AZN remaining) → exact case → person → advance → linked Task → originating conversation; Android Back restores each prior screen",
                "screenshot_count": len(screenshots),
                "screenshots": screenshots,
                "network_used": False,
                "model_used": "NOT APPLICABLE",
                "data_scope": "synthetic Android user 10 only; no UI write actions or data cleanup",
            })
        except (RuntimeError, subprocess.CalledProcessError, subprocess.TimeoutExpired) as error:
            steps.append({
                "name": "persistent_conversation_case_person_finance_task_source_back_ui_journey",
                "status": "FAIL",
                "reason": scrub(f"{type(error).__name__}: {error}"),
                "network_used": False,
                "model_used": "NOT APPLICABLE",
                "data_scope": "synthetic Android user 10 only; no UI write actions or data cleanup",
            })


def validate_bmw_ui_fixture_readback(fields: dict[str, str]) -> tuple[bool, dict[str, bool]]:
    """Fail closed unless the synthetic 300/100/200 BMW path and exact links read back."""
    expected = {
        "obligation_minor_units": 30_000,
        "advance_minor_units": 10_000,
        "outstanding_minor_units": 20_000,
        "advance_applied_minor_units": 10_000,
        "task_event_count": 1,
        "case_evidence_count": 4,
        "bmw_conversation_message_count": 2,
        "bmw_action_result_count": 2,
    }
    checks = {key: fields.get(key) == str(value) for key, value in expected.items()}
    checks["fixture_status"] = fields.get("status") == "synthetic_finance_ui_journey_fixture_passed"
    return all(checks.values()), checks


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("preflight", "quick", "full", "device-smoke", "device-lineage", "volume-probe", "startup-probe", "finance-ui-journey"))
    parser.add_argument("--serial", help="explicit connected emulator serial; physical serials are rejected")
    parser.add_argument("--synthetic-user-id", type=int, help="existing isolated emulator user id; mode checks its exact name")
    parser.add_argument("--volume-count", action="append", type=int, choices=(100, 1000),
                        help="volume fixture size; repeat to run several sizes (default: 100 and 1000)")
    parser.add_argument("--startup-samples", type=int,
                        help="process-cold MainActivity launches for startup-probe (default: 20; range: 5..50)")
    parser.add_argument("--seed-synthetic-profile", action="store_true",
                        help="one-time opt-in seed; probe refuses any non-empty profile or existing install marker")
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
        if args.mode in {"device-lineage", "volume-probe", "startup-probe", "finance-ui-journey"} and args.synthetic_user_id is None:
            raise RuntimeError(f"{args.mode} requires --synthetic-user-id for the named isolated test user")
        if args.seed_synthetic_profile and args.mode != "device-lineage":
            raise RuntimeError("--seed-synthetic-profile is accepted only by device-lineage")
        if args.startup_samples is not None and args.mode != "startup-probe":
            raise RuntimeError("--startup-samples is accepted only by startup-probe")
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
            _version_code, _version_name, version_args = acceptance_debug_version(identity["serial"])
            result = run([str(GRADLEW), *FULL_TASKS, *version_args, "--init-script", str(init_script), "--no-daemon", "--console=plain", "--max-workers=1"], timeout=3600,
                         env=test_environment(identity["serial"]))
            result["junit"] = parse_junit(started)
            junit = result["junit"]
            passed = result["exit_code"] == 0 and junit["tests"] > 0 and junit["failures"] == 0 and junit["errors"] == 0
            report["steps"].append({"name": "full_gradle_acceptance", "status": "PASS" if passed else "FAIL", **result})
        elif args.mode == "device-smoke":
            report["steps"].extend(run_device_smoke(identity["serial"], test_environment(identity["serial"]), out_dir))
        elif args.mode == "device-lineage":
            run_device_lineage(identity["serial"], args.synthetic_user_id,
                               args.seed_synthetic_profile, test_environment(identity["serial"]),
                               out_dir, report["steps"])
        elif args.mode == "volume-probe":
            run_synthetic_volume_probe(identity["serial"], args.synthetic_user_id,
                                       test_environment(identity["serial"]), args.volume_count or [100, 1_000],
                                       report["steps"])
        elif args.mode == "startup-probe":
            report["steps"].append(run_startup_probe(identity["serial"], args.synthetic_user_id,
                                                     args.startup_samples if args.startup_samples is not None else 20,
                                                     out_dir))
        elif args.mode == "finance-ui-journey":
            run_synthetic_finance_ui_journey_probe(identity["serial"], args.synthetic_user_id,
                                                   out_dir, report["steps"])
        statuses = [step.get("status") for step in report["steps"]]
        if any(step.get("status") == "FAIL" or step.get("exit_code", 0) != 0 for step in report["steps"]):
            report["status"] = "FAIL"
        elif any(status in {"BLOCKED", "NOT TESTED"} for status in statuses):
            report["status"] = "PARTIAL"
        else:
            report["status"] = "PASS"
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
