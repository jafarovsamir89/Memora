import unittest

from test_contracts import check, bind_search_result, bind_person_candidates, bind_table_query, bind_table_aggregate, bind_clarification

U1 = "00000000-0000-4000-8000-000000000001"
U2 = "00000000-0000-4000-8000-000000000002"


class RemainingReadSchemasTest(unittest.TestCase):
    def call(self, tool, args):
        return {"toolName": tool, "schemaVersion": 1, "providerCallId": "synthetic-call", "arguments": args}

    def test_query_table_is_versioned_bounded_and_projected(self):
        check("model-tool-call", self.call("query_table", {"tableId": U1, "expectedSchemaVersion": 3, "projection": [U2], "limit": 25}))
        with self.assertRaises(Exception):
            check("model-tool-call", self.call("query_table", {"tableId": U1, "expectedSchemaVersion": 3, "projection": [], "limit": 1000}))

    def test_aggregate_table_has_explicit_operation_and_column(self):
        check("model-tool-call", self.call("aggregate_table", {"tableId": U1, "expectedSchemaVersion": 1, "operation": "SUM", "columnId": U2}))
        with self.assertRaises(Exception):
            check("model-tool-call", self.call("aggregate_table", {"tableId": U1, "expectedSchemaVersion": 1, "operation": "AVERAGE", "columnId": U2}))

    def test_clarification_requires_bounded_options_and_request_fields(self):
        check("model-tool-call", self.call("request_clarification", {"reasonCode": "AMBIGUOUS_AMOUNT", "fields": ["amount"], "options": [{"optionId": "a", "label": "100 AZN"}, {"optionId": "b", "label": "100 USD"}]}))
        with self.assertRaises(Exception):
            check("model-tool-call", self.call("request_clarification", {"reasonCode": "AMBIGUOUS_AMOUNT", "fields": ["amount"], "options": [{"optionId": "a", "label": "100 AZN"}]}))

    def test_read_result_binders_reject_unrequested_or_partial_as_complete(self):
        search=self.call('search_memory',{'query':'test','types':['note'],'limit':2})
        sres={'protocolVersion':1,'callId':search['providerCallId'],'commandId':None,'status':'found','data':{'kind':'search','hits':[{'id':U1,'version':1,'type':'note','evidenceRefs':[U2]}]},'affected':[],'evidenceRefs':[],'completeness':'complete','errorCode':None,'retryable':False,'nextCursor':None}
        bind_search_result(search,sres)
        sres['data']['hits'][0]['type']='person'
        with self.assertRaises(ValueError):bind_search_result(search,sres)

        table=self.call('query_table',{'tableId':U1,'expectedSchemaVersion':2,'projection':[U2]})
        tres={'protocolVersion':1,'callId':table['providerCallId'],'commandId':None,'status':'found','data':{'kind':'table_page','tableId':U1,'schemaVersion':2,'rows':[{'rowId':U2,'version':1,'cells':[{'columnId':U2,'value':5}]}],'complete':True},'affected':[],'evidenceRefs':[],'completeness':'complete','errorCode':None,'retryable':False,'nextCursor':None}
        bind_table_query(table,tres)
        tres['data']['schemaVersion']=3
        with self.assertRaises(ValueError):bind_table_query(table,tres)

    def test_person_aggregate_and_clarification_result_binders(self):
        person=self.call('resolve_person',{'name':'Elçin'})
        pres={'protocolVersion':1,'callId':person['providerCallId'],'commandId':None,'status':'empty','data':{'kind':'person_candidates','candidates':[]},'affected':[],'evidenceRefs':[],'completeness':'complete','errorCode':None,'retryable':False,'nextCursor':None}
        bind_person_candidates(person,pres)
        aggregate=self.call('aggregate_table',{'tableId':U1,'expectedSchemaVersion':2,'operation':'COUNT_ROWS','columnId':U2})
        ares={'protocolVersion':1,'callId':aggregate['providerCallId'],'commandId':None,'status':'found','data':{'kind':'table_aggregate','tableId':U1,'schemaVersion':2,'operation':'COUNT_ROWS','columnId':U2,'groups':[]},'affected':[],'evidenceRefs':[],'completeness':'complete','errorCode':None,'retryable':False,'nextCursor':None}
        bind_table_aggregate(aggregate,ares)
        clarify=self.call('request_clarification',{'reasonCode':'AMBIGUOUS_AMOUNT','fields':['amount'],'options':[{'optionId':'a','label':'1 AZN'},{'optionId':'b','label':'2 AZN'}]})
        cres={'protocolVersion':1,'callId':clarify['providerCallId'],'commandId':None,'status':'prepared','data':{'kind':'clarification','clarificationId':U1,'requestRevisionId':U2,'reasonCode':'AMBIGUOUS_AMOUNT','fields':['amount'],'options':clarify['arguments']['options']},'affected':[],'evidenceRefs':[],'completeness':None,'errorCode':None,'retryable':False,'nextCursor':None}
        bind_clarification(clarify,cres)


if __name__ == "__main__":
    unittest.main()
