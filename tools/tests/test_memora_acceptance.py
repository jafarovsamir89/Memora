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


class VolumeProbeResultTests(unittest.TestCase):
    def test_persisted_larger_fixture_is_explicitly_not_applicable(self) -> None:
        self.assertEqual(lab.volume_probe_step_status("synthetic_volume_not_applicable"), "NOT APPLICABLE")

    def test_volume_probe_success_and_unknown_statuses_are_not_conflated(self) -> None:
        self.assertEqual(lab.volume_probe_step_status("synthetic_volume_probe_passed"), "PASS")
        self.assertEqual(lab.volume_probe_step_status("synthetic_volume_probe_failed"), "FAIL")
        self.assertEqual(lab.volume_probe_step_status(None), "FAIL")

    def test_not_applicable_is_not_a_successful_overall_run(self) -> None:
        self.assertEqual(lab.acceptance_report_status([{"status": "PASS"}, {"status": "NOT APPLICABLE"}]), "PARTIAL")

    def test_failed_step_takes_precedence_over_partial_and_success(self) -> None:
        self.assertEqual(lab.acceptance_report_status([
            {"status": "PASS"}, {"status": "NOT APPLICABLE"}, {"status": "FAIL"},
        ]), "FAIL")
        self.assertEqual(lab.acceptance_report_status([{"status": "PASS"}]), "PASS")


class ProcessDeathProbeTests(unittest.TestCase):
    def test_transaction_process_death_requires_every_recovery_invariant_and_cleanup(self) -> None:
        fields = {
            "status": "passed",
            "probe_process_id": "124",
            "committed_transaction_survived_process_death": "true",
            "uncommitted_transaction_rolled_back": "true",
            "reopened_database_has_no_plaintext_marker": "true",
            "named_test_files_removed": "true",
            "test_alias_removed": "true",
        }
        checks = lab.process_death_probe_checks("transaction", fields, cleanup_absent=True,
                                                prepared_pid=123, prepare_process_exited=True)
        self.assertTrue(all(checks.values()))
        self.assertFalse(lab.process_death_probe_checks(
            "transaction", fields, cleanup_absent=False, prepared_pid=123, prepare_process_exited=True,
        )["named_test_files_removed"])

    def test_key_rotation_process_death_requires_reconciliation_invariants(self) -> None:
        fields = {
            "status": "passed",
            "probe_process_id": "456",
            "rekey_recovered_after_process_force_stop": "true",
            "synthetic_marker_survived": "true",
            "staged_envelope_promoted": "true",
            "named_test_files_removed": "true",
            "test_alias_removed": "true",
        }
        self.assertTrue(all(lab.process_death_probe_checks(
            "key_rotation", fields, cleanup_absent=True, prepared_pid=123, prepare_process_exited=True,
        ).values()))
        fields["staged_envelope_promoted"] = "false"
        self.assertFalse(all(lab.process_death_probe_checks(
            "key_rotation", fields, cleanup_absent=True, prepared_pid=123, prepare_process_exited=True,
        ).values()))

    def test_prepare_process_must_be_gone_and_recovery_pid_must_change(self) -> None:
        fields = {"status": "passed", "probe_process_id": "123"}
        checks = lab.process_death_probe_checks("transaction", fields, cleanup_absent=True,
                                                prepared_pid=123, prepare_process_exited=False)
        self.assertFalse(checks["prepare_process_exited"])
        self.assertFalse(checks["recovery_ran_in_new_process"])


class LineageSettingsReadbackTests(unittest.TestCase):
    def test_synthetic_settings_fixture_is_complete_and_never_grants_consent(self) -> None:
        fixture = lab.synthetic_settings_fixture_snapshot()
        defaults = lab.synthetic_default_settings_snapshot()
        self.assertEqual(set(fixture), set(lab.LINEAGE_SETTINGS_KEYS))
        self.assertEqual(set(defaults), set(lab.LINEAGE_SETTINGS_KEYS))
        self.assertEqual(fixture["appearance_theme"], "dark")
        self.assertEqual(fixture["selected_gemini_model"], "gemini-3.6-flash")
        self.assertEqual(defaults["selected_gemini_model"], "gemini-3.5-flash-lite")
        for name in ("text", "voice", "memory", "audio_note", "live_voice", "legacy_audio"):
            self.assertEqual(fixture[f"consent_{name}_present"], "true")
            self.assertEqual(fixture[f"consent_{name}_granted"], "false")

    def test_lineage_probe_parses_fixture_phase_and_all_allowlisted_values(self) -> None:
        fixture = lab.synthetic_settings_fixture_snapshot()
        output = "INSTRUMENTATION_RESULT: status=synthetic_lineage_settings_fixture_passed\n" + "".join(
            f"INSTRUMENTATION_RESULT: {key}={value}\n" for key, value in fixture.items()
        )
        with patch.object(lab, "adb", return_value=SimpleNamespace(stdout=output, stderr="")):
            result = lab.run_lineage_probe("emulator-5556", 10, "prepare-settings")

        self.assertEqual(result["name"], "synthetic_lineage_prepare_settings")
        self.assertEqual(result["status"], "PASS")
        self.assertEqual(result["settings_snapshot"], fixture)

    def test_fixture_restore_runs_after_partial_setup_and_checks_original_snapshot(self) -> None:
        original = {"settings_snapshot": lab.synthetic_default_settings_snapshot()}
        steps = [
            {"name": "synthetic_lineage_verify", **original},
            {"name": "synthetic_lineage_prepare_settings", "status": "FAIL"},
        ]
        restore = {"name": "synthetic_lineage_restore_settings", "status": "PASS"}
        verify = {"name": "synthetic_lineage_verify", "status": "PASS",
                  "settings_snapshot": dict(original["settings_snapshot"])}
        with tempfile.TemporaryDirectory() as directory, \
             patch.object(lab, "run_lineage_probe", side_effect=[restore, verify]) as probe, \
             patch.object(lab, "adb", return_value=SimpleNamespace(stdout="10", stderr="")), \
             patch.object(lab, "run_user_launch", return_value={"status": "PASS", "screenshot": "ignored.png"}):
            lab.restore_lineage_settings_fixture("emulator-5556", 10, Path(directory), steps)

        self.assertEqual(probe.call_args_list, [
            call("emulator-5556", 10, "restore-settings"),
            call("emulator-5556", 10, "verify"),
        ])
        self.assertEqual(steps[-1]["name"], "settings_and_consents_after_fixture_restore")
        self.assertEqual(steps[-1]["status"], "PASS")

    def test_fixture_restore_is_not_attempted_without_fixture_setup(self) -> None:
        steps = [{"name": "synthetic_lineage_verify", "status": "PASS"}]
        with tempfile.TemporaryDirectory() as directory, patch.object(lab, "run_lineage_probe") as probe:
            lab.restore_lineage_settings_fixture("emulator-5556", 10, Path(directory), steps)
        probe.assert_not_called()

    def test_settings_and_effective_consent_state_must_match_across_update(self) -> None:
        snapshot = {
            "appearance_file_present": "true",
            "appearance_language": "az",
            "appearance_theme": "dark",
            "model_selection_present": "true",
            "selected_gemini_model": "gemini-3.6-flash",
            "consent_text_present": "true", "consent_text_granted": "false",
            "consent_voice_present": "true", "consent_voice_granted": "false",
            "consent_memory_present": "true", "consent_memory_granted": "false",
            "consent_audio_note_present": "false", "consent_audio_note_granted": "false",
            "consent_live_voice_present": "false", "consent_live_voice_granted": "false",
            "consent_legacy_audio_present": "false", "consent_legacy_audio_granted": "false",
        }
        reference = {"settings_snapshot": snapshot}
        actual = {"settings_snapshot": dict(snapshot)}
        self.assertEqual(lab.compare_lineage_settings(reference, actual, "after_update")["status"], "PASS")

        actual["settings_snapshot"]["consent_text_granted"] = "true"
        check = lab.compare_lineage_settings(reference, actual, "after_update")
        self.assertEqual(check["status"], "FAIL")
        self.assertFalse(check["unchanged_from_pre_update"])

    def test_incomplete_settings_readback_is_not_counted_as_pass(self) -> None:
        check = lab.compare_lineage_settings(
            {"settings_snapshot": {"appearance_theme": "dark"}},
            {"settings_snapshot": {"appearance_theme": "dark"}},
            "after_rotation",
        )
        self.assertEqual(check["status"], "FAIL")
        self.assertFalse(check["snapshot_complete"])

    def test_fixture_preflight_uses_target_android_user_in_instrumentation(self) -> None:
        output = "INSTRUMENTATION_RESULT: status=synthetic_process_death_fixture_absent\n" \
            "INSTRUMENTATION_RESULT: named_test_files_absent=true\n" \
            "INSTRUMENTATION_RESULT: test_alias_absent=true\n"
        with patch.object(lab, "adb", return_value=SimpleNamespace(stdout=output, stderr="")) as adb:
            fields = lab.run_process_death_preflight("emulator-5556", 11, "transaction")
        self.assertEqual(fields["status"], "synthetic_process_death_fixture_absent")
        command = adb.call_args.args
        self.assertEqual(command[:4], ("emulator-5556", "shell", "am", "instrument"))
        self.assertEqual(command[4:7], ("--user", "11", "-w"))
        self.assertIn("transaction-preflight", command)

    def test_process_exit_check_is_scoped_to_expected_android_user(self) -> None:
        output = "USER PID\nu10_a198 123\nu11_a198 456\n"
        with patch.object(lab, "adb", return_value=SimpleNamespace(stdout=output)) as adb:
            self.assertFalse(lab.process_id_running_for_user("emulator-5556", 11, 123))
            self.assertTrue(lab.process_id_running_for_user("emulator-5556", 11, 456))
        self.assertEqual(adb.call_args_list[-1].args[:4], ("emulator-5556", "shell", "ps", "-A"))


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
        swipe.assert_called_once_with("emulator-5556", Path("/tmp"), "find-bmw-swipe-1", 0.50, 0.72, 0.50, 0.34)

    def test_scroll_until_texts_supports_bounded_small_swipes_for_dense_chat_cards(self) -> None:
        old = lab.ET.fromstring('<hierarchy><node text="Task card"/></hierarchy>')
        target = lab.ET.fromstring('<hierarchy><node text="Finance action"/><node text="Exact advance"/></hierarchy>')
        with patch.object(lab, "ui_tree", side_effect=[old, target]), \
             patch.object(lab, "swipe_ui") as swipe:
            result = lab.scroll_until_ui_texts(
                "emulator-5556", Path("/tmp"), "find-finance-card",
                {"Finance action", "Exact advance"}, "up", max_swipes=3,
                swipe_start_y_fraction=0.68, swipe_end_y_fraction=0.42,
            )

        self.assertIs(result, target)
        swipe.assert_called_once_with(
            "emulator-5556", Path("/tmp"), "find-finance-card-swipe-1",
            0.50, 0.68, 0.50, 0.42,
        )

    def test_scroll_until_texts_rejects_partial_custom_swipe_coordinates(self) -> None:
        with self.assertRaisesRegex(ValueError, "provided together"):
            lab.scroll_until_ui_texts(
                "emulator-5556", Path("/tmp"), "find-finance-card",
                {"Finance action"}, "up", swipe_start_y_fraction=0.68,
            )

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
            direction="up", max_swipes=8,
            swipe_start_y_fraction=0.68, swipe_end_y_fraction=0.42)
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
        self.assertEqual(source_scroll.call_args_list, [
            call("emulator-5556", out_dir, "person-finance-find-exact-advance",
                 {"100 AZN", "Synthetic BMW repair advance received."}, direction="up", max_swipes=8),
            call("emulator-5556", out_dir, "task-source-open-conversation", {"Söhbəti katibədə aç"},
                 direction="up", max_swipes=8),
            call("emulator-5556", out_dir, "finance-back-person-find-exact-advance",
                 {"100 AZN", "Synthetic BMW repair advance received."}, direction="up", max_swipes=8),
        ])
        restored_person_finance = next(call.args[3] for call in wait.call_args_list
                                       if call.args[2] == "finance-back-to-person-finance")
        self.assertEqual(restored_person_finance, {
            "100 AZN",
            "Synthetic BMW repair advance received.",
        })
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
        self.assertEqual(scroll.call_args_list, [
            call("emulator-5556", out_dir, "secretary-find-ambiguous-turn", {
                "Sintetik yoxlama: Ruslan mənə 150 manat qaytardı; hansı Ruslan olduğunu dəqiqləşdirin.",
                "Hansı Ruslanı nəzərdə tutursunuz? Qeydi dəqiqləşdirmədən heç nə saxlamadım.",
            }, max_swipes=8),
            call("emulator-5556", out_dir, "secretary-ambiguous-no-write", {
                "Təsdiq gözlənilir",
                "Təsdiq verilməyənədək heç bir dəyişiklik edilməyib.",
            }, max_swipes=4),
        ])
        capture.assert_called_once()

    def test_correction_ui_capture_prepares_exact_id_and_reads_append_only_history(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            out_dir = Path(directory)
            with patch.object(lab, "launch_synthetic_today"), \
                 patch.object(lab, "tap_ui_text") as tap, \
                 patch.object(lab, "wait_for_ui_texts") as wait, \
                 patch.object(lab, "wait_for_ui_fragments") as fragments, \
                 patch.object(lab, "scroll_until_ui_texts_either_direction") as scroll_card, \
                 patch.object(lab, "scroll_to_top_and_wait_for_ui_texts") as scroll_top, \
                 patch.object(lab, "scroll_until_ui_texts") as scroll_history, \
                 patch.object(lab, "screenshot") as capture, \
                 patch.object(lab, "adb") as adb, \
                 patch.object(lab, "ROOT", out_dir):
                paths = lab.capture_finance_correction_ui("emulator-5556", out_dir)

        self.assertEqual(len(paths), 4)
        self.assertTrue(paths[0].endswith("p0-3-correction-action-card.png"))
        self.assertTrue(paths[1].endswith("p0-3-correction-exact-id-draft.png"))
        self.assertTrue(paths[2].endswith("p0-3-correction-finance-readback.png"))
        self.assertTrue(paths[3].endswith("p0-3-correction-readback-history.png"))
        self.assertEqual(capture.call_count, 4)
        self.assertEqual(fragments.call_args_list[0].args[3], {
            "00000000-0000-4000-8000-000000000296", "nümunə qəbzin məbləği 50 AZN idi",
        })
        self.assertEqual(fragments.call_args_list[1].args[3], {
            "00000000-0000-4000-8000-000000000296", "nümunə qəbzin məbləği 50 AZN idi",
        })
        self.assertEqual(scroll_history.call_args.args[3],
                         {"Məbləğ düzəlişi", "Synthetic correction UI readback"})
        self.assertIn(call("emulator-5556", "shell", "input", "keyevent", "4"), adb.call_args_list)
        self.assertEqual(tap.call_count, 3)
        self.assertEqual(scroll_top.call_count, 1)


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

    def test_rotation_capture_waits_for_stable_home_content_before_screenshot(self) -> None:
        events: list[tuple[str, object]] = []

        def fake_adb(_serial: str, *args: str, **_kwargs: object) -> SimpleNamespace:
            if args[:2] == ("shell", "settings") and "get" in args:
                return SimpleNamespace(stdout={
                    "accelerometer_rotation": "1",
                    "user_rotation": "0",
                }[args[-1]])
            if args[:3] == ("shell", "am", "start"):
                return SimpleNamespace(stdout="Status: ok\n")
            return SimpleNamespace(stdout="")

        def ready_wait(*args: object, **kwargs: object) -> None:
            events.append(("wait", (args, kwargs)))

        def capture(_serial: str, path: Path) -> None:
            events.append(("screenshot", path.name))

        with tempfile.TemporaryDirectory() as directory, \
             patch.object(lab, "adb", side_effect=fake_adb), \
             patch.object(lab.time, "sleep"), \
             patch.object(lab, "wait_for_ui_texts", side_effect=ready_wait), \
             patch.object(lab, "screenshot", side_effect=capture), \
             patch.object(lab, "screenshot_dimensions", return_value=(2400, 1080)), \
             patch.object(lab, "run_lineage_probe", return_value={"status": "PASS"}):
            steps = lab.run_rotation_recreation("emulator-5556", 10, Path(directory))

        wait_index = next(index for index, event in enumerate(events) if event[0] == "wait")
        screenshot_index = next(index for index, event in enumerate(events) if event[0] == "screenshot")
        self.assertLess(wait_index, screenshot_index)
        wait_args, wait_kwargs = events[wait_index][1]
        self.assertEqual(wait_args[2:4], ("rotation-landscape-ready", {"Katibə", "Bu gün"}))
        self.assertEqual(wait_kwargs, {"timeout_seconds": 30, "stable_samples": 3})
        self.assertEqual(steps[0]["status"], "PASS")
        self.assertEqual(steps[-1]["status"], "PASS")

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

    def test_finance_correction_fixture_requires_exact_amount_delta_and_target(self) -> None:
        fields = {
            "status": "synthetic_finance_ui_journey_fixture_passed",
            "correction_event_id": "00000000-0000-4000-8000-000000000296",
            "correction_turn_id": "00000000-0000-4000-8000-000000000297",
            "correction_effective_minor_units": "4500",
            "correction_adjustment_minor_units": "-500",
            "correction_adjustment_count": "1",
            "correction_message_count": "2",
            "correction_action_result_count": "1",
            "correction_target_matches": "true",
        }
        passed, checks = lab.validate_finance_correction_fixture_readback(fields)
        self.assertTrue(passed)
        self.assertTrue(all(checks.values()))

    def test_finance_correction_fixture_rejects_duplicate_or_wrong_target_readback(self) -> None:
        fields = {
            "status": "synthetic_finance_ui_journey_fixture_passed",
            "correction_event_id": "00000000-0000-4000-8000-000000000296",
            "correction_turn_id": "00000000-0000-4000-8000-000000000297",
            "correction_effective_minor_units": "4500",
            "correction_adjustment_minor_units": "-500",
            "correction_adjustment_count": "2",
            "correction_message_count": "2",
            "correction_action_result_count": "1",
            "correction_target_matches": "false",
        }
        passed, checks = lab.validate_finance_correction_fixture_readback(fields)
        self.assertFalse(passed)
        self.assertFalse(checks["one_correction_adjustment"])
        self.assertFalse(checks["action_target_is_same_exact_finance_id"])

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

    def test_lineage_probe_captures_only_allowlisted_settings_and_consent_fields(self) -> None:
        output = "INSTRUMENTATION_RESULT: status=synthetic_lineage_verify_passed\n" \
            "INSTRUMENTATION_RESULT: appearance_file_present=true\n" \
            "INSTRUMENTATION_RESULT: appearance_language=az\n" \
            "INSTRUMENTATION_RESULT: appearance_theme=dark\n" \
            "INSTRUMENTATION_RESULT: model_selection_present=true\n" \
            "INSTRUMENTATION_RESULT: selected_gemini_model=gemini-3.6-flash\n" \
            "INSTRUMENTATION_RESULT: consent_text_present=true\n" \
            "INSTRUMENTATION_RESULT: consent_text_granted=false\n" \
            "INSTRUMENTATION_RESULT: consent_voice_present=true\n" \
            "INSTRUMENTATION_RESULT: consent_voice_granted=false\n" \
            "INSTRUMENTATION_RESULT: consent_memory_present=true\n" \
            "INSTRUMENTATION_RESULT: consent_memory_granted=false\n" \
            "INSTRUMENTATION_RESULT: consent_audio_note_present=false\n" \
            "INSTRUMENTATION_RESULT: consent_audio_note_granted=false\n" \
            "INSTRUMENTATION_RESULT: consent_live_voice_present=false\n" \
            "INSTRUMENTATION_RESULT: consent_live_voice_granted=false\n" \
            "INSTRUMENTATION_RESULT: consent_legacy_audio_present=false\n" \
            "INSTRUMENTATION_RESULT: consent_legacy_audio_granted=false\n"
        with patch.object(lab, "adb", return_value=SimpleNamespace(stdout=output, stderr="")):
            result = lab.run_lineage_probe("emulator-5556", 10, "verify")
        self.assertEqual(result["status"], "PASS")
        self.assertEqual(result["settings_snapshot"]["selected_gemini_model"], "gemini-3.6-flash")
        self.assertEqual(result["settings_snapshot"]["consent_text_granted"], "false")
        self.assertEqual(len(result["settings_snapshot"]), 17)

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
