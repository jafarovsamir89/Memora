from __future__ import annotations

import importlib.util
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch


SCRIPT = Path(__file__).resolve().parents[1] / "ui_visual_regression.py"
SPEC = importlib.util.spec_from_file_location("memora_ui_visual_regression", SCRIPT)
assert SPEC and SPEC.loader
visual = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(visual)


class GalleryReadinessTests(unittest.TestCase):
    def test_visible_compose_heading_marks_gallery_ready(self) -> None:
        hierarchy = """<hierarchy><node text="Memora · komponentlər" bounds="[24,100][800,180]" /></hierarchy>"""
        self.assertTrue(visual.gallery_content_is_ready(hierarchy))

    def test_missing_heading_does_not_allow_early_screenshot(self) -> None:
        hierarchy = """<hierarchy><node text="Davam et" bounds="[24,200][800,300]" /></hierarchy>"""
        self.assertFalse(visual.gallery_content_is_ready(hierarchy))

    def test_confirmation_modal_is_ready_from_its_visible_title(self) -> None:
        hierarchy = """<hierarchy><node text="Dəyişikliyi təsdiqlə" bounds="[180,900][900,980]" /></hierarchy>"""
        self.assertTrue(visual.gallery_content_is_ready(hierarchy, "Dəyişikliyi təsdiqlə"))

    def test_zero_bounds_heading_is_not_visible(self) -> None:
        hierarchy = """<hierarchy><node text="Memora · komponentlər" bounds="[0,0][0,0]" /></hierarchy>"""
        self.assertFalse(visual.gallery_content_is_ready(hierarchy))

    def test_heading_without_screen_bounds_is_not_ready(self) -> None:
        hierarchy = """<hierarchy><node text="Memora · komponentlər" /></hierarchy>"""
        self.assertFalse(visual.gallery_content_is_ready(hierarchy))

    def test_heading_without_bounds_is_rejected(self) -> None:
        hierarchy = """<hierarchy><node text="Memora · komponentlər" /></hierarchy>"""
        self.assertFalse(visual.gallery_content_is_ready(hierarchy))

    def test_malformed_hierarchy_is_not_ready(self) -> None:
        self.assertFalse(visual.gallery_content_is_ready("<hierarchy>"))

    def test_visible_android_system_anr_is_not_mistaken_for_gallery_readiness(self) -> None:
        hierarchy = '''<hierarchy><node text="System UI isn't responding" bounds="[10,10][900,400]" /></hierarchy>'''
        self.assertTrue(visual.android_system_anr_is_visible(hierarchy))
        self.assertFalse(visual.gallery_content_is_ready(hierarchy))

    def test_transient_missing_hierarchy_retries_until_gallery_is_ready(self) -> None:
        hierarchy = '<hierarchy><node text="Memora · komponentlər" bounds="[24,100][800,180]" /></hierarchy>'.encode("utf-8")
        cat_attempts = 0

        def run_adb(_serial: str, *args: str, **_kwargs: object) -> bytes:
            nonlocal cat_attempts
            if args[:3] == ("shell", "cat", visual.UI_HIERARCHY_PATH):
                cat_attempts += 1
                if cat_attempts == 1:
                    raise subprocess.CalledProcessError(1, args)
                return hierarchy
            return b""

        with patch.object(visual, "run_adb", side_effect=run_adb) as adb, \
             patch.object(visual.time, "sleep"):
            visual.wait_for_gallery_ready("emulator-5556", timeout_seconds=2)

        self.assertEqual(cat_attempts, 2)
        self.assertEqual(sum(call.args[1:4] == ("shell", "uiautomator", "dump") for call in adb.call_args_list), 2)

    def test_android_system_anr_fails_visual_capture_without_retrying(self) -> None:
        hierarchy = b'''<hierarchy><node text="Process system isn't responding" bounds="[10,10][900,400]" /></hierarchy>'''
        with patch.object(visual, "run_adb", side_effect=lambda _serial, *args, **_kwargs: hierarchy if args[:2] == ("shell", "cat") else b""), \
             patch.object(visual.time, "sleep"):
            with self.assertRaisesRegex(RuntimeError, "system ANR blocks"):
                visual.wait_for_gallery_ready("emulator-5556", timeout_seconds=2)

    def test_stuck_uiautomator_is_reported_as_bounded_blocker(self) -> None:
        timeout = subprocess.TimeoutExpired(["adb", "uiautomator"], 2)
        with patch.object(visual, "run_adb", side_effect=timeout) as run_adb, \
             patch.object(visual.time, "sleep"):
            with self.assertRaisesRegex(RuntimeError, "hierarchy capture timed out"):
                visual.wait_for_gallery_ready("emulator-5556", timeout_seconds=2)

        self.assertEqual(run_adb.call_count, 2)  # bounded dump attempt and best-effort cleanup


class EmulatorSettingRestoreTests(unittest.TestCase):
    def test_unset_android_setting_is_normalized_from_null(self) -> None:
        with patch.object(visual, "run_adb", return_value=b"null\n") as run_adb:
            value = visual.get_optional_setting("emulator-5556", "system", "font_scale")

        self.assertIsNone(value)
        run_adb.assert_called_once_with(
            "emulator-5556", "shell", "settings", "get", "system", "font_scale",
            timeout_seconds=visual.ADB_CAPTURE_TIMEOUT_SECONDS,
        )

    def test_absent_setting_is_deleted_to_restore_original_state(self) -> None:
        with patch.object(visual, "run_adb") as run_adb:
            visual.restore_setting("emulator-5556", "system", "font_scale", None)

        run_adb.assert_called_once_with(
            "emulator-5556", "shell", "settings", "delete", "system", "font_scale",
            timeout_seconds=visual.ADB_CLEANUP_TIMEOUT_SECONDS,
        )

    def test_existing_setting_is_put_back_exactly(self) -> None:
        with patch.object(visual, "run_adb") as run_adb:
            visual.restore_setting("emulator-5556", "system", "user_rotation", "3")

        run_adb.assert_called_once_with(
            "emulator-5556", "shell", "settings", "put", "system", "user_rotation", "3",
            timeout_seconds=visual.ADB_CLEANUP_TIMEOUT_SECONDS,
        )

    def test_restore_failure_keeps_the_capture_failure_in_diagnostic(self) -> None:
        primary = subprocess.TimeoutExpired(["adb", "start"], 10)
        message = visual.restore_failure_message(["font_scale", "app_process"], primary)

        self.assertIn("font_scale, app_process", message)
        self.assertIn("preceding operation failed", message)
        self.assertIn("TimeoutExpired", message)


class CaptureTimeoutTests(unittest.TestCase):
    def test_capture_bounds_launch_stop_and_screenshot_commands(self) -> None:
        with patch.object(visual, "run_adb", return_value=b"screen") as run_adb, \
             patch.object(visual, "wait_for_gallery_ready"), \
             patch.object(visual.time, "sleep"):
            screenshot = visual.capture("emulator-5556", "primary", "light")

        self.assertEqual(screenshot, b"screen")
        self.assertEqual(run_adb.call_count, 3)
        self.assertTrue(
            all(call.kwargs["timeout_seconds"] == visual.ADB_CAPTURE_TIMEOUT_SECONDS
                for call in run_adb.call_args_list)
        )

    def test_main_reports_capture_and_restore_failures_together(self) -> None:
        restored: list[str] = []

        def restore(_serial: str, _namespace: str, key: str, _value: str | None) -> None:
            restored.append(key)
            if key == "font_scale":
                raise subprocess.TimeoutExpired(["adb", "settings", "put"], 5)

        with tempfile.TemporaryDirectory() as directory, \
             patch.object(visual.sys, "argv", ["ui_visual_regression.py", "--component", "primary", "--golden-dir", directory]), \
             patch.object(visual, "resolve_emulator", return_value="emulator-5556"), \
             patch.object(visual, "get_optional_setting", side_effect=["1.0", "0", "1"]), \
             patch.object(visual, "set_setting"), \
             patch.object(visual, "capture", side_effect=RuntimeError("gallery did not render")), \
             patch.object(visual, "restore_setting", side_effect=restore), \
             patch.object(visual, "run_adb", side_effect=subprocess.CalledProcessError(1, ["adb", "force-stop"])):
            with self.assertRaises(RuntimeError) as error:
                visual.main()

        self.assertEqual(restored, ["font_scale", "accelerometer_rotation", "user_rotation"])
        self.assertIn("font_scale, app_process", str(error.exception))
        self.assertIn("gallery did not render", str(error.exception))


if __name__ == "__main__":
    unittest.main()
