import json
import unittest
from pathlib import Path

PATH = Path(__file__).resolve().parents[2] / "contracts/agent/v1/evaluation-plan.json"


class EvaluationPlanTest(unittest.TestCase):
    def test_manifest_is_planned_synthetic_and_includes_language_minima(self):
        m = json.loads(PATH.read_text(encoding="utf-8"))
        self.assertEqual("planned_not_executed", m["status"])
        self.assertEqual("synthetic_only", m["dataPolicy"])
        self.assertEqual("gemini_only", m["providerScope"])
        self.assertEqual({"az": 100, "ru": 60, "mixed": 80}, m["minimumHoldoutText"])
        self.assertEqual({"az": 30, "ru": 15, "mixed": 15}, m["minimumHoldoutSyntheticAudio"])
        self.assertTrue(all(m["splits"].values()))
        self.assertEqual(0, m["maxUnauthorizedActions"])
        self.assertEqual(0, m["maxRetryLossOrDuplicateEffects"])
        self.assertEqual(0, m["maxFinancialArithmeticErrors"])
        self.assertEqual(0, m["maxFalseSuccessClaims"])
        self.assertEqual(100, m["minDeterministicPassPercent"])
        self.assertEqual("implemented_not_run_on_model_or_holdout", m["metricsImplementation"]["status"])
        self.assertIn("no utterance text", m["metricsImplementation"]["outputPolicy"])


if __name__ == "__main__":
    unittest.main()
