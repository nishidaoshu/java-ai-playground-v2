# V1 实际验证与评测报告

生成时间：2026-11-24 16:55:37 CST。V1 地址：http://127.0.0.1:8081。报告只依据保存的真实运行结果；不把编译、模拟模型或固定拒答当作真实模型全链路验证。

## 正式结果

- 生产 Maven 构建通过；TypeScript noEmit 通过。
- 真实 PostgreSQL 集成测试：执行23，失败0，错误0，跳过0。这些测试的模型明确模拟，数据库、事务、写锁、版本、回滚和并发均真实。
- 最终固定评测集：执行100/100，规则断言通过99，失败1。真实模型/RAG问题与后端安全业务场景混合，不能称为100道纯模型问答，更不能称为普遍准确率。
- 全量中的失败场景['P03']修复后，另行执行最终定向回归29场景，通过29、失败0。未把定向通过改写为新的100/100全量结果。
- 当前最终运行JAR与源码构建产物SHA-256一致：`c7412f8511f7547a7fbe63d4d41e6ef506ce6b6139be1eb409acd17a946070eb`。数据库PostgreSQL 17.8 (Debian 17.8-1.pgdg12+1)，pgvector 0.8.1。全量与最后修复的构建指纹分别保存，可区分验证范围。

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
| knowledge | 20 | 20 | 0 |
| unsupported | 10 | 10 | 0 |
| multiturn | 10 | 10 | 0 |
| order | 10 | 10 | 0 |
| proposal | 10 | 9 | 1 |
| confirm | 10 | 10 | 0 |
| repeat | 10 | 10 | 0 |
| expired | 5 | 5 | 0 |
| stale | 5 | 5 | 0 |
| unauthorized | 10 | 10 | 0 |

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
| 客户端调试：SSE Accept不匹配 | 4 | 0 | 4 | [initial-smoke.json](../evaluation/results/initial-smoke.json) |
| 客户端调试：SSE UTF-8解码问题 | 4 | 0 | 4 | [initial-smoke-fixed.json](../evaluation/results/initial-smoke-fixed.json) |
| 调试修复后的4场景连通检查 | 4 | 4 | 0 | [initial-smoke-utf8.json](../evaluation/results/initial-smoke-utf8.json) |
| 初轮真实抽样，暴露多轮问题 | 20 | 19 | 1 | [baseline-sample.json](../evaluation/results/baseline-sample.json) |
| 初轮全量中止，修复上下文与工具标记 | 28 | 27 | 1 | [baseline-full-partial.json](../evaluation/results/baseline-full-partial.json) |
| 上下文与最终回答修复后真实抽样 | 20 | 20 | 0 | [live-sample-fixed.json](../evaluation/results/live-sample-fixed.json) |
| 固定0.45阈值全量，暴露无依据兜底问题 | 100 | 98 | 2 | [live-full-fixed.json](../evaluation/results/live-full-fixed.json) |
| 固定0.50阈值全量，暴露2题漏检 | 100 | 98 | 2 | [live-final-full.json](../evaluation/results/live-final-full.json) |
| 0.50初检/0.40补检全量，暴露检索子问题覆盖 | 100 | 99 | 1 | [live-release-full.json](../evaluation/results/live-release-full.json) |
| 最终检索子问题与证据复用修复后定向回归 | 29 | 29 | 0 | [live-focused-final.json](../evaluation/results/live-focused-final.json) |

初轮中止全量不是完整100场景，表内按实际执行数记录。重复保存的baseline副本不计作新的执行。调试客户端错误不计作模型准确率。本轮全量失败情况（修复前的真实记录）：

- P03：Expected pending proposal；问题：为 {order} 生成退票确认卡片

已在最终定向回归中通过的对应场景：['P03']。最终定向回归不等同再次全量；未声称普遍准确率或性能提升。

## 服务商用量与耗时

本节主要统计最后一次100场景全量关联请求，包含失败请求已返回的usage。模型实际调用372次，服务商返回输入268692 Token、输出26951 Token。Embedding无外部付费API调用。没有读取账单余额，无法给出实际扣款金额；缓存折扣、赠送额度等以服务商账单为准，不能把Token总量直接伪装成货币花费。

| 模型用途 | 调用数 | 实际输入Token | 实际输出Token |
|---|---:|---:|---:|
| answer-stream | 59 | 53360 | 6377 |
| llm-rerank | 99 | 74180 | 7236 |
| rewrite-retry | 12 | 451 | 79 |
| route-and-context-rewrite | 70 | 10545 | 1351 |
| tool-decision-1 | 70 | 57915 | 3954 |
| tool-decision-2 | 58 | 68373 | 7520 |
| tool-decision-3 | 4 | 3868 | 434 |

修复后额外定向回归实际输入149972 Token、输出15640 Token。整个V1数据库记录的全部联调/评测/页面调用共1632次模型调用、实际输入1319156 Token、输出138548 Token，包含调试和此前运行，未对重复保存的JSON副本重复计费统计。

全量聊天请求中有首字记录70条，首字耗时中位数3955.0ms，总耗时中位数4665.5ms。首字包括改写、工具决策、检索和重排；无依据固定拒答记录首次输出时间。本机顺序功能评测、非压测，无V0对照基线，不宣称性能或准确率提升。

## 页面、运行与局限

截图在docs/screenshots。流程图flow.png、flow.svg与flow.mmd按实际实现绘制。V0/V1变化详见CHANGES-V0-V1.md。V0的8080实例保留，V1只使用8081、CPU Embedding 18082和WSL数据库15432。

Windows未安装Docker CLI，实际使用现有WSL Ubuntu Docker Engine和用户级Compose v2.40.3。镜像通过Windows现有Clash代理下载并SHA校验、导入WSL，未修改已有Docker daemon代理。Compose健康检查和命名数据卷可用，默认启动/停止脚本已实测。

局限：小型演示语料和固定词面/状态断言，不是独立人工语义评判或生产SLA；无真实支付/航空库存；Session不跨应用重启；上下文最多20条，页面消息最多100条；未集成外部追踪平台、MCP、Skills或多Agent。密钥、口令均不进入源码、前端、报告或日志，另有secret-scan.json记录本机扫描结果。

## 最终构建的额外页面改签验收

最终构建还经真实DeepSeek工具为A1002生成改签至2026-12-15商务舱的方案。确认前直接SQL核对仍为2026-12-10、经济舱、1000元、版本0；页面点击确认后为2026-12-15、商务舱、1500元、版本1，方案EXECUTED、演示费用550元、成功确认记录恰好1条。见browser-change-before.json、browser-change-after.json与截图11/12。最终页面又完成上传休息室资料的真实问答及来源展开，追踪显示实际Token与耗时，见截图13–15。
