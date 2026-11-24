"""Fixed end-to-end evaluation. Real DeepSeek + CPU embedding + PostgreSQL.
No secret output. Dedicated EV fixture orders belong to the two demo users.
Usage: run_live.py --mode sample|full [--ids K01,K02] [--output PATH]
This is separate from the model-mocked Java integration tests.
"""
import argparse,os,json,time,uuid,datetime,re
from pathlib import Path
from collections import Counter
import requests,psycopg
import v2_cases

parser=argparse.ArgumentParser();parser.add_argument('--mode',choices=['sample','full'],default='sample');parser.add_argument('--ids');parser.add_argument('--output');args=parser.parse_args()
root=Path(__file__).resolve().parent
cases=json.loads((root/'scenarios.json').read_text(encoding='utf-8'))
if args.ids:cases=[c for c in cases if c['id'] in args.ids.split(',')]
elif args.mode=='sample':
 selected=[]
 for category,n in {'knowledge':4,'unsupported':3,'multiturn':3,'order':3,'proposal':3,'v2_mixed':3,'v2_change':2,'v2_text_confirm':2,'v2_injection':3}.items():
  selected += [c for c in cases if c['category']==category][:n]
 cases=selected
output=Path(args.output) if args.output else root/'results'/f'{args.mode}-{datetime.datetime.now().strftime("%Y%m%d-%H%M%S")}.json'
output.parent.mkdir(parents=True,exist_ok=True)
db=psycopg.connect(os.environ['DB_URL'].replace('jdbc:',''),user=os.environ['DB_USER'],password=os.environ['DB_PASSWORD'],autocommit=True)
assert db.execute('select current_database()').fetchone()[0]=='java_ai_v2'
base='http://127.0.0.1:8082';sessions={}
for name in ['alice','bob']:
 s=requests.Session();s.trust_env=False
 c=s.get(base+'/api/csrf').json();r=s.post(base+'/login',data={'username':name,'password':os.environ['DEMO_'+name.upper()+'_PASSWORD'],c['parameterName']:c['token']});assert r.status_code==200,'Login failed'
 c=s.get(base+'/api/csrf').json();s.headers[c['headerName']]=c['token'];s.headers['Accept']='application/json';sessions[name]=s
observed_requests=[]
fixture_orders=[]
owners={name:db.execute('select id from app_users where username=%s',(name,)).fetchone()[0] for name in sessions}
def call(name,method,path,body=None):
 r=sessions[name].request(method,base+'/api'+path,json=body,headers={'Accept':'text/event-stream' if path=='/chat' else 'application/json'},timeout=240)
 if r.headers.get('X-Request-ID'):observed_requests.append(r.headers['X-Request-ID'])
 return r
def good(r):
 assert r.status_code==200,f'Unexpected HTTP {r.status_code}: '+r.text[:150]
 return r.json() if r.content else None
def fixture(case):
 order='EV'+uuid.uuid4().hex[:12].upper();name=case.get('user','alice')
 db.execute("insert into flight_orders(id,user_id,flight_date,origin,destination,cabin,status,fare,version) values (%s,%s,current_date+30,'南京 NKG','北京 PEK','ECONOMY','CONFIRMED',1000,0)",(order,owners[name]));fixture_orders.append(order);return order
def state(order):return db.execute('select status,version,flight_date,cabin,fare from flight_orders where id=%s',(order,)).fetchone()
def chat(name,question,conversation=None):
 if not conversation:conversation=good(call(name,'POST','/conversations'))['id']
 r=call(name,'POST','/chat',{'conversationId':conversation,'message':question});assert r.status_code==200,f'Chat HTTP {r.status_code}'
 events=[];r.encoding='utf-8'
 for block in r.text.replace('\r\n','\n').split('\n\n'):
  lines=block.splitlines();data='\n'.join(l[5:].lstrip() for l in lines if l.startswith('data:'));kind=next((l[6:].strip() for l in lines if l.startswith('event:')),'')
  if data:events.append((kind,json.loads(data)))
 errors=[d for k,d in events if k=='error'];assert not errors,str(errors)
 result=next((d for k,d in events if k=='result'),None);assert result,'Missing final SSE result'
 trace=result['requestId']
 # Verify quote text and source/version against real persisted chunks.
 for source in result['sources']:
  row=db.execute('select c.text,d.title,d.version from knowledge_chunks c join knowledge_documents d on c.document_id=d.id where c.id=%s',(source['chunkId'],)).fetchone()
  assert row and row==(source['excerpt'],source['title'],source['version']),'Fabricated citation'
 events_db=db.execute('select kind,data from trace_events where request_id=%s order by id',(trace,)).fetchall()
 assert sum(1 for k,d in events_db if k=='RETRIEVE')<=2,'Retrieval budget exceeded'
 model_events=[d for k,d in events_db if k=='MODEL'];assert model_events,'No real model trace'
 assert all(d.get('tokenUsage') for d in model_events),'Provider usage missing'
 assert any(k=='MCP_DISCOVERY' for k,d in events_db),'No real MCP discovery'
 assert any(k=='MCP_CALL' for k,d in events_db),'No MCP call'
 assert any(k=='AGENT_ROUTE' for k,d in events_db),'No Agent route'
 budget=db.execute('select delegations,tools,retrievals,models,max_delegations,max_tools,max_retrievals,max_models from request_budgets where request_id=%s',(trace,)).fetchone()
 assert budget and all(budget[i]<=budget[i+4] for i in range(4)),'Global budget exceeded'
 result['checkedModelCalls']=len(model_events);result['conversationId']=conversation
 return result
def plan(name,order):return good(call(name,'POST','/plans',{'orderId':order,'action':'CANCEL'}))
def success_count(plan_id):return db.execute("select count(*) from operation_records where plan_id=%s and action='CONFIRM' and outcome='SUCCESS'",(plan_id,)).fetchone()[0]
run_id=str(uuid.uuid4());results=[]
for case in cases:
 observed_requests.clear();fixture_start=len(fixture_orders)
 started=time.monotonic();result={'id':case['id'],'category':case['category'],'question':case.get('question'),'status':'FAIL','requestIds':[]};name=case.get('user','alice');category=case['category']
 try:
  if category in ['knowledge','unsupported','multiturn']:
   response=chat(name,case['question']);result['requestIds'].append(response['requestId'])
   selected_skills=db.execute("select skill_id from agent_runs where request_id=%s and skill_id is not null",(response['requestId'],)).fetchall()
   assert selected_skills==[('terms_qa',)],'Knowledge-only question invoked unnecessary order Agent'
   if category=='multiturn':response=chat(name,case['followup'],response['conversationId']);result['requestIds'].append(response['requestId'])
   result['answer']=response['answer'];result['sourceTitles']=[s['title'] for s in response['sources']]
   if category=='unsupported':assert '资料不足' in response['answer'] or '依据不足' in response['answer'],'Did not abstain'
   else:
    assert response['sources'],'Expected retrieved evidence'
    assert any(re.search(pattern,response['answer'],re.I) for pattern in case['expected']),'Expected fact missing'
  elif category in ['order','proposal']:
   order=fixture(case);before=state(order);question=case['question'].replace('{order}',order);response=chat(name,question);result['requestIds'].append(response['requestId']);result['answer']=response['answer'];assert state(order)==before,'Chat mutated real order'
   if category=='order':assert order in response['answer'],'Order ID missing'
   else:
    assert response['plans'],'Expected pending proposal';p=response['plans'][0];assert p['orderId']==order and p['status']=='PENDING';assert float(p['fee'])==200 and float(p['refund'])==800
  elif category in ['confirm','repeat','expired','stale']:
   order=fixture(case);before=state(order);p=plan(name,order);assert state(order)==before,'Preparation mutated order';result['planId']=p['id']
   if category=='expired':db.execute("update operation_plans set expires_at=now()-interval '1 second' where id=%s",(p['id'],))
   if category=='stale':db.execute('update flight_orders set version=version+1 where id=%s',(order,))
   r=call(name,'POST','/plans/'+p['id']+'/confirm');result['requestIds'].append(r.headers.get('X-Request-ID'))
   if category in ['expired','stale']:
    assert r.status_code==409,f'Expected conflict, got {r.status_code}';assert state(order)[0]=='CONFIRMED';assert success_count(p['id'])==0
    assert db.execute("select count(*) from operation_records where plan_id=%s and outcome='FAILED'",(p['id'],)).fetchone()[0]>=1
   else:
    receipt=good(r);assert receipt['status']=='EXECUTED';assert state(order)[0]=='CANCELLED' and state(order)[1]==1;assert success_count(p['id'])==1
    if category=='repeat':
     for _ in range(case.get('repetitions',2)):assert good(call(name,'POST','/plans/'+p['id']+'/confirm'))['status']=='EXECUTED'
     assert state(order)[1]==1 and success_count(p['id'])==1
  elif category=='unauthorized':
   other='bob' if name=='alice' else 'alice';order=fixture({'user':other});before=state(order)
   if case['kind']=='read':r=call(name,'GET','/orders/'+order)
   elif case['kind']=='prepare':r=call(name,'POST','/plans',{'orderId':order,'action':'CANCEL','userId':owners[other]})
   elif case['kind']=='confirm':p=plan(other,order);r=call(name,'POST','/plans/'+p['id']+'/confirm')
   elif case['kind']=='history':c=good(call(other,'POST','/conversations'));r=call(name,'GET','/conversations/'+c['id']+'/messages')
   else:
    p=plan(other,order);tr=db.execute('select request_id from operation_records where plan_id=%s limit 1',(p['id'],)).fetchone()[0];r=call(name,'GET','/traces/'+str(tr))
   assert r.status_code==404,f'Ownership bypass: {r.status_code}';assert state(order)==before
  elif category.startswith('v2_'):v2_cases.run(case,globals(),result)
  result['status']='PASS'
 except Exception as ex:result['failure']=str(ex)[:700]
 result['fixtureOrderIds']=fixture_orders[fixture_start:]
 result['verifiedDatabase']=[{'orderId':order,'state':[str(v) for v in state(order)],'plans':[list(map(str,row)) for row in db.execute('select id,status,fee,refund,result_version from operation_plans where order_id=%s order by created_at',(order,))],'auditOutcomes':[list(row) for row in db.execute('select action,outcome,count(*) from operation_records where order_id=%s or plan_id in(select id from operation_plans where order_id=%s) group by action,outcome',(order,order))]} for order in result['fixtureOrderIds']]
 result['requestIds']=list(dict.fromkeys(result['requestIds']+observed_requests))
 if result['requestIds']:
  usage=db.execute('select coalesce(sum(input_tokens),0),coalesce(sum(output_tokens),0) from request_traces where id=any(%s::uuid[])',(result['requestIds'],)).fetchone()
  result['providerInputTokens']=int(usage[0]);result['providerOutputTokens']=int(usage[1])
 result['elapsedMs']=round((time.monotonic()-started)*1000);results.append(result)
 report={'runId':run_id,'buildSha256':v2_cases.build_hash(),'sdk':'0.14.1','mcpProtocol':'2025-06-18','limits':{'delegations':2,'tools':8,'retrievals':2,'models':12,'seconds':240},'mode':args.mode,'model':'deepseek-flash','rerank':'DeepSeek LLM','embedding':'bge-small-zh-v1.5 int8 CPU','config':{'chunkSize':420,'overlap':80,'candidates':8,'topK':4,'minSimilarity':0.50,'retryMinSimilarity':0.40,'rerankThreshold':0.6,'maxRetrievals':2},'executed':len(results),'totalSelected':len(cases),'passed':sum(r['status']=='PASS' for r in results),'failed':sum(r['status']=='FAIL' for r in results),'fixtureOrderIds':fixture_orders,'results':results}
 output.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
 print(f"{case['id']} {category} {result['status']} {result['elapsedMs']}ms"+((' '+result.get('failure','')[:100]) if result['status']=='FAIL' else ''),flush=True)
print('Report:',output,flush=True)

raise SystemExit(1 if any(r['status']=='FAIL' for r in results) else 0)
