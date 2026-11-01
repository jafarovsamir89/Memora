import json
import unittest
from pathlib import Path

PATH = Path(__file__).resolve().parents[2] / "contracts/agent/v1/policy-matrix.json"


class PolicyMatrixTest(unittest.TestCase):
    def test_every_planned_tool_defaults_to_unavailable(self):
        policy = json.loads(PATH.read_text(encoding="utf-8"))
        self.assertEqual("design_not_authorization", policy["status"])
        self.assertEqual("deny", policy["default"])
        self.assertTrue(policy["cloudMemoryRequiresSeparateConsent"])
        self.assertTrue(policy["allToolsRequireImplementedHandlerAndPolicy"])
        tools = {item["tool"]: item for item in policy["tools"]}
        self.assertEqual(len(tools), len(policy["tools"]))
        self.assertEqual(32, len(tools))
        self.assertTrue(all(not row["runtimeEnabled"] for row in tools.values()))
        self.assertEqual({"search_memory", "create_note", "update_note", "read_records", "resolve_person", "create_person", "update_person", "create_project", "update_project", "link_records", "unlink_records", "preview_delete", "commit_delete", "create_task", "update_task", "query_finance", "query_table", "aggregate_table", "request_clarification", "create_calendar_event", "update_calendar_event", "record_receipt", "record_obligation", "record_settlement", "correct_money_event", "create_table", "add_table_rows", "update_table_rows", "change_table_schema", "restore_records", "undo_change", "prepare_changes"},
                         {name for name, row in tools.items() if row["schemaAvailable"]})
        schema_path = PATH.parent / "model-tool-call.schema.json"
        schema = json.loads(schema_path.read_text(encoding="utf-8"))
        self.assertEqual(set(schema["properties"]["toolName"]["enum"]),
                         {name for name, row in tools.items() if row["schemaAvailable"]})
        self.assertEqual(20, policy["globalConditions"]["massEditRequiresPreviewAboveExistingObjectCount"])
        self.assertTrue(policy["globalConditions"]["planInheritsStrictestEmbeddedPolicy"])
        self.assertTrue(policy["globalConditions"]["confirmationsMustBindExactDigestAndVersions"])
        for name in ("commit_delete", "record_settlement", "correct_money_event", "change_table_schema"):
            self.assertEqual("preview_and_confirm", tools[name]["targetPolicy"])
        self.assertEqual("plan_only_inherit_highest_risk", tools["prepare_changes"]["targetPolicy"])
        self.assertEqual("preview_only", tools["preview_delete"]["targetPolicy"])
        self.assertIn("external_payment", policy["forbiddenAutomaticExternalEffects"])


if __name__ == "__main__":
    unittest.main()
