# V2 最终实现

## 版本与协议

Java21、Boot3.3.3、Vaadin/Hilla24.4.11、React/TypeScript/Vite 继承 V1。LangChain4j1.24.0 使用普通 Java SDK。MCP 锁定官方 `io.modelcontextprotocol.sdk:mcp:0.14.1`，客户端 `HttpClientStreamableHttpTransport`，服务端 `HttpServletStreamableServerTransportProvider`，实际协商 `2025-06-18`。没有引入 Spring AI 或 LangChain4j 的另一个 MCP starter。

兼容核对基于该版本 Maven POM 和 sources.jar，而非最新版示例：[官方0.14.1源码](https://github.com/modelcontextprotocol/java-sdk/tree/v0.14.1)、[mcp-core POM](https://repo.maven.apache.org/maven2/io/modelcontextprotocol/sdk/mcp-core/0.14.1/mcp-core-0.14.1.pom)、[LangChain4j 官方入门](https://docs.langchain4j.dev/get-started/)。SDK 使用 Jackson2 适配和 Reactor3.7，项目显式锁 Reactor BOM2024.0.0；Servlet transport 使用的异步 API 在当前 Tomcat10.1 实际运行验证。Boot 管理 Jackson2.17。依赖选择经生产构建及真实传输集成测试确认，不从版本号推断兼容。依赖树随验证证据保存。

MCP 服务为同一生产 JAR 的独立 JVM：`--spring.profiles.active=mcp --server.port=18084 --app.demo.enabled=false`。只注册 `/mcp` 和无敏感信息的 `/mcp-health`，其余路径拒绝，未注册交互式 Api。所有服务仅监听127.0.0.1；数据库 WSL 内发布15433供本机访问。

## 身份与权限

浏览器通过 Session、CSRF 和 BCrypt12 登录后，Api 捕获持久化用户ID。每个专业 Agent 建立独立 MCP 客户端连接，发出不可变的短期 HMAC-SHA256 签名上下文，内容包括 owner、request、span、Skill ID/版本、固定 audience、scope、iat、exp 和 nonce。有效期300秒；持久化请求总时限240秒，结束的 Agent capability 不再有效。

HTTP 同时要求独立服务令牌和签名上下文。服务验证签名、时间、受众、scope、Skill版本、运行记录和请求所属用户、截止时间；不接受裸userId。工具参数严格匹配Schema字段，不能额外传入owner或权限。认证上下文不进入模型输入、业务事件或日志。无效认证只记录通用失败原因，不信任其伪造的所属用户或关联ID。

身份不保存在全局可变字段或共享连接中。Alice/Bob并发调用采用各自不可变 capability 和客户端。专业 Agent 的上下文每次新建，并来自所属会话历史；模型客户端自身无聊天记忆。请求证据缓存从真实片段表重新读取，同时检查请求和文档归属。

MCP 只有 `search_knowledge`、`get_my_orders`、`get_order`、`propose_cancellation`、`propose_change`。没有确认、丢弃、SQL、任意文件、网络或脚本工具。仅服务令牌无法初始化 MCP，更不能调用主应用的Session+CSRF确认接口。连接失败时记录 `MCP_ERROR` 并明确失败，不回退本地 Java 方法。

## Skills 与 Agents

`src/main/resources/skills/*.json` 只能由部署者管理。`schema.json` 使用 JSON Schema2020-12、拒绝额外字段，并由 networknt1.5.6 在启动时校验。后端再检查每类Skill固定权限上限和Agent对应关系；不存在上传/启用配置或执行任意脚本的接口。用户知识文件只进入知识表，不能变成Skill。

- terms_qa2.0.0：knowledge Agent，仅search_knowledge，最多3步/2次工具；真实片段或明确资料不足。
- order_lookup2.0.0：order Agent，仅订单列表和详情，最多3步/3次工具。
- change_plan2.0.0：order Agent，可查订单、检索和准备方案，最多3步/6次工具；缺参数询问，没有权威规则拒绝。

协调 Agent 用独立无工具提示词进行路由和多轮问题改写。后端再以最新用户文字检查准备操作的边界：纯政策问题不授予方案工具，“生成确认卡片”允许准备，“确认执行”仍不能执行。模型选择和最终选择同时写入AGENT_ROUTE，上传文档与模型改写不能提升这一权限。简单问题仅委派对应专业 Agent；明确混合任务可依次委派知识及订单 Agent，不能重复委派相同角色或超过2个，专业Agent没有再委派工具。知识Agent完成一次有界检索任务后结束，底层RAG自行执行最多两次检索，避免无资料时反复请求工具。订单Agent的工具定义取自真实发现结果，再按Skill过滤；客户端和服务端均检查白名单。

专业Agent的查询和方案输出逐项记录，最终协调答复使用独立无工具上下文，只以真实工具输出和证据作答；引用数字检查真实来源边界。无来源且无实际订单结果时明确拒答。准备方案不等于执行，文字确认无效。Skill版本与业务规则`demo-v1.0`分开。

## 全局预算和追踪

`request_budgets` 使用数据库原子条件UPDATE共享上限：2次委派、8次工具、2次底层检索、12次所有DeepSeek调用、240秒。模型调用包括协调路由、重排、补检索改写、专业决策与最终流式输出；重试次数设0。每次尝试在网络前扣计数，实际返回usage另记，因此尝试数可能多于成功usage记录，不把预算计数当作计费Token。

`agent_runs` 记录父子调用、角色、Skill版本、步骤及工具消耗、开始结束和失败。MCP客户端记录发现、调用和结果；独立服务记录身份验证、实际结果、进程PID及耗时。两侧使用后端生成并签名的同一个请求ID，认证令牌不进入追踪。RAG保存查询、候选原文、相似度、重排结果；MODEL保存模型、服务商响应ID、实际input/output usage与耗时。首字和总耗时记录在请求主表。JSONB在后端解析后交给页面。

## 数据库与订单生命周期

源码仍保留`org.vaadin.marcus.v1`包名以复用已验证的业务实现；包名不代表调用V1服务。两进程复用同一套WorkflowService和TransactionalWorkflow源代码及费用规则，没有两套算法。原V1迁移和demo-v1.0条款未改写；V2增量迁移增加执行表及认证审计。

方案PENDING、EXECUTED、DISCARDED持久化；有效期和旧订单版本独立检查。准备不修改订单。确认仅通过Api：锁方案、锁归属订单、重查有效期/版本/状态/日期/舱位/规则/费用，同事务更新与成功审计，直接SQL核对状态、版本、日期、舱位、票价后提交。重复确认返回原执行回执；两个方案竞争订单只有一个有效。丢弃与确认锁同一方案，过期和旧版本仍可丢弃，不修改订单，已执行不能丢弃。

数据库迁移前和应用启动时检查精确库名，只允许java_ai_v2或java_ai_v2_test。集成测试另在任何TRUNCATE前断言精确测试库名。所有评测清理基于本轮具体随机订单清单，没有对V1业务库的写入路径。

## 页面和范围

每秒刷新方案时钟，无需F5禁用过期确认；用当前订单版本显示“订单已变化”。已执行卡片显示订单、结果版本、费用/退款和操作时间，刷新及重启后从持久化方案恢复。对话列表显示创建时间及首条问题标题；消息显示时间及本次链路入口。

MCP与Skills页读取真实探测和受校验配置。追踪支持筛选、分页，明确的执行详情区展示预算、父子Agent和结构化事件。没有写死演示步骤。保留资料不足拒答、私有文档、CSRF、业务并发及事务安全。没有接入真实航空库存、支付或外部追踪平台，也没有声称生产级压测或一般准确率。
