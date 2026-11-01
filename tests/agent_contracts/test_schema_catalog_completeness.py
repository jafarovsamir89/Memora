import json
import unittest
from pathlib import Path

ROOT=Path(__file__).resolve().parents[2]/'contracts/agent/v1'

class SchemaCatalogCompletenessTest(unittest.TestCase):
    def test_every_internal_reference_resolves(self):
        for filename in ('model-tool-call.schema.json','command-envelope.schema.json','tool-result.schema.json'):
            schema=json.loads((ROOT/filename).read_text())
            def walk(value):
                if isinstance(value,dict):
                    ref=value.get('$ref')
                    if ref and ref.startswith('#/'):
                        target=schema
                        for part in ref[2:].split('/'):
                            target=target[part.replace('~1','/').replace('~0','~')]
                    for child in value.values():walk(child)
                elif isinstance(value,list):
                    for child in value:walk(child)
            walk(schema)

    def test_all_32_proposals_have_exactly_one_typed_branch_and_deny_policy(self):
        model=json.loads((ROOT/'model-tool-call.schema.json').read_text())
        policy=json.loads((ROOT/'policy-matrix.json').read_text())
        names=set(model['properties']['toolName']['enum'])
        branches=[row['properties']['toolName']['const'] for row in model['oneOf']]
        self.assertEqual(32,len(names));self.assertEqual(names,set(branches));self.assertEqual(len(branches),len(set(branches)))
        entries={row['tool']:row for row in policy['tools']}
        self.assertEqual(names,set(entries));self.assertTrue(all(not row['runtimeEnabled'] for row in entries.values()))
        self.assertTrue(all(row['schemaAvailable'] for row in entries.values()))
        examples=json.loads((ROOT/'fixtures/all-proposals.json').read_text())
        self.assertEqual(names,{example['toolName'] for example in examples})
        from test_contracts import check
        for example in examples:check('model-tool-call',example)

    def test_all_mutating_proposals_have_command_or_plan_result_coverage(self):
        names=set(json.loads((ROOT/'model-tool-call.schema.json').read_text())['properties']['toolName']['enum'])
        command=json.loads((ROOT/'command-envelope.schema.json').read_text())
        command_names=set(command['properties']['toolName']['enum'])
        branches={row['properties']['toolName']['const'] for row in command['oneOf']}
        self.assertEqual(command_names,branches)
        read_or_prepare={'search_memory','read_records','resolve_person','query_finance','query_table','aggregate_table','request_clarification','preview_delete','prepare_changes'}
        self.assertEqual(set(json.loads((ROOT/'model-tool-call.schema.json').read_text())['properties']['toolName']['enum'])-read_or_prepare,command_names)
        result=json.loads((ROOT/'tool-result.schema.json').read_text())
        kinds={branch.get('properties',{}).get('kind',{}).get('const') for branch in result['properties']['data']['oneOf']}
        required={'search','note','records','person_candidates','finance_summary','table_page','table_aggregate','clarification','delete_preview','deletion','prepared_plan','undo_preview','undo_applied','restored_records','money_event','obligation','settlement','money_correction','calendar_event','table_created','table_rows_changed','table_schema_changed','person','project','link','task','prepared'}
        self.assertTrue(required.issubset(kinds))
        result_kind={'search_memory':'search','create_note':'note','update_note':'note','read_records':'records','resolve_person':'person_candidates','query_finance':'finance_summary','query_table':'table_page','aggregate_table':'table_aggregate','request_clarification':'clarification','create_person':'person','update_person':'person','create_project':'project','update_project':'project','link_records':'link','unlink_records':'link','preview_delete':'delete_preview','commit_delete':'deletion','create_task':'task','update_task':'task','create_calendar_event':'calendar_event','update_calendar_event':'calendar_event','record_receipt':'money_event','record_obligation':'obligation','record_settlement':'settlement','correct_money_event':'money_correction','create_table':'table_created','add_table_rows':'table_rows_changed','update_table_rows':'table_rows_changed','change_table_schema':'table_schema_changed','restore_records':'restored_records','undo_change':'undo_preview','prepare_changes':'prepared_plan'}
        self.assertEqual(names,set(result_kind))
        self.assertTrue(set(result_kind.values()).issubset(kinds))

    def test_every_command_schema_accepts_one_app_authored_golden_envelope(self):
        from canonical import command_digest
        from test_contracts import check,read_fixture
        proposals={x['toolName']:x['arguments'] for x in json.loads((ROOT/'fixtures/all-proposals.json').read_text())}
        U=lambda n:f'00000000-0000-4000-8000-{n:012d}'
        commands={}
        def add(tool,args,expected=(),confirmation=None):commands[tool]=(args,list(expected),confirmation)
        a=dict(proposals['create_note']);a.update(noteId=U(100),noteRevisionId=U(101));add('create_note',a)
        a=dict(proposals['update_note']);a.update(newRevisionId=U(102));add('update_note',a,[{'id':U(1),'version':1}])
        a=dict(proposals['create_person'],personId=U(103),aliases=[]);add('create_person',a)
        a=dict(proposals['update_person']);add('update_person',a,[{'id':a['personId'],'version':1}])
        a=dict(proposals['create_project'],projectId=U(104),status='ACTIVE');add('create_project',a)
        a=dict(proposals['update_project']);add('update_project',a,[{'id':a['projectId'],'version':1}])
        a=dict(proposals['link_records'],linkId=U(105));add('link_records',a,[{'id':a['from']['id'],'version':1},{'id':a['to']['id'],'version':1}])
        a=dict(proposals['unlink_records']);add('unlink_records',a,[{'id':a['linkId'],'version':1}])
        preview=read_fixture('delete-case.json')['preview']['data']
        add('commit_delete',{'previewId':preview['previewId'],'planDigest':preview['planDigest']},[{'id':x['id'],'version':x['version']} for x in preview['targets']+preview['dependentLinks']],U(106))
        a=dict(proposals['create_task'],taskId=U(107),due=None,remind=False,links=[]);add('create_task',a)
        a=dict(proposals['update_task']);add('update_task',a,[{'id':a['taskId'],'version':1}])
        a=dict(proposals['record_receipt'],eventId=U(108));add('record_receipt',a,[{'id':a['personId'],'version':1}])
        a=dict(proposals['record_obligation'],obligationId=U(109));add('record_obligation',a,[{'id':a['personId'],'version':1}])
        a=dict(proposals['record_settlement'],settlementId=U(110),expectedRemainingMinorUnits=100);add('record_settlement',a,[{'id':a['obligationId'],'version':1}],U(111))
        a=dict(proposals['correct_money_event'],correctionEventId=U(112));add('correct_money_event',a,[{'id':a['targetEventId'],'version':1}],U(113))
        a=dict(proposals['create_table'],tableId=U(114),columns=[dict(proposals['create_table']['columns'][0],columnId=U(115))]);add('create_table',a)
        a=dict(proposals['add_table_rows'],rowIds=[U(116)]);add('add_table_rows',a,[{'id':a['tableId'],'version':a['expectedSchemaVersion']}])
        a=dict(proposals['update_table_rows']);add('update_table_rows',a,[{'id':a['tableId'],'version':a['expectedSchemaVersion']},{'id':a['rows'][0]['rowId'],'version':a['rows'][0]['expectedVersion']}])
        a=dict(proposals['change_table_schema']);add('change_table_schema',a,[{'id':a['tableId'],'version':a['expectedSchemaVersion']}],U(117))
        a=dict(proposals['restore_records']);add('restore_records',a,[{'id':x['recordId'],'version':x['expectedTrashVersion']} for x in a['records']])
        a=dict(proposals['undo_change']);add('undo_change',a,[{'id':U(118),'version':1}])
        a=dict(proposals['create_calendar_event'],eventId=U(119));add('create_calendar_event',a)
        a=dict(proposals['update_calendar_event']);add('update_calendar_event',a,[{'id':a['eventId'],'version':1}])
        self.assertEqual(command_names:=set(json.loads((ROOT/'command-envelope.schema.json').read_text())['properties']['toolName']['enum']),set(commands))
        for i,(tool,(args,expected,confirmation)) in enumerate(commands.items(),200):
            cmd={'protocolVersion':1,'commandId':U(i),'turnId':U(2),'stepId':U(3),'scopeId':U(4),'actorId':U(5),'toolName':tool,'toolVersion':1,'arguments':args,'expectedVersions':expected,'sourceRevisionId':U(6),'evidenceRefs':[U(7)],'payloadDigest':'','permissionSnapshotVersion':1,'confirmationId':confirmation}
            cmd['payloadDigest']=command_digest(cmd)
            check('command-envelope',cmd)

if __name__=='__main__':unittest.main()
