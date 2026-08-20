# 长期记忆（UniSelect 客服/导购 Agent 代码版）

## 项目事实
- **当前工作区（2026-08-20 起）：`C:\Users\Asus\Desktop\uniselect-agent-backend`**（原中文目录 `客服 Agent 方案 + 导购 Agent` 已整体改名为 ASCII；工程位于其子目录 `uniselect-cs-agent\`）。包含客服/导购 Agent 多份方案文档（.md 形式，用户提及的 PDF 实际为 .md）。
- **工程运行/开发目录：`C:\Users\Asus\Desktop\uniselect-agent-backend\uniselect-cs-agent`（全 ASCII，2026-08-20 已验证可正常 IDE Run / java -jar）**。历史教训：中文路径会同时导致 mvn spring-boot:run `Could not build classpath`、PowerShell 传参中文损坏、IDE Run 报 ClassNotFoundException 三类问题。旧开发目录 `C:\Users\Asus\uniselect-cs-agent`（ASCII 备份副本）仍存在，可作备份。**Git push 与后续开发一律在当前 ASCII 工作区**。
- 启动环境（本机无全局 mvn/JAVA_HOME）：JDK21 在 `C:\PROGRA~1\Java\jdk-21`；Maven 3.9.9 在 `C:\Users\Asus\mvn_home\apache-maven-3.9.9\bin\mvn.cmd`；`start-demo.cmd`（package + java -jar 一键脚本）；后台启动模板见当日日志。
- 核心指导文档：`UniSelect客服导购Agent代码版方案评审与优化建议.md`（转人工前置、流式+并行预取、三层拦截双闭环、时间预算表）。
- 业务/数据文档：`客服Agent方案_代码实现版.md`（F1-F11、两层 Prompt、merchant_id 隔离）、`动态知识库搭建方案.md`（静态层/动态层字段对照表）。

## 工程约定（用户已确认）
- Loop Engineering 模式：Builder 写 → Inspector 审 → 通过进下一步，**单步执行、绝不跨步预生成**。
- 技术栈：Java 17 + Spring Boot 3.4.x (Maven) + Spring AI（后续）+ SSE(SseEmitter)。
- 新建工程 `uniselect-cs-agent/`（Maven，根目录），当前阶段**优先 Mock/内存实现**（Map 模拟向量检索、硬编码模拟 LLM 流式），真实 pgvector/Redis/LLM key 留后续，架构需支持无缝切换。
- 架构红线：merchant_id 严格行级隔离（缺失即 403 拒绝，绝不兜底默认商家）；转人工预判前置 <10ms 短路不进 LLM；时间预算 P95≤2s；三层拦截（组装约束/生成中扫描/输出终检）。
- **Prompt 注入防御（2026-08-20 落地）**：注入词表/正则统一在 `SystemRuleConstants`（PROMPT_INJECTION_KEYWORDS/PATTERNS + PROMPT_INJECTION_MESSAGE），共享检测器 `common/util/PromptInjectionGuard`；网关前置 `predictInjection` 命中即 emit `event: degrade`（reason 前缀 `injection:`）+ 短路不进 LLM；Mock LLM 兜底拒绝（仅对 `PromptAssembler.extractUserInput` 提取的用户原文检测，**绝不对完整 prompt 匹配**——系统规则层文本本身含「忽略…规则/其他商家」字样会全量误命中）。

## 已完成
- Step 1（2026-08-19）：工程骨架 + `CsGatewayInterceptor`（merchant_id 校验+转人工预判短路）。
- Step 2（2026-08-19）：意图识别（规则+小模型双通道）+ `ToolUsePrefetchService`（CompletableFuture 并行预取，3s 超时降级）+ `ProductDataGateway` Mock（merchant_id 隔离）。

## Inspector 待办（记入·后续真实实现）
- 商家追加转人工词防呆校验：真实 DB 保存侧须调用 validateHandoffKeywords 拒绝非法词（Step 3）。
- Step 4 建议：①degrade 事件 reason 含越权词明文，改词类别避免二次暴露；②流式用公共 ForkJoinPool，高并发需独立线程池。
- Step 2：extractQuery 仅返回整句，真实场景需抽取订单号/SKU。
- 真实接入：LLM/pgvector/Redis 替换各 Mock（接口已稳定）；DB 实现替换 MerchantConfigService/RagService/SessionContextService 的 Mock。

## 全 5 步状态：✅ 全部完成（2026-08-19，Loop Engineering）
工程 `uniselect-cs-agent/` 已具备完整主链路：拦截→意图→并行预取→RAG→Prompt(系统规则层最高优先级+历史上下文)→LLM SSE流式(主备)→二层生成中截断→三层终检revoke→多轮上下文(联合Key/窗口/摘要/TTL)→异步埋点。Lints 全程 0 错误。
