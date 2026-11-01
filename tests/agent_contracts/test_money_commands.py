import copy
import unittest

from canonical import command_digest
from test_contracts import bind_money_result, check

U = "00000000-0000-4000-8000-000000000021"
V = "00000000-0000-4000-8000-000000000022"

class MoneyCommandsTest(unittest.TestCase):
    def proposal(self, tool, args):
        return {"toolName":tool,"schemaVersion":1,"providerCallId":"synthetic-money","arguments":args}

    def envelope(self, tool, args, confirmed=False):
        cmd={"protocolVersion":1,"commandId":"00000000-0000-4000-8000-000000000001","turnId":U,"stepId":V,
            "scopeId":"00000000-0000-4000-8000-000000000004","actorId":"00000000-0000-4000-8000-000000000005",
            "toolName":tool,"toolVersion":1,"arguments":args,"expectedVersions":[{"id":args.get('personId',args.get('obligationId',args.get('targetEventId'))),'version':1}],
            "sourceRevisionId":"00000000-0000-4000-8000-000000000007","evidenceRefs":["00000000-0000-4000-8000-000000000008"],
            "payloadDigest":"","permissionSnapshotVersion":1,"confirmationId":"00000000-0000-4000-8000-000000000009" if confirmed else None}
        cmd['payloadDigest']=command_digest(cmd)
        return cmd

    def test_receipt_obligation_and_settlement_use_exact_integer_amounts(self):
        base={"personId":U,"amountMinorUnits":12345,"currency":"AZN","currencyScaleVersion":1,"occurredAt":"2026-10-31T10:00:00Z"}
        check('model-tool-call',self.proposal('record_receipt',{**base,"direction":"IN"}))
        check('model-tool-call',self.proposal('record_obligation',{**base,"direction":"PERSON_OWES_USER"}))
        settlement={"obligationId":U,"amountMinorUnits":500,"currency":"AZN","currencyScaleVersion":1,"occurredAt":"2026-10-31T10:00:00Z","settlementId":V,"expectedRemainingMinorUnits":1200}
        with self.assertRaises(Exception): check('command-envelope',self.envelope('record_settlement',settlement))
        check('command-envelope',self.envelope('record_settlement',settlement,confirmed=True))
        settlement['amountMinorUnits']=1300
        with self.assertRaises(ValueError):check('command-envelope',self.envelope('record_settlement',settlement,confirmed=True))

    def test_wrong_currency_and_float_are_rejected(self):
        args={"personId":U,"amountMinorUnits":10.5,"direction":"IN","currency":"AZN","currencyScaleVersion":1,"occurredAt":"2026-10-31T10:00:00Z"}
        with self.assertRaises(Exception):check('model-tool-call',self.proposal('record_receipt',args))
        args['amountMinorUnits']=10;args['currency']='AZ'
        with self.assertRaises(Exception):check('model-tool-call',self.proposal('record_receipt',args))

    def test_result_cannot_change_amount_currency_or_target(self):
        args={"personId":U,"amountMinorUnits":12345,"direction":"IN","currency":"AZN","currencyScaleVersion":1,"occurredAt":"2026-10-31T10:00:00Z","eventId":V}
        cmd=self.envelope('record_receipt',args)
        result={"protocolVersion":1,"callId":"synthetic-money","commandId":cmd['commandId'],"status":"committed",
            "data":{"kind":"money_event","id":V,"version":1,"amountMinorUnits":12345,"currency":"AZN","currencyScaleVersion":1,"direction":"IN","occurredAt":"2026-10-31T10:00:00Z"},
            "affected":[{"id":V,"version":1}],"evidenceRefs":cmd['evidenceRefs'],"completeness":None,"errorCode":None,"retryable":False,"nextCursor":None}
        bind_money_result(cmd,result)
        bad=copy.deepcopy(result);bad['data']['amountMinorUnits']=12346
        with self.assertRaises(ValueError):bind_money_result(cmd,bad)

if __name__=='__main__':unittest.main()
