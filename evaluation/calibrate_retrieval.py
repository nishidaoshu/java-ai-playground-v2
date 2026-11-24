"""Small, labeled corpus calibration on real BGE vectors and pgvector; no model answers.
Vector candidate recall is not answer accuracy. Rerank is evaluated by the live suite.
"""
import os,json,itertools
from pathlib import Path
import requests,psycopg
root=Path(__file__).resolve().parent
cases=json.loads((root/'scenarios.json').read_text(encoding='utf-8'))
connection=psycopg.connect(os.environ['DB_URL'].replace('jdbc:',''),user=os.environ['DB_USER'],password=os.environ['DB_PASSWORD'])
session=requests.Session();session.trust_env=False
results=[]
for case in [c for c in cases if c['category'] in ['knowledge','unsupported']]:
    response=session.post('http://127.0.0.1:18083/v1/embeddings',json={'input':'为这个句子生成表示以用于检索相关文章：'+case['question']},timeout=30);response.raise_for_status()
    vector=json.dumps(response.json()['data'][0]['embedding'])
    rows=connection.execute('select d.title,1-(c.embedding <=> %s::vector) from knowledge_chunks c join knowledge_documents d on c.document_id=d.id where d.authoritative=true order by c.embedding <=> %s::vector limit 8',(vector,vector)).fetchall()
    index=int(case['id'][1:]);expected=None
    if case['category']=='knowledge':
        expected='演示退票服务条款' if index in [1,2,3,4,5,13,14,20] else ('演示改签与舱位条款' if index<=12 else '演示行李与客服说明')
    results.append({'id':case['id'],'question':case['question'],'expectedDocument':expected,'rankedCandidates':[{'title':t,'cosine':s} for t,s in rows]})
grid=[]
for threshold,k in itertools.product([0.35,0.40,0.45,0.50,0.55],[2,4,8]):
    hits=sum(any(r['title']==case['expectedDocument'] for r in case['rankedCandidates'][:k] if r['cosine']>=threshold) for case in results if case['expectedDocument'])
    negatives=sum(bool([r for r in case['rankedCandidates'][:k] if r['cosine']>=threshold]) for case in results if not case['expectedDocument'])
    grid.append({'threshold':threshold,'candidateLimit':k,'relevantDocumentHits':hits,'labeledKnowledgeQuestions':20,'unsupportedQuestionsWithAnyVectorCandidate':negatives,'unsupportedQuestionCount':10})
report={'model':'bge-small-zh-v1.5 int8 CPU','scope':'20 labeled questions + 10 unsupported questions; global five chunks only; no LLM rerank in this calibration','grid':grid,'queries':results,'warning':'Candidate presence on unsupported questions requires rerank/abstention, not evidence of a correct answer. Small corpus does not establish generally optimal hyperparameters.'}
(root/'results').mkdir(exist_ok=True)
(root/'results/retrieval-calibration.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
for row in grid:print(json.dumps(row))
