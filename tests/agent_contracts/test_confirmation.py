"""Synthetic confirmation-contract tests; no permission is granted by this module."""
import copy
import datetime
import json
import unittest
from pathlib import Path
from jsonschema import Draft202012Validator, FormatChecker

ROOT = Path(__file__).resolve().parents[2] / "contracts/agent/v1"
SCHEMA = json.loads((ROOT / "confirmation-proof.schema.json").read_text(encoding="utf-8"))


def verify(proof):
    Draft202012Validator.check_schema(SCHEMA)
    Draft202012Validator(SCHEMA, format_checker=FormatChecker()).validate(proof)
    ids = [x["id"] for x in proof["targetVersions"]]
    if len(ids) != len(set(ids)):
        raise ValueError("duplicate target")
    parse = lambda t: datetime.datetime.fromisoformat(t.replace("Z", "+00:00"))
    lifetime = (parse(proof["expiresAt"]) - parse(proof["issuedAt"])).total_seconds()
    if not 0 < lifetime <= 600:
        raise ValueError("invalid lifetime")


class ConfirmationSchemaTest(unittest.TestCase):
    def setUp(self):
        self.proof = json.loads((ROOT / "fixtures/confirmation-proof.json").read_text(encoding="utf-8"))

    def test_fixture_and_state_variants(self):
        for state in ("ACTIVE", "CONSUMED", "REVOKED"):
            p = copy.deepcopy(self.proof)
            p["state"] = state
            verify(p)

    def test_rejects_forged_or_ambiguous_shapes_and_times(self):
        changes = [
            lambda p: p.update(approved=True),
            lambda p: p.update(actionDigest="short"),
            lambda p: p.update(expiresAt="2026-01-01T00:10:01Z"),
            lambda p: p.update(expiresAt=p["issuedAt"]),
            lambda p: p.update(state="UNKNOWN"),
            lambda p: p["targetVersions"].append({"id": p["targetVersions"][0]["id"], "version": 6}),
        ]
        for change in changes:
            p = copy.deepcopy(self.proof)
            change(p)
            with self.assertRaises(Exception):
                verify(p)


if __name__ == "__main__":
    unittest.main()
