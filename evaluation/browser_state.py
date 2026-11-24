import os,json,sys,hashlib,datetime
from pathlib import Path
import psycopg
db=psycopg.connect(os.environ['DB_URL'].replace('jdbc:',''),user=os.environ['DB_USER'],password=os.environ['DB_PASSWORD'])
assert db.execute('select current_database()').fetchone()[0]=='java_ai_v2'
stage=sys.argv[1];out=Path(__file__).resolve().parents[1]/'docs/evidence';out.mkdir(exist_ok=True)
result={'stage':stage,'recordedAt':datetime.datetime.now(datetime.timezone.utc).isoformat()}
for table,key in [('flight_orders','id'),('operation_plans','id'),('operation_records','id'),('knowledge_documents','id'),('conversations','id'),('chat_messages','id')]:
 rows=[r[0] for r in db.execute(f'select row_to_json(t) from (select * from {table} order by {key})t')]
 result[table]={'count':len(rows),'sha256':hashlib.sha256(json.dumps(rows,ensure_ascii=False,sort_keys=True,default=str).encode()).hexdigest()}
 if table in ['flight_orders','operation_plans','operation_records']:result[table]['rows']=rows
(out/('browser-'+stage+'.json')).write_text(json.dumps(result,ensure_ascii=False,indent=2,default=str),encoding='utf-8')
if stage=='after-restart':
 before=json.loads((out/'browser-before-restart.json').read_text(encoding='utf-8'))
 equal={k:before[k]['sha256']==result[k]['sha256'] for k in result if isinstance(result[k],dict) and 'sha256' in result[k]}
 (out/'restart-persistence.json').write_text(json.dumps({'equal':all(equal.values()),'tables':equal},indent=2),encoding='utf-8')
 print('Restart persisted all recorded business data:',all(equal.values()))
else:print('Recorded actual SQL browser evidence:',stage)
