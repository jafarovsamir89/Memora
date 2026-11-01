"""Reference validation for typed table cells; runtime storage is not implemented."""
import datetime
import re

from test_contracts import check_due


def validate_table_rows(columns, rows):
    by_id={column['columnId']:column for column in columns}
    if len(by_id)!=len(columns):raise ValueError('duplicate column ID')
    for row in rows:
        cells={cell['columnId']:cell['value'] for cell in row['cells']}
        if len(cells)!=len(row['cells']) or not set(cells).issubset(by_id):raise ValueError('duplicate or unknown column cell')
        for column_id,column in by_id.items():
            value=cells.get(column_id)
            if column['required'] and (column_id not in cells or value is None):raise ValueError('required cell is empty')
            if column_id in cells:validate_value(column,value)


def validate_value(column,value):
    kind=column['type']
    if value is None:return
    valid={
        'text':lambda v:isinstance(v,str) and len(v.encode('utf-16-le',errors='strict'))//2<=20000,
        'integer':lambda v:isinstance(v,int) and not isinstance(v,bool) and -(2**63)<=v<2**63,
        'decimal':lambda v:isinstance(v,str) and re.fullmatch(r'-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?',v) is not None,
        'money':lambda v:isinstance(v,dict) and set(v)=={'minorUnits','currency','currencyScaleVersion'} and isinstance(v['minorUnits'],int) and not isinstance(v['minorUnits'],bool) and -(2**63)<=v['minorUnits']<2**63 and re.fullmatch(r'[A-Z]{3}',v['currency']) is not None and isinstance(v['currencyScaleVersion'],int) and v['currencyScaleVersion']>0,
        'boolean':lambda v:isinstance(v,bool),
        'localDate':lambda v:isinstance(v,str) and _is_date(v),
        'zonedDateTime':lambda v:isinstance(v,dict) and set(v)=={'precision','localDateTime','zoneId'} and v['precision']=='minute' and _valid_zoned(v),
        'enum':lambda v:isinstance(v,str) and v in column.get('enumValues',[]),
        'reference':lambda v:isinstance(v,dict) and set(v)=={'type','id'} and v['type']==column.get('referenceType') and isinstance(v['id'],str) and re.fullmatch(r'[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}',v['id']) is not None,
    }.get(kind)
    if valid is None or not valid(value):raise ValueError('cell value does not match declared column type')


def _is_date(value):
    if not isinstance(value,str) or re.fullmatch(r'[0-9]{4}-[0-9]{2}-[0-9]{2}',value) is None:return False
    try:datetime.date.fromisoformat(value);return True
    except (TypeError,ValueError):return False


def _valid_zoned(value):
    try:check_due({'precision':'minute','localDateTime':value['localDateTime'],'zoneId':value['zoneId']});return True
    except (KeyError,ValueError):return False
