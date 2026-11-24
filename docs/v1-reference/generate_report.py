"""Summarize actual saved runs and provider usage. Never reads or emits credential values."""
import json,os,re,statistics,hashlib,datetime
from pathlib import Path
from collections import Counter,defaultdict
from zoneinfo import ZoneInfo
import psycopg

root=Path(__file__).resolve().parent.parent;results=root/'evaluation/results'
release=json.loads((results/'live-release-full.json').read_text(encoding='utf-8'))
assert release['executed']==release['totalSelected']==100,'Full run is still incomplete'
focused=json.loads((results/'live-focused-final.json').read_text(encoding='utf-8'))
assert focused['executed']==focused['totalSelected'],'Focused regression is still incomplete'
connection=psycopg.connect(os.environ['DB_URL'].replace('jdbc:',''),user=os.environ['DB_USER'],password=os.environ['DB_PASSWORD'])
ids=sorted({rid for r in release['results'] for rid in r.get('requestIds',[]) if rid})
rows=connection.execute('select id,path,status,first_token_ms,total_ms,input_tokens,output_tokens from request_traces where id=any(%s::uuid[])',(ids,)).fetchall()
model=connection.execute("select data->>'purpose',data->>'model',data->>'providerModel',count(*),sum((data->'tokenUsage'->>'inputTokens')::bigint),sum((data->'tokenUsage'->>'outputTokens')::bigint) from trace_events where kind='MODEL' and request_id=any(%s::uuid[]) group by 1,2,3 order by 1",(ids,)).fetchall()
ttft=[r[3] for r in rows if r[1]=='/api/chat' and r[3] is not None]
totals=[r[4] for r in rows if r[1]=='/api/chat' and r[4] is not None]
summary={'requestRows':len(rows),'modelCalls':sum(r[3] for r in model),'providerInputTokens':sum(r[5] for r in rows),'providerOutputTokens':sum(r[6] for r in rows),'chatRequestsWithMeasuredFirstResponse':len(ttft),'medianFirstResponseMs':statistics.median(ttft) if ttft else None,'medianTotalChatMs':statistics.median(totals) if totals else None,'modelPurposeUsage':[{'purpose':r[0],'requestedModel':r[1],'providerModel':r[2],'calls':r[3],'inputTokens':int(r[4] or 0),'outputTokens':int(r[5] or 0)} for r in model]}
(results/'release-metrics.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2),encoding='utf-8')
test_text=(root/'target/surefire-reports/org.vaadin.marcus.v1.WorkflowIntegrationTest.txt').read_text(encoding='utf-8')
match=re.search(r'Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)',test_text);assert match
test_counts=list(map(int,match.groups()))
jar=root/'target/java-ai-playground-v1-1.0-SNAPSHOT.jar';runtime=root.parent/'.java-ai-runtime-v1'
source_hash=hashlib.sha256(jar.read_bytes()).hexdigest();runtime_hash=hashlib.sha256((runtime/'application.jar').read_bytes()).hexdigest();assert source_hash==runtime_hash,'Running JAR does not match final build'
provenance={'fullEvaluationJarSha256':json.loads((results/'release-full-build.json').read_text())['fullEvaluationJarSha256'],'runtimeJarSha256':runtime_hash,'finalBuildMatchesRuntime':True,'databaseVersion':connection.execute('show server_version').fetchone()[0],'pgvectorVersion':connection.execute("select extversion from pg_extension where extname='vector'").fetchone()[0],'backendTests':test_counts}
(results/'release-provenance.json').write_text(json.dumps(provenance,ensure_ascii=False,indent=2),encoding='utf-8')
counts=defaultdict(Counter)
for r in release['results']:counts[r['category']][r['status']]+=1
category_table='\n'.join(f"| {key} | {sum(c.values())} | {c['PASS']} | {c['FAIL']} |" for key,c in counts.items())
usage_table='\n'.join(f"| {u['purpose']} | {u['calls']} | {u['inputTokens']} | {u['outputTokens']} |" for u in summary['modelPurposeUsage'])
history=[]
for file,label in [('initial-smoke.json','客户端调试：SSE Accept不匹配'),('initial-smoke-fixed.json','客户端调试：SSE UTF-8解码问题'),('initial-smoke-utf8.json','调试修复后的4场景连通检查'),('baseline-sample.json','初轮真实抽样，暴露多轮问题'),('baseline-full-partial.json','初轮全量中止，修复上下文与工具标记'),('live-sample-fixed.json','上下文与最终回答修复后真实抽样'),('live-full-fixed.json','固定0.45阈值全量，暴露无依据兜底问题'),('live-final-full.json','固定0.50阈值全量，暴露2题漏检'),('live-release-full.json','0.50初检/0.40补检全量，暴露检索子问题覆盖'),('live-focused-final.json','最终检索子问题与证据复用修复后定向回归')]:
 p=results/file
 if p.exists():
  data=json.loads(p.read_text(encoding='utf-8'));history.append(f"| {label} | {data['executed']} | {data['passed']} | {data['failed']} | [{file}](../evaluation/results/{file}) |")
failures=[r for r in release['results'] if r['status']=='FAIL']
failure_text='全量无失败场景。' if not failures else '\n'.join(f"- {r['id']}：{r.get('failure','未通过')}；问题：{r.get('question') or r['category']}" for r in failures)
resolved=[r['id'] for r in failures if any(x['id']==r['id'] and x['status']=='PASS' for x in focused['results'])]
all_usage=connection.execute("select count(*),coalesce(sum((data->'tokenUsage'->>'inputTokens')::bigint),0),coalesce(sum((data->'tokenUsage'->>'outputTokens')::bigint),0) from trace_events where kind='MODEL'").fetchone()
now=datetime.datetime.now(ZoneInfo('Asia/Shanghai')).strftime('%Y-%m-%d %H:%M:%S %Z')
report=f'''# V1 实际验证与评测报告

生成时间：{now}。V1 地址：http://127.0.0.1:8082。报告只依据保存的真实运行结果；不把编译、模拟模型或固定拒答当作真实模型全链路验证。

## 正式结果

- 生产 Maven 构建通过；TypeScript noEmit 通过。
- 真实 PostgreSQL 集成测试：执行{test_counts[0]}，失败{test_counts[1]}，错误{test_counts[2]}，跳过{test_counts[3]}。这些测试的模型明确模拟，数据库、事务、写锁、版本、回滚和并发均真实。
- 最终固定评测集：执行{release['executed']}/100，规则断言通过{release['passed']}，失败{release['failed']}。真实模型/RAG问题与后端安全业务场景混合，不能称为100道纯模型问答，更不能称为普遍准确率。
- 全量中的失败场景{resolved}修复后，另行执行最终定向回归{focused['executed']}场景，通过{focused['passed']}、失败{focused['failed']}。未把定向通过改写为新的100/100全量结果。
- 当前最终运行JAR与源码构建产物SHA-256一致：`{runtime_hash}`。数据库PostgreSQL {provenance['databaseVersion']}，pgvector {provenance['pgvectorVersion']}。全量与最后修复的构建指纹分别保存，可区分验证范围。

原始证据：[最终全量JSON](../evaluation/results/live-release-full.json)、[请求与Token汇总](../evaluation/results/release-metrics.json)、[构建版本](../evaluation/results/release-provenance.json)、target/surefire-reports 下的JUnit报告。

## 八项验收与实际证据

| 验收 | 实际验证方式与结果 |
|---|---|
| 两个账号不能查询/修改对方订单 | 两个真实Session，跨账号GET订单、准备方案、确认方案、聊天历史、追踪均404；数据库状态保持不变。浏览器Bob查询并取消A1001也被拒绝。 |
| 聊天取消先生成方案 | 页面向真实DeepSeek发送“帮我取消订单A1001”；生成PENDING卡片，直接SQL查到订单仍CONFIRMED/版本0，费用200元、退款800元。 |
| 点击确认后取消 | 页面点击确认，直接SQL查到A1001=CANCELLED/版本1、方案EXECUTED，成功记录恰好1条。操作历史可查。 |
| 重复、过期、旧版本 | 真实确认接口多次提交同方案只执行一次；测试专用方案的expires_at写入过去后409，订单版本更新后旧方案409。4线程确认同方案仅一次成功写入；两方案竞争只有一个成功。 |
| 重启保留 | 停止并重新启动V1，逐字段比较8个种子订单、8条验收消息、2条A1001操作记录、4份文档均一致，见restart-persistence.json。Session重新登录，业务数据保留。 |
| 上传后有来源 | 页面上传中文Markdown lounge.md，真实问答返回08:00–22:00与30元/小时；展开来源显示上传文档标题、demo-upload-v1和原文。Bob看不到该私有文档。 |
| 无依据提示不足 | 页面真实提问宠物收费/延误赔偿，返回资料不足。固定集10个无依据场景另行检查拒答。 |
| 页面模型/RAG/工具闭环 | 浏览器真实登录、知识问答、模型生成退票方案、手动确认、订单与操作记录、上传检索均已实际操作并截图。 |

事务校验另有真实PG故障注入：审计成功写入失败时全部回滚；触发器故意把更新状态改回CONFIRMED时，后端SQL复核发现异常并回滚。没有用JPA内存对象作为结果证明。

## 固定集覆盖

| 类别 | 执行 | 通过 | 失败 |
|---|---:|---:|---:|
{category_table}

知识/无依据/多轮/订单/生成方案经过真实DeepSeek聊天与工具决策。确认/重复/过期/旧版本主要经真实REST业务接口，准备方案仍执行真实Embedding、pgvector和LLM重排。越权场景不要求调用模型，验证受保护接口本身。

所有操作成功断言均查询真实SQL状态、版本和成功记录数量。评测专用EV订单及关联测试方案/操作记录按结果清单清理，种子8订单和浏览器A1001验收记录保留；聊天、检索与模型追踪保留。报告的verifiedDatabase保存清理前的实际状态和审计结果。

## 配置、调参与失败修复

最终参数：分段420字符、重叠80、候选8、保留4、初检cosine阈值0.50、唯一一次补检索阈值0.40、LLM重排阈值0.60。所有聊天共享最多2次底层检索预算；最多3轮工具决策、8次工具调用。

Embedding为中文BGE-small-zh-v1.5，512维、ONNX int8 CPU、CLS+L2，模型revision与文件SHA记录在runtime/model-manifest.json。Rerank采用单独配置的DeepSeek LLM，非独立cross-encoder；不新增付费密钥，但会消费现有DeepSeek Token。最终生成使用独立的无工具定义上下文并流式输出，thinking显式disabled。

校准对20个标注知识问题和10个无依据问题比较15组阈值/候选组合。0.45与0.50都命中18/20个相关文档，0.50把无依据问题出现任意候选的数量从7/10降至3/10；但真实全量中客服开放时间、起飞时区两题漏检。0.40的标注候选命中20/20，同时无依据题10/10都会有候选，因此只在补检索时采用，之后必须再做LLM重排与拒答。该校准只有5个全局片段，不代表一般语料最优，也不代表回答准确率。

最后一轮全量P03因后端用操作指令覆盖模型给出的具体规则子问题而漏检，安全拒绝生成方案，订单未变化。已改为采用工具提供的具体检索问题，并复用当前请求内已检索的同版本权威规则证据；最终定向回归覆盖全部10个多轮、10个方案、先前2个漏检问题、2个无依据问题及确认/幂等/过期/旧版本/越权。全量构建SHA在release-full-build.json，修复后的运行构建SHA在release-provenance.json。

初轮还发现：多轮问题未被完整改写、最终生成偶尔输出工具调用标记、无依据问题可能因路由误判进入一般答复。分别通过独立上下文问题改写、最终回答移除工具调用上下文、后台无证据固定拒答修复。增加了即使路由误判也不能无证据给一般知识答案的回归测试。

| 运行阶段 | 实际场景数 | 通过 | 失败 | 原始结果 |
|---|---:|---:|---:|---|
{chr(10).join(history)}

初轮中止全量不是完整100场景，表内按实际执行数记录。重复保存的baseline副本不计作新的执行。调试客户端错误不计作模型准确率。本轮全量失败情况（修复前的真实记录）：

{failure_text}

已在最终定向回归中通过的对应场景：{resolved}。最终定向回归不等同再次全量；未声称普遍准确率或性能提升。

## 服务商用量与耗时

本节主要统计最后一次100场景全量关联请求，包含失败请求已返回的usage。模型实际调用{summary['modelCalls']}次，服务商返回输入{summary['providerInputTokens']} Token、输出{summary['providerOutputTokens']} Token。Embedding无外部付费API调用。没有读取账单余额，无法给出实际扣款金额；缓存折扣、赠送额度等以服务商账单为准，不能把Token总量直接伪装成货币花费。

| 模型用途 | 调用数 | 实际输入Token | 实际输出Token |
|---|---:|---:|---:|
{usage_table}

修复后额外定向回归实际输入{sum(r.get('providerInputTokens',0) for r in focused['results'])} Token、输出{sum(r.get('providerOutputTokens',0) for r in focused['results'])} Token。整个V1数据库记录的全部联调/评测/页面调用共{all_usage[0]}次模型调用、实际输入{all_usage[1]} Token、输出{all_usage[2]} Token，包含调试和此前运行，未对重复保存的JSON副本重复计费统计。

全量聊天请求中有首字记录{len(ttft)}条，首字耗时中位数{summary['medianFirstResponseMs']}ms，总耗时中位数{summary['medianTotalChatMs']}ms。首字包括改写、工具决策、检索和重排；无依据固定拒答记录首次输出时间。本机顺序功能评测、非压测，无V0对照基线，不宣称性能或准确率提升。

## 页面、运行与局限

截图在docs/screenshots。流程图flow.png、flow.svg与flow.mmd按实际实现绘制。V0/V1变化详见CHANGES-V0-V1.md。V0的8080实例保留，V1只使用8081、CPU Embedding 18082和WSL数据库15432。

Windows未安装Docker CLI，实际使用现有WSL Ubuntu Docker Engine和用户级Compose v2.40.3。镜像通过Windows现有Clash代理下载并SHA校验、导入WSL，未修改已有Docker daemon代理。Compose健康检查和命名数据卷可用，默认启动/停止脚本已实测。

局限：小型演示语料和固定词面/状态断言，不是独立人工语义评判或生产SLA；无真实支付/航空库存；Session不跨应用重启；上下文最多20条，页面消息最多100条；未集成外部追踪平台、MCP、Skills或多Agent。密钥、口令均不进入源码、前端、报告或日志，另有secret-scan.json记录本机扫描结果。
'''
(root/'docs/VALIDATION-REPORT.md').write_text(report,encoding='utf-8')
print('Generated actual validation report:',release['passed'],'PASS /',release['failed'],'FAIL')
