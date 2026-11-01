import unittest

from test_contracts import check, bind_table_result

U="00000000-0000-4000-8000-000000000021"
V="00000000-0000-4000-8000-000000000022"

def call(tool,args):return {"toolName":tool,"schemaVersion":1,"providerCallId":"table-synthetic","arguments":args}

class TableCommandsTest(unittest.TestCase):
    def test_typed_table_schema_and_command_authored_ids(self):
        args={"title":"Xərclər","mode":"editable","columns":[{"name":"Məbləğ","type":"money","required":True,"position":0}]}
        check('model-tool-call',call('create_table',args))
        args['columns'][0]['columnId']=U;args['tableId']=V
        cmd={"protocolVersion":1,"commandId":U,"turnId":V,"stepId":U,"scopeId":U,"actorId":V,"toolName":"create_table","toolVersion":1,"arguments":args,"expectedVersions":[],"sourceRevisionId":U,"evidenceRefs":[V],"payloadDigest":"0"*64,"permissionSnapshotVersion":1,"confirmationId":None}
        from canonical import command_digest
        cmd['payloadDigest']=command_digest(cmd)
        check('command-envelope',cmd)

    def test_rows_are_bounded_and_exactly_versioned(self):
        args={"tableId":U,"expectedSchemaVersion":2,"rows":[{"cells":[{"columnId":V,"value":{"minorUnits":1200,"currency":"AZN","currencyScaleVersion":1}}]}]}
        check('model-tool-call',call('add_table_rows',args))
        cmd={"protocolVersion":1,"commandId":U,"turnId":V,"stepId":U,"scopeId":U,"actorId":V,"toolName":"update_table_rows","toolVersion":1,"arguments":{"tableId":U,"expectedSchemaVersion":2,"rows":[{"rowId":V,"expectedVersion":1,"patch":[{"columnId":V,"value":9}]}]},"expectedVersions":[{"id":U,"version":2},{"id":V,"version":1}],"sourceRevisionId":U,"evidenceRefs":[V],"payloadDigest":"0"*64,"permissionSnapshotVersion":1,"confirmationId":None}
        from canonical import command_digest
        cmd['payloadDigest']=command_digest(cmd)
        check('command-envelope',cmd)

    def test_schema_change_requires_confirmation(self):
        args={"tableId":U,"expectedSchemaVersion":2,"patch":[{"columnId":V,"operation":"remove"}]}
        cmd={"protocolVersion":1,"commandId":U,"turnId":V,"stepId":U,"scopeId":U,"actorId":V,"toolName":"change_table_schema","toolVersion":1,"arguments":args,"expectedVersions":[{"id":U,"version":2}],"sourceRevisionId":U,"evidenceRefs":[V],"payloadDigest":"0"*64,"permissionSnapshotVersion":1,"confirmationId":None}
        from canonical import command_digest
        cmd['payloadDigest']=command_digest(cmd)
        with self.assertRaises(Exception):check('command-envelope',cmd)
        cmd['confirmationId']=V;cmd['payloadDigest']=command_digest(cmd)
        check('command-envelope',cmd)

    def test_result_must_cover_each_changed_row_exactly_once(self):
        cmd={"protocolVersion":1,"commandId":U,"turnId":V,"stepId":U,"scopeId":U,"actorId":V,"toolName":"add_table_rows","toolVersion":1,"arguments":{"tableId":U,"expectedSchemaVersion":2,"rows":[{"cells":[{"columnId":V,"value":5}]}],"rowIds":[V]},"expectedVersions":[{"id":U,"version":2}],"sourceRevisionId":U,"evidenceRefs":[V],"payloadDigest":"0"*64,"permissionSnapshotVersion":1,"confirmationId":None}
        from canonical import command_digest
        cmd['payloadDigest']=command_digest(cmd)
        result={"protocolVersion":1,"callId":"synthetic-table","commandId":U,"status":"committed","data":{"kind":"table_rows_changed","tableId":U,"schemaVersion":2,"rowIds":[V],"versions":[1]},"affected":[{"id":V,"version":1}],"evidenceRefs":[V],"completeness":None,"errorCode":None,"retryable":False,"nextCursor":None}
        bind_table_result(cmd,result)
        result['data']['rowIds']=[]
        with self.assertRaises(ValueError):bind_table_result(cmd,result)

if __name__=='__main__':unittest.main()
