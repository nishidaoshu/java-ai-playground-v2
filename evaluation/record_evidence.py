"""Record local build, database preservation and actual provider usage without credentials."""
import os,json,hashlib,datetime,sys
from pathlib import Path
import psycopg
root=Path(__file__).resolve().parents[1];runtime=root.parent/'.java-ai-runtime-v2';out=root/'docs/evidence';out.mkdir(parents=True,exist_ok=True)
db=psycopg.connect(os.environ['DB_URL'].replace('jdbc:',''),user=os.environ['DB_USER'],password=os.environ['DB_PASSWORD'])
assert db.execute('select current_database()').fetchone()[0]=='java_ai_v2'
def save(name,data):
 (out/name).write_text(json.dumps(data,ensure_ascii=False,indent=2,default=str),encoding='utf-8')
def rows(sql,args=()):return [r[0] for r in db.execute(sql,args).fetchall()]
mode=sys.argv[1]
if mode=='before-full':
 save('v2-seeds-before-full.json',rows("select row_to_json(t) from (select * from flight_orders where id=any(%s) order by id)t",(['A1001','A1002','A1003','A1004','B1001','B1002','B1003','B1004'],)))
elif mode=='provenance':
 files=[]
 for base in ['src/main/java','src/main/resources','src/main/frontend','src/test/java']:
  for p in sorted((root/base).rglob('*')):
   if p.is_file() and 'generated' not in p.parts:files.append((str(p.relative_to(root)).replace('\\','/'),hashlib.sha256(p.read_bytes()).hexdigest()))
 for f in ['pom.xml','compose.yaml','package.json','package-lock.json']:
  p=root/f
  if p.exists():files.append((f,hashlib.sha256(p.read_bytes()).hexdigest()))
 jar=runtime/'application.jar';built=root/'target/java-ai-playground-v2-2.0-SNAPSHOT.jar'
 save('build-provenance.json',{'recordedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'runtimeJarSha256':hashlib.sha256(jar.read_bytes()).hexdigest(),'buildJarSha256':hashlib.sha256(built.read_bytes()).hexdigest(),'sourceTreeSha256':hashlib.sha256(json.dumps(files,ensure_ascii=False).encode()).hexdigest(),'sourceFiles':files,'databaseVersion':db.execute('select version()').fetchone()[0],'pgvectorVersion':db.execute("select extversion from pg_extension where extname='vector'").fetchone()[0]})
elif mode=='after-full':
 after=rows("select row_to_json(t) from (select * from flight_orders where id=any(%s) order by id)t",(['A1001','A1002','A1003','A1004','B1001','B1002','B1003','B1004'],))
 before=json.loads((out/'v2-seeds-before-full.json').read_text(encoding='utf-8'))
 save('v2-seeds-preservation.json',{'equal':before==after,'before':before,'after':after})
elif mode=='metrics':
 reports={}
 for p in sorted((root/'evaluation/results').glob('*.json')):
  d=json.loads(p.read_text(encoding='utf-8'))
  if 'results' not in d:continue
  ids=list({str(rid) for c in d['results'] for rid in c.get('requestIds',[]) if rid})
  metrics=db.execute("select count(*),coalesce(sum((data->'tokenUsage'->>'inputTokens')::bigint),0),coalesce(sum((data->'tokenUsage'->>'outputTokens')::bigint),0) from trace_events where kind='MODEL' and request_id=any(%s::uuid[])",(ids,)).fetchone()
  timing=db.execute("select count(*),percentile_cont(0.5) within group(order by first_token_ms),percentile_cont(0.5) within group(order by total_ms) from request_traces where path='/api/chat' and id=any(%s::uuid[])",(ids,)).fetchone()
  reports[p.name]={'executed':d['executed'],'passed':d['passed'],'failed':d['failed'],'buildSha256':d.get('buildSha256'),'providerCalls':metrics[0],'providerInputTokens':metrics[1],'providerOutputTokens':metrics[2],'chatCount':timing[0],'medianFirstMs':timing[1],'medianTotalMs':timing[2],'failures':[{'id':c['id'],'failure':c.get('failure'),'requestIds':c['requestIds']} for c in d['results'] if c['status']=='FAIL']}
 overall=db.execute("select count(*),coalesce(sum((data->'tokenUsage'->>'inputTokens')::bigint),0),coalesce(sum((data->'tokenUsage'->>'outputTokens')::bigint),0) from trace_events where kind='MODEL'").fetchone()
 save('usage-metrics.json',{'reports':reports,'databaseTotalProviderCalls':overall[0],'databaseTotalInputTokens':overall[1],'databaseTotalOutputTokens':overall[2],'currencyCost':None,'currencyNote':'服务商实际返回usage；未读账单，未估算或声称货币扣款。数据库总量包含页面联调与所有评测，报告不能重复累加副本。'})
print('Recorded evidence:',mode)
