from __future__ import annotations

import importlib.util
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch


SCRIPT = Path(__file__).resolve().parents[1] / "run_memora_acceptance.py"
SPEC = importlib.util.spec_from_file_location("memora_acceptance", SCRIPT)
assert SPEC and SPEC.loader
lab = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(lab)


class EmulatorSelectionTests(unittest.TestCase):
    def test_only_verified_emulator_is_selected_when_physical_device_is_also_listed(self) -> None:
        props = {"serial": "emulator-5556", "qemu": "1"}
        with patch.object(lab, "connected_devices", return_value=[
            ("R58M123456", "model:physical-phone"),
            ("emulator-5556", "model:sdk_gphone"),
        ]), patch.object(lab, "verify_emulator", return_value=props) as verify:
            selected, inventory = lab.select_emulator(None)

        self.assertEqual(selected, props)
        self.assertEqual(inventory, [{"serial": "emulator-5556"}])
        self.assertNotIn("R58M123456", repr(inventory))
        verify.assert_called_once_with("emulator-5556")

    def test_physical_serial_is_rejected_even_if_explicit(self) -> None:
        with patch.object(lab, "connected_devices", return_value=[("R58M123456", "model:phone")]):
            with self.assertRaisesRegex(RuntimeError, "physical devices are rejected"):
                lab.select_emulator("R58M123456")

    def test_multiple_emulators_require_an_explicit_serial(self) -> None:
        with patch.object(lab, "connected_devices", return_value=[
            ("emulator-5556", "model:sdk_gphone"),
            ("emulator-5558", "model:sdk_gphone"),
        ]):
            with self.assertRaisesRegex(RuntimeError, "exactly one Android Emulator"):
                lab.select_emulator(None)

    def test_android_properties_must_prove_emulator(self) -> None:
        values = {
            "ro.kernel.qemu": "0",
            "ro.boot.qemu": "0",
            "ro.hardware": "redfin",
            "ro.build.characteristics": "default",
            "ro.build.fingerprint": "google/redfin/redfin:user/release-keys",
            "ro.product.model": "Pixel",
            "ro.product.manufacturer": "Google",
            "ro.build.version.sdk": "34",
        }
        with patch.object(lab, "property_value", side_effect=lambda _serial, key: values[key]):
            with self.assertRaisesRegex(RuntimeError, "emulator system-property verification"):
                lab.verify_emulator("emulator-5556")

    def test_every_command_after_inventory_is_serial_scoped(self) -> None:
        with patch.object(lab.subprocess, "run") as run:
            lab.adb("emulator-5556", "shell", "getprop", "ro.kernel.qemu")
        self.assertEqual(run.call_args.args[0][:3], ["adb", "-s", "emulator-5556"])


class ReportSafetyTests(unittest.TestCase):
    def test_run_id_contains_subsecond_precision(self) -> None:
        self.assertIn(".", lab.utc_stamp())

    def test_gradle_init_script_forces_only_unit_tests(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = lab.test_force_init_script(Path(directory))
            script = path.read_text(encoding="utf-8")
        self.assertIn("testDebugUnitTest", script)
        self.assertIn("outputs.upToDateWhen { false }", script)
        self.assertNotIn("--rerun-tasks", script)

    def test_common_api_credentials_are_redacted(self) -> None:
        text = "gemini.apiKey=AIzaSyABCDEFGHIJKLMNOPQRSTUVWXYZ123456789"
        self.assertNotIn("AIzaSy", lab.scrub(text))
        self.assertIn("[REDACTED]", lab.scrub(text))
        self.assertNotIn("abcdef0123456789abcdef0123456789", lab.scrub("abcdef0123456789abcdef0123456789"))

    def test_junit_totals_are_aggregated(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            app_root = Path(directory) / "app"
            target = app_root / "build/test-results/testDebugUnitTest"
            target.mkdir(parents=True)
            (target / "TEST-one.xml").write_text(
                '<testsuite tests="3" failures="1" errors="0" skipped="1"/>', encoding="utf-8"
            )
            with patch.object(lab, "APP", app_root):
                totals = lab.parse_junit(0)
            self.assertEqual(totals, {"tests": 3, "failures": 1, "errors": 0, "skipped": 1, "suites": 1})


if __name__ == "__main__":
    unittest.main()
