import copy
import json
import unittest
from pathlib import Path

from test_contracts import bind_finance_result, check

ROOT = Path(__file__).resolve().parents[2] / "contracts/agent/v1"


class FinanceQueryContractTest(unittest.TestCase):
    def setUp(self):
        self.call = {"toolName": "query_finance", "schemaVersion": 1, "providerCallId": "finance-synthetic-1", "arguments": {
            "operationTypes": ["RECEIPT", "SETTLEMENT"], "currency": "AZN",
            "period": {"from": "2026-01-01", "untilExclusive": "2026-02-01"},
            "asOf": "2026-02-01T00:00:00Z"}}
        self.result = {"protocolVersion": 1, "callId": "finance-synthetic-1", "commandId": None,
            "status": "found", "data": {"kind": "finance_summary", "currency": "AZN", "minorUnitScale": 2,
                "totalMinorUnits": 12500, "eventIds": ["00000000-0000-4000-8000-000000000006"],
                "asOf": "2026-02-01T00:00:00Z", "periodStart": "2026-01-01", "periodEndExclusive": "2026-02-01"},
            "affected": [], "evidenceRefs": [], "completeness": "complete", "errorCode": None,
            "retryable": False, "nextCursor": None}

    def test_exact_currency_period_and_supporting_events_bind(self):
        check("model-tool-call", self.call)
        bind_finance_result(self.call, self.result)

    def test_rejects_empty_or_mismatched_period_and_currency(self):
        bad = copy.deepcopy(self.call)
        bad["arguments"]["period"]["from"] = "2026-02-01"
        with self.assertRaises(ValueError): check("model-tool-call", bad)
        for field, value in (("currency", "USD"), ("periodStart", "2025-12-01")):
            result = copy.deepcopy(self.result)
            if field == "currency": result["data"][field] = value
            else: result["data"][field] = value
            with self.assertRaises(ValueError): bind_finance_result(self.call, result)

    def test_empty_summary_is_exact_zero_without_event_ids(self):
        result = copy.deepcopy(self.result)
        result["status"] = "empty"
        result["data"]["eventIds"] = []
        result["data"]["totalMinorUnits"] = 0
        bind_finance_result(self.call, result)
        result["data"]["totalMinorUnits"] = 1
        with self.assertRaises(ValueError): bind_finance_result(self.call, result)

    def test_rejects_floats_and_unsupported_output_fields(self):
        result = copy.deepcopy(self.result)
        result["data"]["totalMinorUnits"] = 125.5
        with self.assertRaises(Exception): check("tool-result", result)
        result = copy.deepcopy(self.result)
        result["data"]["exchangeRate"] = 1.5
        with self.assertRaises(Exception): check("tool-result", result)


if __name__ == "__main__":
    unittest.main()
