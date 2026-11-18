from __future__ import annotations

import importlib.util
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
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


class StartupProbeMetricTests(unittest.TestCase):
    def test_parses_activity_manager_total_time_only_after_success(self) -> None:
        output = "Status: ok\nLaunchState: COLD\nActivity: ai.memora.app/.MainActivity\nTotalTime: 1844\nWaitTime: 1901\n"
        self.assertEqual(lab.parse_activity_startup_ms(output), 1844)

    def test_rejects_missing_or_failed_activity_manager_start(self) -> None:
        for output in ("Status: timeout\nTotalTime: 2000\n", "Status: ok\nWaitTime: 500\n"):
            with self.subTest(output=output), self.assertRaisesRegex(RuntimeError, "Status: ok and TotalTime"):
                lab.parse_activity_startup_ms(output)

    def test_percentiles_use_nearest_rank_and_are_deterministic(self) -> None:
        values = list(range(1, 21))
        self.assertEqual(lab.nearest_rank_percentile(values, 50), 10)
        self.assertEqual(lab.nearest_rank_percentile(values, 95), 19)

    def test_percentile_rejects_empty_samples_and_out_of_range_rank(self) -> None:
        for values, rank in (([], 95), ([4], 0), ([4], 101)):
            with self.subTest(values=values, rank=rank), self.assertRaises(ValueError):
                lab.nearest_rank_percentile(values, rank)


class UiHierarchyTests(unittest.TestCase):
    def test_ui_bounds_parse_android_accessibility_bounds(self) -> None:
        self.assertEqual(lab.ui_bounds("[276,2188][365,2230]"), (276, 2188, 365, 2230))

    def test_tap_prefers_bottom_navigation_label_when_title_is_duplicate(self) -> None:
        root = lab.ET.fromstring("""<hierarchy rotation="0"><node bounds="[0,0][1080,2400]">
          <node text="Katibə" bounds="[210,178][368,246]" />
          <node text="Katibə" bounds="[276,2188][365,2230]" />
        </node>
        </hierarchy>""")
        with patch.object(lab, "ui_tree", return_value=root), patch.object(lab, "adb") as adb:
            lab.tap_ui_text("emulator-5556", Path("/tmp"), "state", "Katibə", 0.8)
        adb.assert_called_once_with("emulator-5556", "shell", "input", "tap", "320", "2209")

    def test_tap_refuses_ambiguous_visible_targets(self) -> None:
        root = lab.ET.fromstring("""<hierarchy bounds="[0,0][1080,2400]">
          <node text="Open" bounds="[10,100][100,160]" />
          <node text="Open" bounds="[10,300][100,360]" />
        </hierarchy>""")
        with patch.object(lab, "ui_tree", return_value=root), patch.object(lab, "adb") as adb:
            with self.assertRaisesRegex(RuntimeError, "found 2"):
                lab.tap_ui_text("emulator-5556", Path("/tmp"), "state", "Open")
        adb.assert_not_called()

    def test_startup_probe_rejects_owner_user_before_any_adb_call(self) -> None:
        with patch.object(lab, "adb") as adb:
            with self.assertRaisesRegex(RuntimeError, "non-owner synthetic"):
                lab.run_startup_probe("emulator-5556", 0, 20, Path("/tmp"))
        adb.assert_not_called()

    def test_startup_probe_rejects_unbounded_sample_count_before_any_adb_call(self) -> None:
        with patch.object(lab, "adb") as adb:
            with self.assertRaisesRegex(RuntimeError, "between 5 and 50"):
                lab.run_startup_probe("emulator-5556", 11, 51, Path("/tmp"))
        adb.assert_not_called()


class ReportSafetyTests(unittest.TestCase):
    def test_volume_report_preserves_conversation_count_paging_and_measurements(self) -> None:
        report = lab.volume_probe_measurements({
            "synthetic_conversation_turns": "1000",
            "conversation_rows_inserted_this_run": "0",
            "conversation_archive_pages": "34",
            "conversation_pagination_exact": "true",
            "conversation_oldest_search_p95_ms": "1262",
            "conversation_oldest_search_expected_turn": "synthetic-volume-conversation-0000",
        })
        self.assertEqual(report, {
            "synthetic_conversation_turns": 1000,
            "conversation_rows_inserted_this_run": 0,
            "conversation_archive_pages": 34,
            "conversation_oldest_search_p95_ms": 1262,
            "conversation_pagination_exact": True,
        })

    def test_run_id_contains_subsecond_precision(self) -> None:
        self.assertIn(".", lab.utc_stamp())

    def test_gradle_init_script_forces_only_unit_tests(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = lab.test_force_init_script(Path(directory))
            script = path.read_text(encoding="utf-8")
        self.assertIn("testDebugUnitTest", script)
        self.assertIn("outputs.upToDateWhen { false }", script)
        self.assertNotIn("--rerun-tasks", script)

    def test_deterministic_profile_fixture_is_in_quick_suite(self) -> None:
        self.assertIn("ai.memora.app.acceptance.SyntheticReliabilityProfileTest", lab.QUICK_TESTS)

    def test_common_api_credentials_are_redacted(self) -> None:
        text = "gemini.apiKey=AIzaSyABCDEFGHIJKLMNOPQRSTUVWXYZ123456789"
        self.assertNotIn("AIzaSy", lab.scrub(text))
        self.assertIn("[REDACTED]", lab.scrub(text))
        self.assertNotIn("abcdef0123456789abcdef0123456789", lab.scrub("abcdef0123456789abcdef0123456789"))

    def test_package_lineage_parser_uses_only_the_selected_android_user(self) -> None:
        package_info = """versionCode=108 minSdk=26 targetSdk=36
versionName=acceptance-lab-v108
  User 0: installed=true stopped=false
    firstInstallTime=2026-11-15 16:15:34
  User 10: installed=true stopped=false
    firstInstallTime=2026-11-18 04:54:50
"""
        self.assertEqual(lab.package_install_state(package_info, 10), {
            "version_code": 108,
            "version_name": "acceptance-lab-v108",
            "first_install_time": "2026-11-18 04:54:50",
            "installed_for_user": True,
        })

    def test_acceptance_debug_version_moves_above_globally_installed_package(self) -> None:
        package_info = "versionCode=109 minSdk=26 targetSdk=36\nversionName=acceptance-lab-v109\n"
        with patch.object(lab, "adb", return_value=SimpleNamespace(stdout=package_info)):
            self.assertEqual(lab.acceptance_debug_version("emulator-5556"), (
                110,
                "acceptance-lab-v110",
                ["-PmemoraAcceptanceVersionCode=110", "-PmemoraAcceptanceVersionName=acceptance-lab-v110"],
            ))

    def test_acceptance_debug_version_leaves_default_build_when_package_is_absent(self) -> None:
        with patch.object(lab, "adb", return_value=SimpleNamespace(stdout="Package [ai.memora.app] not found")):
            self.assertEqual(lab.acceptance_debug_version("emulator-5556"), (
                None, None, []
            ))

    def test_acceptance_debug_version_starts_above_checked_in_version_floor(self) -> None:
        package_info = "versionCode=105 minSdk=26 targetSdk=36\nversionName=1.1.83-old\n"
        with patch.object(lab, "adb", return_value=SimpleNamespace(stdout=package_info)):
            code, name, args = lab.acceptance_debug_version("emulator-5556")
        self.assertEqual((code, name), (106, "acceptance-lab-v106"))
        self.assertEqual(len(args), 2)

    def test_lineage_mode_refuses_owner_user_before_adb(self) -> None:
        with patch.object(lab, "adb") as adb_call:
            with self.assertRaisesRegex(RuntimeError, "greater than owner user 0"):
                lab.run_device_lineage("emulator-5556", 0, False, {}, Path("/tmp"), [])
        adb_call.assert_not_called()

    def test_volume_mode_refuses_owner_user_before_adb(self) -> None:
        with patch.object(lab, "adb") as adb_call:
            with self.assertRaisesRegex(RuntimeError, "greater than owner user 0"):
                lab.run_synthetic_volume_probe("emulator-5556", 0, {}, [], [])
        adb_call.assert_not_called()

    def test_persistent_finance_ui_journey_refuses_owner_before_adb(self) -> None:
        with patch.object(lab, "adb") as adb_call:
            with self.assertRaisesRegex(RuntimeError, "greater than owner user 0"):
                lab.run_synthetic_finance_ui_journey_probe("emulator-5556", 0, Path("/tmp"), [])
        adb_call.assert_not_called()

    def test_persistent_finance_ui_journey_requires_user10_to_be_current(self) -> None:
        user_list = "Users:\n\tUserInfo{10:MemoraReliabilityLab:8410} running\n"
        with patch.object(lab, "adb", side_effect=[
            SimpleNamespace(stdout=user_list), SimpleNamespace(stdout="11\n"),
        ]) as adb_call:
            with self.assertRaisesRegex(RuntimeError, "switch the verified emulator to synthetic user 10"):
                lab.run_synthetic_finance_ui_journey_probe("emulator-5556", 10, Path("/tmp"), [])
        self.assertEqual(adb_call.call_count, 2)

    def test_clean_install_probe_requires_new_marker_and_empty_profile(self) -> None:
        output = """INSTRUMENTATION_RESULT: status=synthetic_lineage_new_install_passed
INSTRUMENTATION_RESULT: onboarding_marker_new=true
INSTRUMENTATION_RESULT: profile_empty=true
"""
        with patch.object(lab, "adb", return_value=SimpleNamespace(stdout=output, stderr="")):
            result = lab.run_lineage_probe("emulator-5556", 11, "verify-new-install")
        self.assertEqual(result["status"], "PASS")
        self.assertTrue(result["fresh_install_marker_new"])
        self.assertTrue(result["fresh_install_profile_empty"])

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
