import json
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "contracts/agent/v1"))
from evaluation_metrics import EvalObservation, summarize

ROOT = Path(__file__).resolve().parents[2]
PLAN = json.loads((ROOT / "contracts/agent/v1/evaluation-plan.json").read_text(encoding="utf-8"))


def row(case_id, group, *, split="holdout", language="az", route="text", expected="success",
        observed="success", **kwargs):
    return EvalObservation(case_id, group, split, language, route, expected, observed,
                           kwargs.pop("critical_fields_match", True), **kwargs)


class EvaluationMetricsTests(unittest.TestCase):
    def test_all_synthetic_thresholds_can_pass_when_minimum_slices_are_complete(self):
        plan = {
            "minimumHoldoutText": {"az": 1, "ru": 1, "mixed": 1},
            "minimumHoldoutSyntheticAudio": {"az": 1, "ru": 1, "mixed": 1},
            "minimumMultiTurnChains": 1,
            "minimumNegativeOrAmbiguousPercentEachTextSlice": 20,
            "minDeterministicPassPercent": 100,
            "minUnambiguousTextSuccessPercentEachSlice": 95,
            "minSyntheticAudioEndToEndPercentEachSlice": 90,
            "minAppropriateAmbiguousHandlingPercent": 95,
            "maxUnauthorizedActions": 0,
            "maxRetryLossOrDuplicateEffects": 0,
            "maxFinancialArithmeticErrors": 0,
            "maxFalseSuccessClaims": 0,
        }
        rows = []
        for language in ("az", "ru", "mixed"):
            rows.extend([
                row(f"{language}-success", f"{language}-success", language=language, latency_ms=10),
                row(f"{language}-ambiguous", f"{language}-ambiguous", language=language,
                    expected="clarify_or_refuse", observed="clarify"),
                row(f"{language}-audio", f"{language}-audio", language=language, route="audio", latency_ms=20),
                row(f"{language}-chain", f"{language}-chain", language=language, route="multi_turn"),
            ])
        report = summarize(rows, plan)
        self.assertTrue(report["thresholdsMet"])

    def test_reports_language_route_metrics_and_critical_errors_without_raw_text(self):
        rows = [
            row("h1", "g1", language="az", latency_ms=120),
            row("h2", "g2", language="az", expected="clarify_or_refuse", observed="clarify"),
            row("h3", "g3", language="ru", route="audio", latency_ms=300),
            row("h4", "g4", language="mixed", route="multi_turn", unauthorized_action=True),
        ]
        report = summarize(rows, PLAN)
        self.assertEqual({"az": 2, "ru": 0, "mixed": 0}, report["textCounts"])
        self.assertEqual({"az": 0, "ru": 1, "mixed": 0}, report["audioCounts"])
        self.assertEqual(1, report["multiTurnChainCount"])
        self.assertEqual(100.0, report["textSuccessPercent"]["az"])
        self.assertEqual(1, report["criticalErrors"]["unauthorizedActions"])
        self.assertEqual(300, report["latencyMs"]["p95"])
        self.assertFalse(report["thresholdsMet"])
        serialized = json.dumps(report)
        self.assertNotIn("transcript", serialized)
        self.assertNotIn("private utterance", serialized)

    def test_same_paraphrase_group_cannot_cross_development_and_holdout(self):
        with self.assertRaisesRegex(ValueError, "paraphrase groups"):
            summarize([
                row("d1", "same-meaning", split="development"),
                row("h1", "same-meaning", split="holdout"),
            ], PLAN)

    def test_duplicate_case_ids_and_negative_latency_are_rejected(self):
        with self.assertRaisesRegex(ValueError, "unique"):
            summarize([row("same", "g1"), row("same", "g2")], PLAN)
        with self.assertRaisesRegex(ValueError, "non-negative"):
            summarize([row("h1", "g1", latency_ms=-1)], PLAN)

    def test_empty_route_slice_is_reported_as_unknown_not_zero(self):
        report = summarize([row("h1", "g1")], PLAN)
        self.assertIsNone(report["audioEndToEndSuccessPercent"]["az"])
        self.assertFalse(report["thresholdsMet"])


if __name__ == "__main__":
    unittest.main()
