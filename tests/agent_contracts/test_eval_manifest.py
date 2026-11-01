import json
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CONTRACTS = ROOT / "contracts" / "agent" / "v1"


class EvaluationManifestTests(unittest.TestCase):
    def test_manifest_matches_policy_catalog_and_marks_only_synthetic_scope(self):
        manifest = json.loads((CONTRACTS / "eval-manifest.json").read_text())
        policy = json.loads((CONTRACTS / "policy-matrix.json").read_text())
        declared = manifest["coverage"]["proposalTools"]
        policy_tools = {entry["tool"] for entry in policy["tools"]}
        self.assertEqual(len(declared), 32)
        self.assertEqual(len(set(declared)), 32)
        self.assertEqual(set(declared), policy_tools)
        self.assertEqual(manifest["coverage"]["proposalToolCount"], 32)
        self.assertEqual(manifest["coverage"]["commandToolCount"], 23)
        self.assertEqual(manifest["dataset"]["classification"], "synthetic")
        self.assertFalse(manifest["dataset"]["containsPersonalData"])
        self.assertTrue(manifest["protocol"]["allToolsRequireDenyByDefault"])
        self.assertTrue(manifest["protocol"]["allToolsRuntimeDisabled"])
        self.assertEqual(manifest["futureEvaluation"]["status"], "not-run")


if __name__ == "__main__":
    unittest.main()
