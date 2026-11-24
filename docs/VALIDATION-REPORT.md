# V2 实际验证与交付报告

生成时间：2026-11-24T20:38:25.192135+08:00。工作台：[http://127.0.0.1:8082](http://127.0.0.1:8082)。所有数据为本机演示，不产生真实交易。

## 结果及复测范围

- V1重新建立基线：31项通过，0失败；V1原有100场景仍为99通过/1失败后定向29通过，没有改写历史结果。
- V2最终真实PostgreSQL+官方SDK HTTP传输确定性测试：81项，失败0、错误0、跳过0。模型明确模拟。包含原31项业务回归及新增身份/并发/Skill/Agent/预算/故障等检查。
- 真实DeepSeek抽样：26场景，22通过/4失败；失败原因与修复见下文。不是把模拟模型当作真实链路。
- 唯一一次完整固定全量：150执行、148通过、2失败。两项路由边界失败修复后进行定向回归，结果逐轮列出。没有再次执行150全量，最终源码不宣称150/150。
- 生产构建、TypeScript noEmit和真实服务启动通过。最终运行JAR与target构建JAR SHA256相同：`b7d10f5c873c2e1380fca4df720756591c66dadcb83453bce53f797df1eafa20`。全量与最终构建不同，见full-build-provenance.json和build-provenance.json。
- 浏览器实际登录、检索引用、多轮、本人订单、混合方案、SQL前后、点击确认、旧/过期丢弃、MCP断连、链路/Token及重启保留均完成，原始截图共18张。

固定集为模型问答、协议/安全与事务业务的混合评测，不能视为模型普遍准确率或生产压测结果。

| 实际运行文件 | 执行 | 通过 | 失败 | 构建SHA前缀 |
|---|---:|---:|---:|---|
| delivery-focused-v2.json | 14 | 14 | 0 | b03ff41d8726 |
| final-route-recheck-v2.json | 2 | 2 | 0 | b7d10f5c873c |
| focused-v2.json | 10 | 10 | 0 | 7b1d7e1e5de1 |
| frozen-focused-v2.json | 5 | 5 | 0 | b6660efad768 |
| full-v2.json | 150 | 148 | 2 | b6660efad768 |
| sample-v2.json | 26 | 22 | 4 | 0afd6c6fb5dd |
| security-live-v2.json | 25 | 25 | 0 | 7b1d7e1e5de1 |
| smoke-v2.json | 5 | 5 | 0 | 早期连通测试 |

原始JSON与JUnit XML在[证据目录](evidence)。每轮保留实际requestIds、fixtureOrderIds、verifiedDatabase和usage，不把定向结果拼成新的全量。

## 真实失败与修复

首轮26抽样中N01/N03：一次search_knowledge内部完成两次检索后，知识Agent再次请求检索，预算正确拒绝但应返回资料不足。修复为知识专业Agent完成一次有界检索任务即结束，底层仍保留最多两次检索。

P01/P03：模型将取消/生成确认卡片选为order_lookup。补充路由区分，并在后端依据最新用户明确指令约束方案工具；纯费率问题不授予准备权限。模型选择与实际选择均保留，文档和改写内容不能提升权限。

完整150中的失败：
- K05：Knowledge-only question invoked unnecessary order Agent；请求 `f2e90dca-5be8-4733-b232-71063b97569a,fa6f9528-6b17-48fd-9fac-de167b2a2f68`。
- M08：Knowledge-only question invoked unnecessary order Agent；请求 `0fa1e292-6e8f-418b-a5fe-5c55f2d893cd,49ce04a6-13ec-4aff-9103-f2510febd590`。

这两条均为“取消订单”的假设性时间问句，被后端匹配范围误当操作意图。已改为具体订单ID或明确准备动作才能进入对应边界，并增加确定性回归。修复后的定向覆盖知识、多轮、无资料、订单、退票、混合、改签、身份及过期丢弃。另修复了匿名API错误经Servlet错误分派变成登录跳转的问题，最终真实HTTP返回401并保存证据。所有中间失败日志保留，不隐藏。

浏览器另有一次“查询Alice订单并取消”的复合越权表达，模型产生重复角色路由，被后端拒绝，未调用工具；该次没有完成订单查询。随后改为独立查询，经真实MCP归属检查明确拒绝，见browser-route-rejection.json和截图05。保留的运行边界是：模型给出非法或重复路由时请求明确失败，复杂表达可能需要拆分，不把拒绝包装成成功。

## 全量分类

| 类别 | 执行 | 通过 | 失败 |
|---|---:|---:|---:|
| knowledge | 20 | 19 | 1 |
| unsupported | 10 | 10 | 0 |
| multiturn | 10 | 9 | 1 |
| order | 10 | 10 | 0 |
| proposal | 10 | 10 | 0 |
| confirm | 10 | 10 | 0 |
| repeat | 10 | 10 | 0 |
| expired | 5 | 5 | 0 |
| stale | 5 | 5 | 0 |
| unauthorized | 10 | 10 | 0 |
| v2_mixed | 5 | 5 | 0 |
| v2_change | 5 | 5 | 0 |
| v2_text_confirm | 5 | 5 | 0 |
| v2_injection | 5 | 5 | 0 |
| v2_mcp_isolation | 5 | 5 | 0 |
| v2_identity | 5 | 5 | 0 |
| v2_whitelist | 5 | 5 | 0 |
| v2_budget | 5 | 5 | 0 |
| v2_discard | 5 | 5 | 0 |
| v2_trace | 5 | 5 | 0 |

## 实际服务商用量与耗时

以下只累加关联MODEL事件中服务商真实返回usage；包括重排、协调、改写、专业决策与最终回答。Embedding本机CPU，不调用付费Embedding。没有估算Token或用模型预算次数冒充计费用量。

| 运行文件 | 实际usage调用 | 输入Token | 输出Token |
|---|---:|---:|---:|
| delivery-focused-v2.json | 67 | 57859 | 7262 |
| final-route-recheck-v2.json | 12 | 8055 | 834 |
| focused-v2.json | 59 | 64285 | 8252 |
| frozen-focused-v2.json | 27 | 25458 | 3010 |
| full-v2.json | 457 | 379132 | 47097 |
| sample-v2.json | 160 | 140161 | 21306 |
| security-live-v2.json | 0 | 0 | 0 |
| smoke-v2.json | 25 | 21197 | 3000 |

V2数据库全部联调/评测/页面累计：856次有usage的模型调用，输入747481、输出97237 Token。此总量含所有轮次及页面验收，不与表中重复相加。没有读取账单，无法确认缓存折扣、赠送额度或实际货币扣款，货币费用记为未核实。明细与首字/总耗时中位数见[usage-metrics.json](evidence/usage-metrics.json)。

## 安全与业务证据

- MCP：独立JVM端口18084，官方SDK0.14.1，Streamable HTTP，实际协议2025-06-18；真实初始化、5工具发现和调用，两端共享签名关联ID。
- 身份：Session→短期HMAC上下文，限定audience/scope/Skill/请求/运行记录；缺失、过期、篡改、伪造、错误受众/权限拒绝。Alice/Bob并发隔离经真实HTTP验证。仅服务凭据加匿名有效CSRF不能确认或丢弃：{'confirm': 401, 'discard': 401}；无签名用户上下文的MCP初始化：401。
- Skill：Schema与固定权限上限均强制；客户端/服务端白名单、参数字段拒绝身份注入；上传文档不能变Skill或权威规则。真实提示注入文档确实进入检索，仍不能串用户或执行订单。
- Agent：独立模型职责与上下文，简单问题只选必要专业Agent；混合任务有两个专业Agent及父子记录，无递归委派工具。MCP探测和表单准备标识为非模型运行，不虚算专业Agent委派。
- 全局预算：数据库原子计数，2委派/8工具/2底层检索/12所有模型/240秒；跨Agent不重置。超限、期限和MCP断连明确失败，不回退普通Java方法。
- 业务：生成方案前后SQL订单不变；只有Session+CSRF按钮确认才提交；重新核对归属、状态、时间、版本、规则、费用、日期和舱位。锁、幂等、事务回滚、审计失败和触发器篡改注入测试均真实PG执行。
- 丢弃：旧版本/过期可丢弃、订单不变、记录持久化；确认/丢弃竞争同一方案锁，重复幂等，已执行不能丢弃，已丢弃不能确认。

## 浏览器与SQL

页面操作使用真实本机8082，认证从DPAPI读取且不出现在截图和报告。关键截图见[screenshots](screenshots)。SQL证据为browser-before-confirm.json、browser-after-confirm.json、browser-after-discard.json，保存真实行及版本/费用/状态。方案有效期测试对精确的本轮方案临时缩短到期时间，生产默认仍300秒；页面加载未来到期时间后保持不刷新，自动禁用按钮。

重启前后六张业务表指纹一致：True，见[restart-persistence.json](evidence/restart-persistence.json)。Session重启后重新登录，订单、方案状态、聊天、文档和操作记录保留。

## 隔离、清理与交付

V2独立Compose项目java-ai-v2、业务库java_ai_v2、测试库java_ai_v2_test、数据卷、PID和日志。原V1迁移、业务规则demo-v1.0和MIT许可保留。V0/V1服务未停止；V1订单、方案、文档、操作、会话、消息六表指纹与开始时相同：True。全量过程中8个V2种子订单逐字段不变：True，随后浏览器明确确认演示订单的变更另有证据。

评测订单只按结果JSON的精确EV清单清理。聊天/追踪保留；没有按前缀重置任何库。秘密扫描171文件，实际凭据匹配0。DPAPI、local.env、模型、日志留在独立运行目录，不提交前端或源码。

架构、请求和确认图以最终实现绘制：[架构](architecture.svg)、[请求](request-flow.svg)、[确认](confirmation-flow.svg)及对应Mermaid源码。普通使用者说明见[FRONTEND-ACCEPTANCE.md](FRONTEND-ACCEPTANCE.md)，逐步列出点哪里、输入、预期及失败判据。启动/停止/测试与无密钥配置在runtime；实际运行目录为D:\.work\jianli\.java-ai-runtime-v2。
