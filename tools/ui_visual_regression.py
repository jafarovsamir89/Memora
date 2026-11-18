#!/usr/bin/env python3
"""Emulator-only pixel regression for the debug Compose component gallery.

The gallery contains fixed synthetic presentation values and does not open the app DB.
Run --record once to intentionally update expected PNGs; normal Gradle runs compare them.
"""

from __future__ import annotations

import argparse
import os
import struct
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
import zlib
from pathlib import Path

try:
    import numpy as np
except ImportError:  # Keep the regression runner dependency-free on clean machines.
    np = None

ROOT = Path(__file__).resolve().parents[1]
GOLDEN_DIR = ROOT / "docs/ui-ux-redesign-v1/phase-1/component-goldens"
OUTPUT_DIR = ROOT / "apps/android/app/build/ui-visual-regression"
ACTIVITY = "ai.memora.app/.ComponentGalleryActivity"

CASES: list[tuple[str, str]] = [
    ("primary", "light"),
    ("primary_loading", "light"),
    ("primary_error", "light"),
    ("primary_disabled", "light"),
    ("secondary", "light"),
    ("secondary_loading", "light"),
    ("voice", "light"),
    ("voice_active", "light"),
    ("voice_loading", "light"),
    ("status_success", "light"),
    ("status_warning", "light"),
    ("status_error", "light"),
    ("action", "light"),
    ("action_warning", "light"),
    ("person", "light"),
    ("case", "light"),
    ("empty", "light"),
    ("error", "light"),
    ("loading", "light"),
    ("confirmation", "light"),
    ("timeline", "light"),
    ("more_cards", "light"),
    ("navigation", "light"),
    ("primary", "dark"),
    ("voice_active", "dark"),
    ("status_error", "dark"),
    ("action", "dark"),
    ("person", "dark"),
    ("case", "dark"),
    ("error", "dark"),
    ("timeline", "dark"),
]
PIXEL_TOLERANCE = 12
MAX_CHANGED_RATIO = 0.003
MAX_MEAN_CHANNEL_DELTA = 0.35
GALLERY_HEADING = "Memora · komponentlər"
UI_HIERARCHY_PATH = "/data/local/tmp/memora-ui-visual-regression.xml"


def run_adb(serial: str, *args: str, capture: bool = True) -> bytes:
    result = subprocess.run(
        ["adb", "-s", serial, *args],
        check=True,
        stdout=subprocess.PIPE if capture else None,
        stderr=subprocess.PIPE,
    )
    return result.stdout if capture else b""


def get_setting(serial: str, namespace: str, key: str) -> str:
    return run_adb(serial, "shell", "settings", "get", namespace, key).decode().strip()


def set_setting(serial: str, namespace: str, key: str, value: str) -> None:
    run_adb(serial, "shell", "settings", "put", namespace, key, value)


def gallery_content_is_ready(hierarchy: str, expected_text: str = GALLERY_HEADING) -> bool:
    """Return true only after Compose exposes visible gallery content."""
    try:
        root = ET.fromstring(hierarchy)
    except ET.ParseError:
        return False
    return any(
        node.attrib.get("text") == expected_text
        and bool(node.attrib.get("bounds"))
        and node.attrib.get("bounds") != "[0,0][0,0]"
        for node in root.iter("node")
    )


def wait_for_gallery_ready(
    serial: str,
    expected_text: str = GALLERY_HEADING,
    timeout_seconds: float = 8.0,
) -> None:
    """Wait for the active Compose content before pixel capture."""
    deadline = time.monotonic() + timeout_seconds
    while True:
        try:
            run_adb(serial, "shell", "uiautomator", "dump", "--compressed", UI_HIERARCHY_PATH)
            hierarchy = run_adb(serial, "shell", "cat", UI_HIERARCHY_PATH).decode("utf-8", errors="replace")
        finally:
            run_adb(serial, "shell", "rm", "-f", UI_HIERARCHY_PATH)
        if gallery_content_is_ready(hierarchy, expected_text):
            return
        if time.monotonic() >= deadline:
            raise RuntimeError("Compose component gallery did not expose its heading before screenshot capture")
        time.sleep(0.2)


def resolve_emulator() -> str:
    requested = os.environ.get("ANDROID_SERIAL")
    rows = subprocess.check_output(["adb", "devices"], text=True).splitlines()[1:]
    connected = [row.split()[0] for row in rows if len(row.split()) >= 2 and row.split()[1] == "device"]
    candidates = [device for device in connected if device.startswith("emulator-")]
    if requested:
        if requested not in candidates:
            raise RuntimeError("ANDROID_SERIAL must identify a connected emulator (physical devices are rejected)")
        serial = requested
    elif len(candidates) == 1 and len(connected) == 1:
        serial = candidates[0]
    else:
        raise RuntimeError("Connect one disposable emulator and set ANDROID_SERIAL explicitly")
    qemu = run_adb(serial, "shell", "getprop", "ro.kernel.qemu").decode().strip()
    fingerprint = run_adb(serial, "shell", "getprop", "ro.build.fingerprint").decode().strip().lower()
    if qemu != "1" and not any(marker in fingerprint for marker in ("generic", "sdk_gphone", "emulator")):
        raise RuntimeError("Screenshot gallery is restricted to an emulator")
    return serial


def png_pixels(png: bytes) -> tuple[int, int, bytes]:
    if png[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError("adb did not return a PNG screenshot")
    offset, width, height, bit_depth, color_type = 8, 0, 0, 0, 0
    compressed = bytearray()
    palette: bytes | None = None
    transparency: bytes | None = None
    while offset < len(png):
        size = struct.unpack_from(">I", png, offset)[0]
        kind = png[offset + 4 : offset + 8]
        data = png[offset + 8 : offset + 8 + size]
        offset += size + 12
        if kind == b"IHDR":
            width, height, bit_depth, color_type, compression, filtering, interlace = struct.unpack(">IIBBBBB", data)
            if bit_depth != 8 or compression != 0 or filtering != 0 or interlace != 0:
                raise ValueError("Only non-interlaced 8-bit emulator screenshots are supported")
        elif kind == b"IDAT":
            compressed.extend(data)
        elif kind == b"PLTE":
            palette = data
        elif kind == b"tRNS":
            transparency = data
        elif kind == b"IEND":
            break

    channels = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}.get(color_type)
    if not width or not height or channels is None:
        raise ValueError(f"Unsupported PNG color type: {color_type}")
    raw = zlib.decompress(compressed)
    stride = width * channels
    output = bytearray(height * stride)
    prior = bytearray(stride)
    cursor = 0

    def paeth(a: int, b: int, c: int) -> int:
        p = a + b - c
        pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
        return a if pa <= pb and pa <= pc else b if pb <= pc else c

    for row in range(height):
        filt = raw[cursor]
        cursor += 1
        scan = bytearray(raw[cursor : cursor + stride])
        cursor += stride
        for i in range(stride):
            left = scan[i - channels] if i >= channels else 0
            above = prior[i]
            upper_left = prior[i - channels] if i >= channels else 0
            if filt == 1:
                scan[i] = (scan[i] + left) & 255
            elif filt == 2:
                scan[i] = (scan[i] + above) & 255
            elif filt == 3:
                scan[i] = (scan[i] + ((left + above) // 2)) & 255
            elif filt == 4:
                scan[i] = (scan[i] + paeth(left, above, upper_left)) & 255
            elif filt != 0:
                raise ValueError(f"Unsupported PNG filter: {filt}")
        output[row * stride : (row + 1) * stride] = scan
        prior = scan

    if color_type == 6:
        return width, height, bytes(output)
    rgba = bytearray(width * height * 4)
    for index in range(width * height):
        source, target = index * channels, index * 4
        if color_type == 0:
            grey = output[source]
            rgba[target : target + 4] = bytes((grey, grey, grey, 255))
        elif color_type == 2:
            rgba[target : target + 4] = bytes((*output[source : source + 3], 255))
        elif color_type == 3:
            if palette is None:
                raise ValueError("Indexed PNG has no palette")
            color = output[source]
            alpha = transparency[color] if transparency is not None and color < len(transparency) else 255
            rgba[target : target + 4] = bytes((*palette[color * 3 : color * 3 + 3], alpha))
        else:  # grayscale plus alpha
            grey, alpha = output[source : source + 2]
            rgba[target : target + 4] = bytes((grey, grey, grey, alpha))
    return width, height, bytes(rgba)


def compare_pixels(expected: bytes, actual: bytes) -> tuple[float, float]:
    if np is not None:
        expected_pixels = np.frombuffer(expected, dtype=np.uint8).reshape((-1, 4)).astype(np.int16)
        actual_pixels = np.frombuffer(actual, dtype=np.uint8).reshape((-1, 4)).astype(np.int16)
        delta = np.abs(expected_pixels - actual_pixels)
        pixels = expected_pixels.shape[0]
        changed = int(np.count_nonzero(np.any(delta > PIXEL_TOLERANCE, axis=1)))
        total_delta = int(delta[:, :3].sum(dtype=np.int64))
        return changed / pixels, total_delta / (pixels * 3)

    changed = 0
    total_delta = 0
    pixels = len(expected) // 4
    for offset in range(0, len(expected), 4):
        delta = max(abs(expected[offset + c] - actual[offset + c]) for c in range(4))
        if delta > PIXEL_TOLERANCE:
            changed += 1
        total_delta += sum(abs(expected[offset + c] - actual[offset + c]) for c in range(3))
    return changed / pixels, total_delta / (pixels * 3)


def capture(serial: str, component: str, theme: str) -> bytes:
    run_adb(serial, "shell", "am", "force-stop", "ai.memora.app")
    run_adb(serial, "shell", "am", "start", "-W", "-n", ACTIVITY,
            "--es", "component", component, "--es", "theme", theme)
    expected_text = "Dəyişikliyi təsdiqlə" if component == "confirmation" else GALLERY_HEADING
    wait_for_gallery_ready(serial, expected_text)
    # Give Android 14 time to finish the immersive-system-bar transition before
    # capture. Some light surfaces are briefly dimmed while that window update
    # settles. The confirmation sheet also needs its modal enter animation.
    # Other gallery states include intentional looping animation (for example,
    # loading and active voice), so frame equality is not suitable.
    time.sleep(3.0 if component == "confirmation" else 1.25)
    return run_adb(serial, "exec-out", "screencap", "-p")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--record", action="store_true", help="explicitly replace committed component goldens")
    parser.add_argument("--component", help="run only one component key")
    parser.add_argument("--font-scale", default="1.0", help="temporary emulator font scale, default 1.0")
    parser.add_argument("--golden-dir", type=Path, default=GOLDEN_DIR)
    args = parser.parse_args()
    serial = resolve_emulator()
    cases = [case for case in CASES if args.component is None or case[0] == args.component]
    if not cases:
        raise RuntimeError(f"Unknown component key: {args.component}")
    old_font = get_setting(serial, "system", "font_scale") or "1.0"
    old_rotation = get_setting(serial, "system", "user_rotation") or "0"
    old_auto_rotation = get_setting(serial, "system", "accelerometer_rotation") or "1"
    args.golden_dir.mkdir(parents=True, exist_ok=True)
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    failures = 0
    try:
        set_setting(serial, "system", "font_scale", args.font_scale)
        set_setting(serial, "system", "accelerometer_rotation", "0")
        set_setting(serial, "system", "user_rotation", "0")
        for component, theme in cases:
            name = f"{component}-{theme}.png"
            screenshot = capture(serial, component, theme)
            output = OUTPUT_DIR / name
            output.write_bytes(screenshot)
            width, height, actual = png_pixels(screenshot)
            golden = args.golden_dir / name
            if args.record:
                golden.write_bytes(screenshot)
                print(f"RECORDED {name} ({width}x{height})")
                continue
            if not golden.is_file():
                print(f"MISSING {name}: run with --record after reviewing the emulator image", file=sys.stderr)
                failures += 1
                continue
            expected_width, expected_height, expected = png_pixels(golden.read_bytes())
            if (width, height) != (expected_width, expected_height):
                print(f"FAIL {name}: size {width}x{height}, expected {expected_width}x{expected_height}")
                failures += 1
                continue
            changed_ratio, mean_delta = compare_pixels(expected, actual)
            if changed_ratio > MAX_CHANGED_RATIO or mean_delta > MAX_MEAN_CHANNEL_DELTA:
                print(f"FAIL {name}: changed={changed_ratio:.3%}, mean RGB delta={mean_delta:.3f}")
                failures += 1
            else:
                print(f"PASS {name}: changed={changed_ratio:.3%}, mean RGB delta={mean_delta:.3f}")
    finally:
        set_setting(serial, "system", "font_scale", old_font)
        set_setting(serial, "system", "accelerometer_rotation", old_auto_rotation)
        set_setting(serial, "system", "user_rotation", old_rotation)
        run_adb(serial, "shell", "am", "force-stop", "ai.memora.app")
    if failures:
        print(f"Visual regression failed: {failures}/{len(cases)} case(s)", file=sys.stderr)
        return 1
    if not args.record:
        print(f"Visual regression passed: {len(cases)} emulator screenshot(s); fontScale={args.font_scale}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (RuntimeError, subprocess.CalledProcessError, ValueError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        raise SystemExit(2)
