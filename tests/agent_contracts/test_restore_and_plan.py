import unittest

from test_contracts import check

U="00000000-0000-4000-8000-000000000021"
V="00000000-0000-4000-8000-000000000022"

def call(tool,args):return {"toolName":tool,"schemaVersion":1,"providerCallId":"synthetic-plan","arguments":args}

class RestoreAndPlanTest(unittest.TestCase):
    def test_restore_binds_exact_trashed_versions(self):
        check('model-tool-call',call('restore_records',{'records':[{'recordId':U,'expectedTrashVersion':3}]}))

    def test_undo_requires_known_change_and_reason(self):
        check('model-tool-call',call('undo_change',{'changeSetId':U,'reason':'User requested undo'}))

    def test_prepare_plan_is_typed_bounded_and_acyclic(self):
        args={'operations':[{'operationId':'one','toolName':'create_note','arguments':{'title':'Xatırlatma','body':'Yoxla','links':[]},'dependsOn':[]},{'operationId':'two','toolName':'create_task','arguments':{'title':'Yoxla'},'dependsOn':['one']}], 'atomicGroups':[['one','two']]}
        check('model-tool-call',call('prepare_changes',args))
        args['operations'][0]['dependsOn']=['two']
        with self.assertRaises(ValueError):check('model-tool-call',call('prepare_changes',args))

if __name__=='__main__':unittest.main()
