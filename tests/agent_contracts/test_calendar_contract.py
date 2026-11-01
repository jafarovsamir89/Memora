import unittest

from test_contracts import check, bind_calendar_result
from canonical import command_digest

class CalendarContractTest(unittest.TestCase):
    def call(self, tool, args):
        return {"toolName": tool, "schemaVersion": 1, "providerCallId": "synthetic-calendar", "arguments": args}

    def test_event_requires_explicit_valid_window(self):
        start = {"precision": "minute", "localDateTime": "2026-04-10T09:00", "zoneId": "Asia/Baku"}
        end = {"precision": "minute", "localDateTime": "2026-04-10T10:00", "zoneId": "Asia/Baku"}
        check("model-tool-call", self.call("create_calendar_event", {"title": "Görüş", "start": start, "end": end}))
        with self.assertRaises(ValueError):
            check("model-tool-call", self.call("create_calendar_event", {"title": "Görüş", "start": end, "end": start}))

    def test_dst_gap_overlap_and_zone_mismatch_are_rejected(self):
        for local in ("2026-03-08T02:30", "2026-11-01T01:30"):
            start = {"precision": "minute", "localDateTime": local, "zoneId": "America/Los_Angeles"}
            end = {"precision": "minute", "localDateTime": "2026-11-02T03:00", "zoneId": "America/Los_Angeles"}
            with self.assertRaises(ValueError):
                check("model-tool-call", self.call("create_calendar_event", {"title": "Test", "start": start, "end": end}))

    def test_date_only_events_do_not_invent_time(self):
        args = {"title": "Tətil", "start": {"precision": "date", "date": "2026-05-01"}, "end": {"precision": "date", "date": "2026-05-03"}}
        check("model-tool-call", self.call("create_calendar_event", args))

    def test_calendar_command_adds_app_id_and_expected_version(self):
        args={"title":"Görüş","start":{"precision":"date","date":"2026-05-01"},"end":{"precision":"date","date":"2026-05-02"},"eventId":"00000000-0000-4000-8000-000000000006"}
        cmd={"protocolVersion":1,"commandId":"00000000-0000-4000-8000-000000000001","turnId":"00000000-0000-4000-8000-000000000002","stepId":"00000000-0000-4000-8000-000000000003","scopeId":"00000000-0000-4000-8000-000000000004","actorId":"00000000-0000-4000-8000-000000000005","toolName":"create_calendar_event","toolVersion":1,"arguments":args,"expectedVersions":[],"sourceRevisionId":"00000000-0000-4000-8000-000000000007","evidenceRefs":["00000000-0000-4000-8000-000000000008"],"payloadDigest":"","permissionSnapshotVersion":1,"confirmationId":None}
        cmd['payloadDigest']=command_digest(cmd)
        check('command-envelope',cmd)
        result={"protocolVersion":1,"callId":"synthetic-calendar","commandId":cmd['commandId'],"status":"committed","data":{"kind":"calendar_event","eventId":args['eventId'],"version":1,"title":args['title'],"start":args['start'],"end":args['end']},"affected":[{"id":args['eventId'],"version":1}],"evidenceRefs":cmd['evidenceRefs'],"completeness":None,"errorCode":None,"retryable":False,"nextCursor":None}
        bind_calendar_result(cmd,result)
        result['data']['title']='Changed'
        with self.assertRaises(ValueError):bind_calendar_result(cmd,result)

if __name__ == "__main__": unittest.main()
