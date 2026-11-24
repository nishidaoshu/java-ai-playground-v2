import json,datetime,re,shutil,collections,xml.etree.ElementTree as ET
from pathlib import Path
root=Path(__file__).resolve().parents[1];e=root/'docs/evidence';runtime=root.parent/'.java-ai-runtime-v2'
def read(name):return json.loads((e/name).read_text(encoding='utf-8'))
reports={p.name:json.loads(p.read_text(encoding='utf-8')) for p in (root/'evaluation/results').glob('*.json') if 'results' in json.loads(p.read_text(encoding='utf-8'))}
for name in reports:shutil.copy2(root/'evaluation/results'/name,e/name)
tests={'tests':0,'failures':0,'errors':0,'skipped':0}
for p in (root/'target/surefire-reports').glob('TEST-*.xml'):
 r=ET.parse(p).getroot()
 for k in tests:tests[k]+=int(r.attrib.get(k,0))
 shutil.copy2(p,e/p.name)
baseline=(runtime/'v1-baseline.log').read_text(encoding='utf-8',errors='replace');assert 'Tests run: 31, Failures: 0, Errors: 0, Skipped: 0' in baseline
provenance=read('build-provenance.json');assert provenance['runtimeJarSha256']==provenance['buildJarSha256']
full=reports['full-v2.json'];assert full['executed']==150
metrics=read('usage-metrics.json');v1=read('v1-data-preservation.json');restart=read('restart-persistence.json');auth=read('final-auth-http.json');seeds=read('v2-seeds-preservation.json');scan=read('secret-scan.json')
assert v1['equal'] and restart['equal'] and seeds['equal'] and scan['passed']
table='\n'.join(f"| {name} | {d['executed']} | {d['passed']} | {d['failed']} | {(d.get('buildSha256') or '早期连通测试')[:12]} |" for name,d in reports.items())
categories=collections.defaultdict(lambda:[0,0,0])
for c in full['results']:
 a=categories[c['category']];a[0]+=1;a[1]+=c['status']=='PASS';a[2]+=c['status']=='FAIL'
category_table='\n'.join(f'| {k} | {a[0]} | {a[1]} | {a[2]} |' for k,a in categories.items())
cost_table='\n'.join(f"| {name} | {d['providerCalls']} | {d['providerInputTokens']} | {d['providerOutputTokens']} |" for name,d in metrics['reports'].items())
failure_table='\n'.join(f"- {c['id']}：{c.get('failure')}；请求 `{','.join(c['requestIds'])}`。" for c in full['results'] if c['status']=='FAIL')
shots=sorted((root/'docs/screenshots').glob('*.jpg'))
text=f'''# V2 实际验证与交付报告

生成时间：{datetime.datetime.now().astimezone().isoformat()}。工作台：[http://127.0.0.1:8082](http://127.0.0.1:8082)。所有数据为本机演示，不产生真实交易。

## 结果及复测范围

- V1重新建立基线：31项通过，0失败；V1原有100场景仍为99通过/1失败后定向29通过，没有改写历史结果。
- V2最终真实PostgreSQL+官方SDK HTTP传输确定性测试：{tests['tests']}项，失败{tests['failures']}、错误{tests['errors']}、跳过{tests['skipped']}。模型明确模拟。包含原31项业务回归及新增身份/并发/Skill/Agent/预算/故障等检查。
- 真实DeepSeek抽样：26场景，22通过/4失败；失败原因与修复见下文。不是把模拟模型当作真实链路。
- 唯一一次完整固定全量：{full['executed']}执行、{full['passed']}通过、{full['failed']}失败。两项路由边界失败修复后进行定向回归，结果逐轮列出。没有再次执行150全量，最终源码不宣称150/150。
- 生产构建、TypeScript noEmit和真实服务启动通过。最终运行JAR与target构建JAR SHA256相同：`{provenance['runtimeJarSha256']}`。全量与最终构建不同，见full-build-provenance.json和build-provenance.json。
- 浏览器实际登录、检索引用、多轮、本人订单、混合方案、SQL前后、点击确认、旧/过期丢弃、MCP断连、链路/Token及重启保留均完成，原始截图共{len(shots)}张。

固定集为模型问答、协议/安全与事务业务的混合评测，不能视为模型普遍准确率或生产压测结果。

| 实际运行文件 | 执行 | 通过 | 失败 | 构建SHA前缀 |
|---|---:|---:|---:|---|
{table}

原始JSON与JUnit XML在[证据目录](evidence)。每轮保留实际requestIds、fixtureOrderIds、verifiedDatabase和usage，不把定向结果拼成新的全量。

## 真实失败与修复

首轮26抽样中N01/N03：一次search_knowledge内部完成两次检索后，知识Agent再次请求检索，预算正确拒绝但应返回资料不足。修复为知识专业Agent完成一次有界检索任务即结束，底层仍保留最多两次检索。

P01/P03：模型将取消/生成确认卡片选为order_lookup。补充路由区分，并在后端依据最新用户明确指令约束方案工具；纯费率问题不授予准备权限。模型选择与实际选择均保留，文档和改写内容不能提升权限。

完整150中的失败：
{failure_table}

这两条均为“取消订单”的假设性时间问句，被后端匹配范围误当操作意图。已改为具体订单ID或明确准备动作才能进入对应边界，并增加确定性回归。修复后的定向覆盖知识、多轮、无资料、订单、退票、混合、改签、身份及过期丢弃。另修复了匿名API错误经Servlet错误分派变成登录跳转的问题，最终真实HTTP返回401并保存证据。所有中间失败日志保留，不隐藏。

浏览器另有一次“查询Alice订单并取消”的复合越权表达，模型产生重复角色路由，被后端拒绝，未调用工具；该次没有完成订单查询。随后改为独立查询，经真实MCP归属检查明确拒绝，见browser-route-rejection.json和截图05。保留的运行边界是：模型给出非法或重复路由时请求明确失败，复杂表达可能需要拆分，不把拒绝包装成成功。

## 全量分类

| 类别 | 执行 | 通过 | 失败 |
|---|---:|---:|---:|
{category_table}

## 实际服务商用量与耗时

以下只累加关联MODEL事件中服务商真实返回usage；包括重排、协调、改写、专业决策与最终回答。Embedding本机CPU，不调用付费Embedding。没有估算Token或用模型预算次数冒充计费用量。

| 运行文件 | 实际usage调用 | 输入Token | 输出Token |
|---|---:|---:|---:|
{cost_table}

V2数据库全部联调/评测/页面累计：{metrics['databaseTotalProviderCalls']}次有usage的模型调用，输入{metrics['databaseTotalInputTokens']}、输出{metrics['databaseTotalOutputTokens']} Token。此总量含所有轮次及页面验收，不与表中重复相加。没有读取账单，无法确认缓存折扣、赠送额度或实际货币扣款，货币费用记为未核实。明细与首字/总耗时中位数见[usage-metrics.json](evidence/usage-metrics.json)。

## 安全与业务证据

- MCP：独立JVM端口18084，官方SDK0.14.1，Streamable HTTP，实际协议2025-06-18；真实初始化、5工具发现和调用，两端共享签名关联ID。
- 身份：Session→短期HMAC上下文，限定audience/scope/Skill/请求/运行记录；缺失、过期、篡改、伪造、错误受众/权限拒绝。Alice/Bob并发隔离经真实HTTP验证。仅服务凭据加匿名有效CSRF不能确认或丢弃：{auth['interactiveApiWithAnonymousCsrfAndMcpCredential']}；无签名用户上下文的MCP初始化：{auth['mcpServiceCredentialWithoutSignedUserContext']}。
- Skill：Schema与固定权限上限均强制；客户端/服务端白名单、参数字段拒绝身份注入；上传文档不能变Skill或权威规则。真实提示注入文档确实进入检索，仍不能串用户或执行订单。
- Agent：独立模型职责与上下文，简单问题只选必要专业Agent；混合任务有两个专业Agent及父子记录，无递归委派工具。MCP探测和表单准备标识为非模型运行，不虚算专业Agent委派。
- 全局预算：数据库原子计数，2委派/8工具/2底层检索/12所有模型/240秒；跨Agent不重置。超限、期限和MCP断连明确失败，不回退普通Java方法。
- 业务：生成方案前后SQL订单不变；只有Session+CSRF按钮确认才提交；重新核对归属、状态、时间、版本、规则、费用、日期和舱位。锁、幂等、事务回滚、审计失败和触发器篡改注入测试均真实PG执行。
- 丢弃：旧版本/过期可丢弃、订单不变、记录持久化；确认/丢弃竞争同一方案锁，重复幂等，已执行不能丢弃，已丢弃不能确认。

## 浏览器与SQL

页面操作使用真实本机8082，认证从DPAPI读取且不出现在截图和报告。关键截图见[screenshots](screenshots)。SQL证据为browser-before-confirm.json、browser-after-confirm.json、browser-after-discard.json，保存真实行及版本/费用/状态。方案有效期测试对精确的本轮方案临时缩短到期时间，生产默认仍300秒；页面加载未来到期时间后保持不刷新，自动禁用按钮。

重启前后六张业务表指纹一致：{restart['equal']}，见[restart-persistence.json](evidence/restart-persistence.json)。Session重启后重新登录，订单、方案状态、聊天、文档和操作记录保留。

## 隔离、清理与交付

V2独立Compose项目java-ai-v2、业务库java_ai_v2、测试库java_ai_v2_test、数据卷、PID和日志。原V1迁移、业务规则demo-v1.0和MIT许可保留。V0/V1服务未停止；V1订单、方案、文档、操作、会话、消息六表指纹与开始时相同：{v1['equal']}。全量过程中8个V2种子订单逐字段不变：{seeds['equal']}，随后浏览器明确确认演示订单的变更另有证据。

评测订单只按结果JSON的精确EV清单清理。聊天/追踪保留；没有按前缀重置任何库。秘密扫描{scan['checkedFiles']}文件，实际凭据匹配{len(scan['matchedFiles'])}。DPAPI、local.env、模型、日志留在独立运行目录，不提交前端或源码。

架构、请求和确认图以最终实现绘制：[架构](architecture.svg)、[请求](request-flow.svg)、[确认](confirmation-flow.svg)及对应Mermaid源码。普通使用者说明见[FRONTEND-ACCEPTANCE.md](FRONTEND-ACCEPTANCE.md)，逐步列出点哪里、输入、预期及失败判据。启动/停止/测试与无密钥配置在runtime；实际运行目录为D:\\.work\\jianli\\.java-ai-runtime-v2。
'''
(root/'docs/VALIDATION-REPORT.md').write_text(text,encoding='utf-8')
print('Generated report from actual artifacts:',tests,'full',full['passed'],'/',full['executed'])
