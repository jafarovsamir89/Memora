from __future__ import annotations

import importlib.util
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import call, patch


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
    def test_synthetic_home_restart_stops_instrumentation_before_main_activity(self) -> None:
        out_dir = Path("/tmp/memora-home")
        home = lab.ET.fromstring('<hierarchy><node text="Bu gün"/><node text="Katibə"/></hierarchy>')
        with patch.object(lab, "adb") as adb, \
             patch.object(lab, "wait_for_ui_texts", return_value=home) as wait:
            result = lab.launch_synthetic_today("emulator-5556", out_dir)

        self.assertIs(result, home)
        self.assertEqual(adb.call_args_list, [
            call("emulator-5556", "shell", "am", "force-stop", "--user", "10", lab.TEST_PACKAGE),
            call("emulator-5556", "shell", "am", "force-stop", "--user", "10", lab.PACKAGE),
            call("emulator-5556", "shell", "am", "start", "--user", "10", "-n", "ai.memora.app/.MainActivity"),
        ])
        wait.assert_called_once_with("emulator-5556", out_dir, "today-ready", {"Bu gün", "Katibə"}, stable_samples=3)

    def test_scroll_until_texts_uses_bounded_directional_search(self) -> None:
        old = lab.ET.fromstring('<hierarchy><node text="Older turn"/></hierarchy>')
        target = lab.ET.fromstring('<hierarchy><node text="Exact BMW task"/></hierarchy>')
        with patch.object(lab, "ui_tree", side_effect=[old, target]) as tree, \
             patch.object(lab, "swipe_ui") as swipe:
            result = lab.scroll_until_ui_texts("emulator-5556", Path("/tmp"), "find-bmw",
                                               {"Exact BMW task"}, "up", max_swipes=4)

        self.assertIs(result, target)
        self.assertEqual(tree.call_count, 2)
        swipe.assert_called_once_with("emulator-5556", Path("/tmp"), "find-bmw-swipe-1", 0.92, 0.72, 0.92, 0.34)

    def test_scroll_search_tries_the_other_direction_after_saved_state_boundary(self) -> None:
        target = lab.ET.fromstring('<hierarchy><node text="Exact BMW task"/></hierarchy>')
        with patch.object(lab, "scroll_until_ui_texts", side_effect=[RuntimeError("up boundary"), target]) as search:
            result = lab.scroll_until_ui_texts_either_direction(
                "emulator-5556", Path("/tmp"), "find-bmw", {"Exact BMW task"}, max_swipes=2
            )

        self.assertIs(result, target)
        self.assertEqual(search.call_args_list, [
            call("emulator-5556", Path("/tmp"), "find-bmw", {"Exact BMW task"}, "up", 2),
            call("emulator-5556", Path("/tmp"), "find-bmw", {"Exact BMW task"}, "down", 2),
        ])

    def test_conversation_action_screenshots_visit_both_cards_and_restore_finance(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            out_dir = Path(directory)
            with patch.object(lab, "wait_for_ui_texts") as wait, \
                 patch.object(lab, "scroll_until_ui_texts_either_direction") as scroll, \
                 patch.object(lab, "scroll_until_ui_texts") as directional_scroll, \
                 patch.object(lab, "swipe_ui") as swipe, \
                 patch.object(lab, "screenshot") as capture, \
                 patch.object(lab, "ROOT", out_dir):
                paths = lab.capture_bmw_conversation_action_screenshots("emulator-5556", out_dir)

        self.assertEqual(len(paths), 2)
        self.assertTrue(paths[0].endswith("p0-3-bmw-conversation-task-action.png"))
        self.assertTrue(paths[1].endswith("p0-3-bmw-conversation-finance-action.png"))
        scroll.assert_called_once_with("emulator-5556", out_dir, "secretary-focus-latest-bmw-turn",
                                       {"Bölməni aç Tapşırıqlar", "Synthetic confirm BMW repair appointment"})
        directional_scroll.assert_called_once_with("emulator-5556", out_dir,
            "secretary-focus-bmw-finance-action",
            {"Bölməni aç Maliyyə", "Synthetic BMW repair advance received."},
            direction="up", max_swipes=8)
        self.assertEqual(wait.call_args_list, [
            call("emulator-5556", out_dir, "secretary-bmw-task-action", {
                "Bölməni aç Tapşırıqlar", "Synthetic confirm BMW repair appointment",
            }, stable_samples=2),
            call("emulator-5556", out_dir, "secretary-bmw-finance-action", {
                "Bölməni aç Maliyyə", "Synthetic BMW repair advance received.",
            }, stable_samples=2),
        ])
        swipe.assert_not_called()
        self.assertEqual(capture.call_count, 2)

    def test_exact_conversation_route_requires_both_messages_not_offscreen_action_status(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            out_dir = Path(directory)
            with patch.object(lab, "launch_synthetic_today"), \
                 patch.object(lab, "tap_ui_text"), \
                 patch.object(lab, "capture_bmw_conversation_action_screenshots", return_value=[]), \
                 patch.object(lab, "wait_for_ui_texts") as wait, \
                 patch.object(lab, "scroll_to_top_and_wait_for_ui_texts"), \
                 patch.object(lab, "scroll_until_ui_texts") as source_scroll, \
                 patch.object(lab, "swipe_ui"), \
                 patch.object(lab, "screenshot"), \
                 patch.object(lab, "adb"), \
                 patch.object(lab, "ROOT", out_dir):
                lab.capture_finance_ui_journey("emulator-5556", out_dir)

        exact_conversation = next(call.args[3] for call in wait.call_args_list
                                  if call.args[2] == "task-source-exact-conversation")
        self.assertEqual(exact_conversation, {
            "Sintetik ssenari: Synthetic Elçin BMW təmiri üçün 100 AZN avans verdi; servis görüşünü təsdiqlə.",
            "Sintetik ssenari: 300 AZN razılaşdırılıb, 100 AZN avans alınıb və 200 AZN qalıb; görüş tapşırığı yaradılıb.",
        })
        source_scroll.assert_called_once_with(
            "emulator-5556", out_dir, "task-source-open-conversation", {"Söhbəti katibədə aç"},
            direction="up", max_swipes=8,
        )
        source_detail = next(call.args[3] for call in wait.call_args_list
                             if call.args[2] == "task-exact-source")
        self.assertIn("Yadda saxlanıldı", source_detail)
        restored_secretary = next(call.args[3] for call in wait.call_args_list
                                  if call.args[2] == "case-back-to-origin-secretary")
        self.assertEqual(restored_secretary, {
            "Synthetic BMW repair advance received.",
            "Məbləğ: 100,00 AZN",
            "Bölməni aç Maliyyə",
        })

    def test_ambiguous_ui_capture_waits_for_clarification_and_no_write_status(self) -> None:
        root = lab.ET.fromstring("""<hierarchy>
          <node text="Sintetik yoxlama: Ruslan mənə 150 manat qaytardı; hansı Ruslan olduğunu dəqiqləşdirin." />
          <node text="Hansı Ruslanı nəzərdə tutursunuz? Qeydi dəqiqləşdirmədən heç nə saxlamadım." />
          <node text="Təsdiq gözlənilir" />
          <node text="Təsdiq verilməyənədək heç bir dəyişiklik edilməyib." />
        </hierarchy>""")
        with tempfile.TemporaryDirectory() as directory:
            out_dir = Path(directory)
            with patch.object(lab, "launch_synthetic_today"), \
                 patch.object(lab, "tap_ui_text") as tap, \
                 patch.object(lab, "wait_for_ui_texts") as wait, \
                 patch.object(lab, "scroll_until_ui_texts_either_direction") as scroll, \
                 patch.object(lab, "screenshot") as capture, \
                 patch.object(lab, "ROOT", out_dir):
                path = lab.capture_ambiguous_ui("emulator-5556", out_dir)

        self.assertTrue(path.endswith("p0-3-ambiguous-no-write.png"))
        tap.assert_called_once_with("emulator-5556", out_dir, "today-before-ambiguous-secretary", "Katibə",
                                    minimum_center_y_fraction=0.8)
        self.assertEqual(wait.call_args_list[0], call("emulator-5556", out_dir, "secretary-ambiguous-messages", {
            "Sintetik yoxlama: Ruslan mənə 150 manat qaytardı; hansı Ruslan olduğunu dəqiqləşdirin.",
            "Hansı Ruslanı nəzərdə tutursunuz? Qeydi dəqiqləşdirmədən heç nə saxlamadım.",
        }, stable_samples=2))
        self.assertEqual(wait.call_args_list[1], call("emulator-5556", out_dir, "secretary-ambiguous-no-write", {
            "Sintetik yoxlama: Ruslan mənə 150 manat qaytardı; hansı Ruslan olduğunu dəqiqləşdirin.",
            "Hansı Ruslanı nəzərdə tutursunuz? Qeydi dəqiqləşdirmədən heç nə saxlamadım.",
            "Təsdiq gözlənilir",
            "Təsdiq verilməyənədək heç bir dəyişiklik edilməyib.",
        }, stable_samples=2))
        scroll.assert_called_once_with("emulator-5556", out_dir, "secretary-ambiguous-no-write", {
            "Təsdiq gözlənilir",
            "Təsdiq verilməyənədək heç bir dəyişiklik edilməyib.",
        }, max_swipes=4)
        capture.assert_called_once()

    def test_wait_for_ui_texts_ignores_stale_navigation_during_activity_start(self) -> None:
        roots = [
            lab.ET.fromstring('<hierarchy><node text="Katibə"/><node text="Old conversation"/></hierarchy>'),
            lab.ET.fromstring('<hierarchy><node text="Memora"/><node text="Loading"/></hierarchy>'),
            lab.ET.fromstring('<hierarchy><node text="Katibə"/><node text="Today"/></hierarchy>'),
            lab.ET.fromstring('<hierarchy><node text="Katibə"/><node text="Today"/></hierarchy>'),
        ]
        with patch.object(lab, "ui_tree", side_effect=roots) as tree, \
             patch.object(lab.time, "sleep"):
            result = lab.wait_for_ui_texts(
                "emulator-5556", Path("/tmp"), "home", {"Katibə"},
                timeout_seconds=2.0, stable_samples=2,
            )

        self.assertEqual(lab.ui_texts(result), {"Katibə", "Today"})
        self.assertEqual(tree.call_count, 4)

    def test_finance_detail_header_is_checked_after_restoring_saved_scroll_position(self) -> None:
        events: list[tuple[str, tuple[object, ...]]] = []
        expected = {"100,00 AZN", "Daxil olan pul"}
        with patch.object(lab, "scroll_until_ui_texts", side_effect=lambda *args, **kwargs: events.append(("scroll", args + (kwargs,)))), \
             patch.object(lab, "wait_for_ui_texts", side_effect=lambda *args: events.append(("wait", args))):
            lab.scroll_to_top_and_wait_for_ui_texts(
                "emulator-5556", Path("/tmp"), "finance-detail", expected
            )

        self.assertEqual([event[0] for event in events], ["scroll", "wait"])
        self.assertEqual(events[0][1], (
            "emulator-5556", Path("/tmp"), "finance-detail-scroll-to-top", expected,
            {"direction": "down", "max_swipes": 8},
        ))
        self.assertEqual(events[1][1][2:], ("finance-detail", expected))

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

    def test_tap_can_select_earlier_of_repeated_labels_by_screen_region(self) -> None:
        root = lab.ET.fromstring("""<hierarchy><node bounds="[0,0][1080,2400]">
          <node text="Synthetic person" bounds="[100,700][600,780]" />
          <node text="Synthetic person" bounds="[100,1700][600,1780]" />
        </node></hierarchy>""")
        with patch.object(lab, "ui_tree", return_value=root), patch.object(lab, "adb") as adb:
            lab.tap_ui_text("emulator-5556", Path("/tmp"), "case", "Synthetic person",
                            maximum_center_y_fraction=0.6)
        adb.assert_called_once_with("emulator-5556", "shell", "input", "tap", "350", "740")

    def test_tap_can_select_later_of_repeated_labels_by_screen_region(self) -> None:
        root = lab.ET.fromstring("""<hierarchy><node bounds="[0,0][1080,2400]">
          <node text="Source" bounds="[100,1140][400,1200]" />
          <node text="Source" bounds="[100,1280][400,1340]" />
        </node></hierarchy>""")
        with patch.object(lab, "ui_tree", return_value=root), patch.object(lab, "adb") as adb:
            lab.tap_ui_text("emulator-5556", Path("/tmp"), "task", "Source",
                            minimum_center_y_fraction=0.52)
        adb.assert_called_once_with("emulator-5556", "shell", "input", "tap", "250", "1310")

    def test_swipe_uses_screen_relative_coordinates(self) -> None:
        root = lab.ET.fromstring("""<hierarchy><node bounds="[0,0][1080,2400]">
          <node text="conversation" bounds="[0,200][1080,2100]" />
        </node></hierarchy>""")
        with patch.object(lab, "ui_tree", return_value=root), patch.object(lab, "adb") as adb:
            lab.swipe_ui("emulator-5556", Path("/tmp"), "conversation", .5, .3, .5, .76)
        adb.assert_called_once_with("emulator-5556", "shell", "input", "swipe",
                                    "540", "720", "540", "1824", "500")

    def test_swipe_rejects_out_of_bounds_fractions_without_adb(self) -> None:
        root = lab.ET.fromstring("""<hierarchy bounds="[0,0][1080,2400]" />""")
        with patch.object(lab, "ui_tree", return_value=root), patch.object(lab, "adb") as adb:
            with self.assertRaisesRegex(RuntimeError, "fractions are out of bounds"):
                lab.swipe_ui("emulator-5556", Path("/tmp"), "conversation", .5, -.1, .5, .76)
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
    def test_bmw_ui_fixture_requires_exact_300_100_200_and_link_readback(self) -> None:
        fields = {
            "status": "synthetic_finance_ui_journey_fixture_passed",
            "obligation_minor_units": "30000",
            "advance_minor_units": "10000",
            "outstanding_minor_units": "20000",
            "advance_applied_minor_units": "10000",
            "task_event_count": "1",
            "case_evidence_count": "4",
            "bmw_conversation_message_count": "2",
            "bmw_action_result_count": "2",
            "ambiguous_message_count": "2",
            "ambiguous_action_result_count": "1",
            "ambiguous_finance_events_before": "3",
            "ambiguous_finance_events_after": "3",
            "ambiguous_status": "NEEDS_CONFIRMATION",
            "ambiguous_code": "person_name_ambiguous",
            "ambiguous_target_present": "false",
        }
        passed, checks = lab.validate_bmw_ui_fixture_readback(fields)
        self.assertTrue(passed)
        self.assertTrue(all(checks.values()))

    def test_bmw_ui_fixture_rejects_incorrect_finance_or_missing_link_readback(self) -> None:
        fields = {
            "status": "synthetic_finance_ui_journey_fixture_passed",
            "obligation_minor_units": "30000",
            "advance_minor_units": "10000",
            "outstanding_minor_units": "19000",
            "advance_applied_minor_units": "10000",
            "task_event_count": "1",
            "case_evidence_count": "3",
            "bmw_conversation_message_count": "2",
            "bmw_action_result_count": "1",
            "ambiguous_message_count": "2",
            "ambiguous_action_result_count": "1",
            "ambiguous_finance_events_before": "3",
            "ambiguous_finance_events_after": "4",
            "ambiguous_status": "NEEDS_CONFIRMATION",
            "ambiguous_code": "person_name_ambiguous",
            "ambiguous_target_present": "false",
        }
        passed, checks = lab.validate_bmw_ui_fixture_readback(fields)
        self.assertFalse(passed)
        self.assertFalse(checks["outstanding_minor_units"])
        self.assertFalse(checks["case_evidence_count"])
        self.assertFalse(checks["bmw_action_result_count"])
        self.assertFalse(checks["ambiguous_finance_events_unchanged"])

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
