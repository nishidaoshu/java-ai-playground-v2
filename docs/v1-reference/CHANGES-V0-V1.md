# V0 → V1 变化清单

上游：[marcushellberg/java-ai-playground](https://github.com/marcushellberg/java-ai-playground)，V0 HEAD a06ad993326770921e496bc20e26802363e1728c。MIT 许可证保留，原 README 保存在 README.upstream.md。

| 方面 | V0 | V1 |
|---|---|---|
| 工程 / 端口 | java-ai-playground / 8080 | 独立 java-ai-playground-v1 / 8081 |
| 运行配置 | .java-ai-runtime | .java-ai-runtime-v1；Key 只读复用原 DPAPI |
| 数据 | 内存订单、内存聊天 | PostgreSQL + JPA + Flyway，重启保留 |
| 用户 | 无账号与订单归属隔离 | 两个 BCrypt 账号、Session/CSRF、全链路归属检查 |
| 模型集成 | LC4j 0.35.0 + 本地兼容转发 | LC4j 1.24.0 普通 Java SDK，直连官方 DeepSeek，thinking disabled |
| Embedding | 英文 AllMiniLM-L6-v2 | 中文 BGE-small-zh-v1.5，512维，本机 ONNX int8 CPU |
| 向量库 | InMemoryEmbeddingStore | PostgreSQL pgvector，cosine + HNSW 索引 |
| 知识资料 | 一份启动期文件 | 3份全局中文演示规则 + 私有TXT/Markdown上传/版本/删除 |
| 检索 | 固定小分段、2条、0.6阈值 | 可配置分段/重叠/候选/阈值、LLM重排、改写、一次补检索预算 |
| 引用 | 文本回答 | 本次真实检索的文档、版本、原文、片段ID、重排分数 |
| 修改订单 | 模型工具直接取消或修改 | 模型只生成方案，独立确认接口执行；文字确认不执行 |
| 费用 | 提示词和示例限制 | 后端统一算费，与演示条款一致，执行前重算 |
| 安全执行 | 无事务/幂等/并发保护 | 事务、写锁、@Version、过期、旧版本拒绝、重复确认REPLAY |
| 追踪 | 普通控制台日志 | 关联ID、检索/重排/模型/工具/实际Token/耗时入库并可查 |
| 测试 | V0页面演示 | 真实PG事务集成测试、固定100场景、真实模型抽样与全量独立报告 |
| 前端 | 聊天和订单表 | 中文登录、聊天、引用、确认卡片、文档管理、操作历史、请求追踪 |
| 启停 | Maven开发启动和转发器 | Compose 数据库、独立CPU Embedding、生产JAR启动/停止脚本 |

V1 移除了复制工程中的匿名 Hilla AssistantService/BookingService、内存 FlightService 和直接修改订单的 LangChain4jTools。前端继续使用 React/TypeScript、Vaadin/Hilla 文件路由与 Vite 构建；鉴权继承 VaadinWebSecurity，业务 API 为同源 REST/SSE。没有引入 MCP、Skills 或多 Agent。
