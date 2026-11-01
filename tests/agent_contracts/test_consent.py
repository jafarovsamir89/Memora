"""The format and category tests are not evidence that transmission is authorized."""
import copy
import json
import unittest
from pathlib import Path
from jsonschema import Draft202012Validator, FormatChecker

ROOT = Path(__file__).resolve().parents[2] / "contracts/agent/v1"


def verify(schema_name, item):
    schema = json.loads((ROOT / (schema_name + ".schema.json")).read_text(encoding="utf-8"))
    Draft202012Validator.check_schema(schema)
    Draft202012Validator(schema, format_checker=FormatChecker()).validate(item)
    if schema_name == "consent-ticket":
        categories = [r["category"] for r in item["records"]]
        ids = [r["id"] for r in item["records"]]
        expected = {"VOICE_OR_TEXT_TURN"} if item["kind"] == "CURRENT_TURN" else {"VOICE_OR_TEXT_TURN", "PERSISTED_MEMORY"}
        if len(set(ids)) != len(ids) or len(set(categories)) != len(categories) or set(categories) != expected:
            raise ValueError("wrong or duplicate consent categories")


class ConsentSchemaTest(unittest.TestCase):
    def setUp(self):
        self.ticket = json.loads((ROOT / "fixtures/consent-ticket.json").read_text(encoding="utf-8"))

    def test_memory_and_current_turn_have_distinct_requirements(self):
        verify("consent-ticket", self.ticket)
        current = copy.deepcopy(self.ticket)
        current["kind"] = "CURRENT_TURN"
        current["records"] = current["records"][:1]
        verify("consent-ticket", current)
        current["kind"] = "MEMORY_EXCERPT"
        with self.assertRaises(ValueError):
            verify("consent-ticket", current)

    def test_rejects_unknown_provider_fields_and_duplicate_categories(self):
        for change in ("provider", "extra", "duplicate"):
            item = copy.deepcopy(self.ticket)
            if change == "provider": item["provider"] = "OTHER"
            elif change == "extra": item["approved"] = True
            else: item["records"][1]["category"] = item["records"][0]["category"]
            with self.assertRaises(Exception):
                verify("consent-ticket", item)

    def test_record_format_does_not_make_status_granted(self):
        record = {"id": self.ticket["records"][0]["id"], "scopeId": self.ticket["scopeId"],
                  "provider": "GEMINI", "category": "VOICE_OR_TEXT_TURN", "policyVersion": 1,
                  "revision": 2, "status": "REVOKED"}
        verify("consent-record", record)
        record["status"] = "ASSUMED"
        with self.assertRaises(Exception):
            verify("consent-record", record)


if __name__ == "__main__":
    unittest.main()
