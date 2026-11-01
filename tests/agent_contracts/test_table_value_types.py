import unittest
from table_contract import validate_table_rows

MONEY={'minorUnits':1250,'currency':'AZN','currencyScaleVersion':1}

class TypedTableCellsTest(unittest.TestCase):
    def test_types_and_required_empty_values_are_distinct(self):
        columns=[{'columnId':'money','name':'Amount','type':'money','required':True,'position':0}, {'columnId':'note','name':'Note','type':'text','required':False,'position':1}]
        validate_table_rows(columns,[{'cells':[{'columnId':'money','value':MONEY},{'columnId':'note','value':None}]}])
        for cells in ([{'columnId':'money','value':None}], [{'columnId':'money','value':1250}], [{'columnId':'money','value':{**MONEY,'minorUnits':12.5}}]):
            with self.assertRaises((ValueError,TypeError)):validate_table_rows(columns,[{'cells':cells}])

    def test_enum_decimal_reference_and_temporal_types(self):
        columns=[{'columnId':'state','name':'State','type':'enum','required':True,'position':0,'enumValues':['OPEN','DONE']},
                 {'columnId':'decimal','name':'Exact','type':'decimal','required':True,'position':1},
                 {'columnId':'person','name':'Person','type':'reference','referenceType':'person','required':True,'position':2},
                 {'columnId':'when','name':'When','type':'zonedDateTime','required':False,'position':3}]
        rows=[{'cells':[{'columnId':'state','value':'OPEN'},{'columnId':'decimal','value':'12.500'},{'columnId':'person','value':{'type':'person','id':'00000000-0000-4000-8000-000000000001'}},{'columnId':'when','value':{'precision':'minute','localDateTime':'2026-11-01T01:30','zoneId':'America/Los_Angeles'}}]}]
        with self.assertRaises(ValueError):validate_table_rows(columns,rows)
        rows[0]['cells'][-1]['value']={'precision':'minute','localDateTime':'2026-11-01T03:30','zoneId':'America/Los_Angeles'}
        validate_table_rows(columns,rows)
        rows[0]['cells'][0]['value']='UNKNOWN'
        with self.assertRaises(ValueError):validate_table_rows(columns,rows)

if __name__=='__main__':unittest.main()
