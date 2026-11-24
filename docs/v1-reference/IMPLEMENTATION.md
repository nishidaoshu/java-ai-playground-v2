# V1 实现与边界

V1 源于 [Marcus Hellberg / java-ai-playground](https://github.com/marcushellberg/java-ai-playground)，保留根目录 MIT LICENSE 和 README.upstream.md。V0 源码与运行目录均未作为写入目标。MCP、Skills、多 Agent 未纳入本次实现。

## 版本与官方兼容依据

| 组件 | V1 锁定值 | 说明 |
|---|---|---|
| Java | 21.0.13.1+2 | 复用已有便携 JDK |
| Spring Boot / Vaadin Hilla | 3.3.3 / 24.4.11 | 沿用 V0；React 18、TypeScript 5.4.5、Vite 5.4.2 |
| LangChain4j | 1.24.0 | 使用 langchain4j 与 langchain4j-open-ai 的普通 Java SDK，移除 0.35.0 Spring starters |
| Flyway | 10.20.1 | PostgreSQL 17 支持；使用版本化 SQL 建表，JPA validate |
| PostgreSQL / pgvector | 17.8 / 0.8.1 | Compose 镜像 pgvector/pgvector:0.8.1-pg17，实际数据库版本在报告记录 |
| Embedding | BGE-small-zh-v1.5 | 中文 512 维，本机 ONNX int8 CPU，CLS pooling + L2 normalization |
| ONNX Runtime / tokenizers | 1.23.2 / 0.22.1 | 外部 Python 3.10 venv，权重与 tokenizer 本机存放 |
| Chat / rerank | deepseek-flash / 独立配置的 deepseek-flash | 直接连接官方接口，无兼容转发器 |

[LangChain4j 官方入门](https://docs.langchain4j.dev/get-started/)要求 Java 17+。当前 [Spring Boot starters 文档](https://docs.langchain4j.dev/tutorials/spring-boot-integration/)区分 Boot 3 与 Boot 4；本项目不用这些 starters，显式构造普通 Java SDK 模型，避免把新版 starter 的 Spring 版本要求套到 Boot 3.3。实际 Maven 构建和真实模型调用用于验证此组合。[OpenAI 集成文档](https://docs.langchain4j.dev/integrations/language-models/open-ai/)提供 custom parameters。已检查 1.24.0 sources.jar 中的 builder 和流式 includeUsage 实现。

[DeepSeek 当前接口](https://api-docs.deepseek.com/api/create-chat-completion/)与 [thinking 文档](https://api-docs.deepseek.com/guides/thinking_mode/)支持 thinking.type=disabled；本项目显式关闭 thinking，以客户端工具调用完成有界决策。同步 ChatModel 处理工具决策与 LLM 重排，StreamingChatModel 输出最终回答，服务商返回的 usage 逐次入库。SDK 流式请求包含 stream_options.include_usage=true。没有估算 Token，也没有把模型输出分词数当作服务商计费数据。

## 登录、数据与权限

VaadinWebSecurity 配合 Spring Security 表单登录和 HttpOnly、SameSite=Strict Session Cookie。REST 修改接口保留 CSRF，前端先取 /api/csrf；密码使用 BCrypt cost 12。两个演示账号 alice/bob 的随机初始密码保存在运行目录 demo-accounts.dpapi，前端、Git 和报告均不保存密码。

所有 /api/** 业务接口要求登录。Identity 从 SecurityContext 中解析持久化用户。聊天线程仅接收控制器捕获的后端用户标识；模型工具签名不存在 userId 参数。订单、方案、聊天、私有文档和追踪详情均检查归属；未找到和越权统一返回404。全局演示条款只读，上传文档属于当前用户，不能覆盖权威费用规则。

Flyway 持久化 users、flight_orders、conversations、chat_messages、knowledge_documents、knowledge_chunks、operation_plans、operation_records、request_traces、trace_events。订单具有 @Version；确认操作对方案与订单取数据库写锁。成功变更、执行状态及成功操作记录同事务提交；异常回滚后另开事务保存失败记录。保存并 flush 后通过同一事务连接直接 SQL 检查订单状态、版本、日期、舱位和票价，绕开 JPA 一级缓存；不匹配即回滚。测试用 PostgreSQL 触发器故意篡改写入结果，验证这一复核确实读取数据库。

## 检索与回答

UTF-8 TXT/Markdown → 保留原文的字符分段 → 中文 BGE Embedding → pgvector cosine 检索 → 独立配置的 LLM 重排 → 最终流式回答。上传最多1MB且最多10万字。删除文档级联删除片段；同一所属用户的标题与版本不能重复。

默认 chunk-size=420、overlap=80、candidates=8、top-k=4、min-similarity=0.50、retry-min-similarity=0.40、rerank-threshold=0.60。参数均在 application.properties 可覆盖，未沿用 V0 的50 token分段、固定2条/0.6阈值。20个标注问题和10个无依据问题的候选校准显示：0.45与0.50均命中18/20个相关文档，后者使无依据问题出现任意候选的数量从7/10降至3/10。全量运行进一步暴露客服时间、起飞时区两题漏检，因此保留初检0.50，只在唯一一次补检索中放宽到0.40；0.40的标注候选命中为20/20，但10个无依据问题均会出现候选，必须由LLM重排和拒答约束过滤。这是5个全局片段的小语料校准，不是通用最优或回答准确率。candidates=8为新增文档保留空间，本次小语料不足以证明8优于4。详见 evaluation/results/retrieval-calibration.json 与实际报告。

模型先结合最近6条历史生成独立检索问题及知识需求判断，再按问题选订单工具或知识检索工具；完整对话决策上下文取最近20条。工具可以给出更具体的知识子问题，后端保留该子问题；自动兜底检索才使用独立改写问题，避免把“生成确认卡片”等操作指令当成规则问题送去重排。已经在当前请求检索到的相应同版本权威规则可复用。一轮聊天最多3轮工具决策、8次工具调用，所有工具共享最多2次底层检索预算。初次证据不足时改写后额外检索一次。LLM 重排不达阈值或返回非法格式时按无证据处理。无证据且没有实际订单查询结果的问题由后端固定返回资料不足，即使分类器误判为一般知识也不能绕过。最终回答阶段单独组装证据与工具结果，不携带工具定义或调用消息，避免输出工具调用标记。最终引用元数据取自本次真实检索的 UUID、文档标题、版本与原文片段；不存在的数字引用会拒绝保存为成功回答。前端的“本次检索来源”展示全部保留证据，不宣称每条证据都被模型逐字引用。

Embedding 只消耗本机 CPU，无额外 API 费用。LLM 重排使用现有 DeepSeek Key，产生独立可见的输入/输出 Token；重排模型可通过 RERANK_MODEL 配置。没有独立神经重排模型，本项目明确采用 LLM 重排。货币费用不预估，按用户实际服务商账单计价。

## 退改签业务

取消：CONFIRMED 状态、距演示起飞至少48小时；经济舱收票价20%，商务舱10%，退款为票价减费。起飞时间统一为 Asia/Shanghai 航班日期12:00。

改签：起飞至少24小时；新日期为明天至未来365天；支持 ECONOMY/BUSINESS；经济舱基础50元，商务舱0元；升商务舱加500元固定差价、票价增加500元；不支持降舱和修改航线。所有收费仅为演示。

生成方案必须查询归属、检索当前 demo-v1.0 权威规则并经后端算费。模型只有 proposeCancellation/proposeChange，没有执行工具。方案保存订单快照版本、费用、规则版本和真实检索证据，默认有效300秒。确认只通过受 Session 与 CSRF 保护的 POST /api/plans/{id}/confirm 执行，重检用户、订单、方案、有效期、版本、状态、规则和费用。相同方案重复确认返回原执行结果并记录 REPLAY。两个不同方案竞争同一订单时，一个成功，另一个因旧版本拒绝。

待确认卡片另提供“丢弃方案”：POST /api/plans/{id}/discard同样要求Session、CSRF和方案归属校验。事务中锁定方案，与确认互斥；PENDING可标记为DISCARDED，过期及旧版本均允许丢弃，不修改订单。重复提交返回原结果并记录REPLAY，已执行方案拒绝丢弃，已丢弃方案拒绝确认。保留方案及操作记录，前端待确认区不再显示DISCARDED方案。

## 追踪与局限

每个 HTTP 请求分配新的 X-Request-ID，不信任客户端传入的关联 ID。已登录业务请求写入所属用户的请求记录。模型目的、模型标识、服务商 response ID、实际 usage、工具参数和结果、检索查询、候选片段、重排分数、失败原因入 PostgreSQL。首字耗时从聊天开始计算，包含决策和检索；无依据的固定拒答以首次输出时间计。HTTP 登录体和密码不进入追踪；SDK 请求/响应日志关闭；Key 仅从原 V0 DPAPI 解密至后端进程环境。

没有外部追踪平台、真实支付、真实航空库存、流量压测或生产可用性承诺。聊天按用户串行处理；对话上下文取最近20条，页面最多加载最近100条消息。Session 不跨应用重启保存，但重新登录后的业务数据保留。演示数据只在用户首次创建时初始化，不在重启时重置订单。
