# Java AI Playground V2

本机中文航空客服演示：真实 MCP、受管理的业务 Skills、有总预算的协调与专业 Agent、可确认及丢弃的退改签方案。订单、收费及航空库存均为演示，不产生实际交易。

采用 MIT [LICENSE](LICENSE)，来源记录保存在 [README.upstream.md](README.upstream.md)。V0、V1 源码、运行目录和业务数据保留；V2 从独立数据库初始化，不复制或重置 V1 订单。

## 本机启动

```powershell
# 已部署的本机运行目录
& 'D:\.work\jianli\.java-ai-runtime-v2\Start-V2.ps1'
# 交互查看账号密码；不要重定向进日志
& 'D:\.work\jianli\.java-ai-runtime-v2\Show-DemoAccounts.ps1'
# 停止 V2；数据库卷和 V0/V1 服务保留
& 'D:\.work\jianli\.java-ai-runtime-v2\Stop-V2.ps1'
# 修改源码后，先停止 V2 应用和 MCP，再构建启动
& 'D:\.work\jianli\.java-ai-runtime-v2\Start-V2.ps1' -Build
```

工作台：[http://127.0.0.1:8082](http://127.0.0.1:8082)。用户名 alice / bob，密码只读复用本机 V1 DPAPI 演示凭据。初始订单 A1001–A1004 / B1001–B1004，后续状态以页面为准。MCP 独立 JVM：[http://127.0.0.1:18084/mcp](http://127.0.0.1:18084/mcp)，协议要求认证和初始化，直接在地址栏打开不会获得工具权限。

WSL Ubuntu 内 Docker Compose 项目 `java-ai-v2`，容器 `java-ai-v2-db-1`，独立卷 `java-ai-v2_v2-postgres`，数据库 `java_ai_v2` / `java_ai_v2_test`，端口15433。Environment.ps1 自动解析 WSL IPv4；Windows 无需 Docker CLI。独立 CPU Embedding 端口18083，仅只读复用 V1 模型和 Python 包，不停止 V1 Embedding。

首次准备同一工作区的独立运行目录可运行 `runtime/Setup-V2.ps1`；已有 local.env 和 DPAPI 文件不会覆盖。配置示例见 `runtime/config.example.env`。实际凭据只在外部运行目录，DeepSeek Key 只读解密原 V0 DPAPI 文件至后端进程环境。普通日志、报告、前端和源码不包含凭据。

## 验证

```powershell
# PostgreSQL + 真实 SDK HTTP 传输；模型明确模拟
& 'D:\.work\jianli\.java-ai-runtime-v2\Test-V2.ps1' -Mode backend
# 固定26个真实模型抽样；消耗DeepSeek额度
& 'D:\.work\jianli\.java-ai-runtime-v2\Test-V2.ps1' -Mode sample
# 固定150场景全量，混合真实模型、业务、安全与协议检查
& 'D:\.work\jianli\.java-ai-runtime-v2\Test-V2.ps1' -Mode full
```

测试库仅允许精确的 `java_ai_v2_test`；Flyway 执行前另检查数据库名。业务评测只创建随机 EV 前缀订单，按本轮 JSON 的精确订单清单清理。保留聊天与追踪供查看；绝不按前缀批量删除用户数据。完整评测不等于模型普遍准确率。实际数量、失败、构建指纹和复测范围见验证报告，不能用编译通过代替功能通过。

## 交付文档

- [实现与安全边界](docs/IMPLEMENTATION.md)
- [实际验证报告](docs/VALIDATION-REPORT.md)
- [普通使用者逐步验收](docs/FRONTEND-ACCEPTANCE.md)
- [V1 → V2 变化清单](docs/CHANGES-V1-V2.md)
- [架构图](docs/architecture.svg)、[请求流程图](docs/request-flow.svg)、[确认流程图](docs/confirmation-flow.svg)
- [浏览器截图](docs/screenshots)、[固定场景](evaluation/scenarios.json)
- `docs/v1-reference` 为继承的 V1 历史报告，只作来源记录，不代表 V2 验收。

前端“请求追踪”支持 ID、状态、类型筛选及分页，详情为结构化 JSON。消息下方的链路按钮直接打开对应请求。“MCP 与 Skills”页实际执行初始化和工具发现，展示当前连接和最近错误、只读 Skill 权限与约束。
