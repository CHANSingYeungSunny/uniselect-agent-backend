---
name: uniselect-cs-agent-tests-readme
overview: 为 uniselect-cs-agent 补全两项交付物（优先级顺序）：1) 红线回归单元测试集（JUnit5，覆盖 6 个 Inspector 锁定的红线场景，防后续回退）；2) README 文档（架构图/启动/验证 curl/真实依赖切换/5步红线清单）。两项均为新增文件，不改动现有业务代码。
todos:
  - id: write-session-test
    content: 编写 SessionContextServiceMockTest：覆盖联合Key隔离、Token防爆、降级纯粹性、并发安全 4 个红线用例
    status: completed
  - id: write-prompt-test
    content: 编写 PromptAssemblerTest：验证历史上下文位于系统规则层之后、商家业务层之前
    status: completed
  - id: write-metrics-test
    content: 编写 MetricsCollectorMockTest：验证异步埋点调用即返回不阻塞
    status: completed
  - id: write-readme
    content: 编写 README.md：架构流程图、Maven启动、curl验证、Mock替换指引、5步红线清单
    status: completed
    dependencies:
      - write-session-test
      - write-prompt-test
      - write-metrics-test
---

## 用户需求

在已完成的 `uniselect-cs-agent` Spring Boot 工程（全 5 步 Loop Engineering，Lints 0 错误）基础上，新增两项交付物，优先级不同：

### 优先级一：红线回归测试集（单元测试）

目标：锁定 Inspector 已修复的 5 个并发/降级缺陷，防止后续改代码回退。覆盖用户指定的 6 个用例：

1. 联合 Key 隔离：M-1001:s1 与 M-1002:s1 数据不串
2. Token 防爆：超过 8 轮触发滑动窗口裁剪，超过字符阈值触发摘要压缩
3. 异步埋点不阻塞：调用 recordLayer2Violation 后立即返回，不等待线程执行完成
4. Prompt 注入位置：历史上下文位于系统规则层之后、商家业务层之前
5. 降级纯粹性：loadHistory 抛异常时返回空列表，单轮对话正常
6. 并发安全：同一 session 多线程 appendTurn 不抛 ConcurrentModificationException

### 优先级二：README 文档

便于交接、真实依赖接入与答辩展示，包含：架构主链路流程图、启动方式（Maven 命令）、验证 curl 示例（带 merchant_id 的 SSE 请求）、切换真实依赖指引（需替换的 Mock 类与接口契约）、5 步红线清单。

## 核心特性

- 测试与文档均为新增文件，不修改任何现有业务源码，避免引入回归风险。
- 测试以纯单元测试为主（直接 new Mock 实现，不启动 Spring 容器，更快更聚焦红线），仅在需要验证异步线程切换时用轻量断言。
- README 写入工程根目录 README.md，中文，含 mermaid 主链路流程图。

## 技术栈选择

- 沿用工程现有栈：Java 17 + Spring Boot 3.4.x (Maven) + JUnit 5 + spring-boot-starter-test（已存在于 pom.xml）。
- 测试框架：JUnit 5（Jupiter）+ AssertJ 风格断言（用原生 JUnit assertEquals/assertTrue 即可，不引入额外依赖）。
- 文档：Markdown（README.md），含 mermaid 代码块流程图。

## 实现方案（高-Level 策略）

采用"分层测试 + 文档化"策略。测试直接构造 `SessionContextServiceMock`、`PromptAssembler`、`MetricsCollectorMock` 等实现类实例（Mock 实现为无参或仅依赖 `@Value` 默认值的普通 Spring Bean，可直接 new 或用 `@SpringBootTest` + `@ActiveProfiles("mock")` 注入）。优先纯单元测试（new 实例）以最快锁定红线，不依赖容器启动。

### 关键技术决策

- **联合 Key 隔离**：直接验证 `SessionContextServiceMock` 的 `compositeKey` 行为——向 M-1001:s1 与 M-1002:s1 分别 appendTurn 后，loadHistory 返回各自独立数据，断言互不串扰。
- **Token 防爆**：循环 appendTurn 超过 8 轮，断言 loadHistory 大小 <= 8（滑动窗口）；构造超长对话使累计字符 > 2000，断言返回列表中含"[历史对话摘要"前缀（摘要压缩生效）。
- **异步埋点不阻塞**：调用 `recordLayer2Violation` 后记录纳秒时间戳，断言方法在微秒级返回（远小于线程池任务执行耗时），证明 fire-and-forget 不阻塞主线程。
- **Prompt 注入位置**：调用 `assemble` 后断言返回字符串中 `系统规则层` 文本索引 < `历史对话上下文` 索引 < `商家业务层` 索引。
- **降级纯粹性**：用反射或包装使 `loadHistory` 内部抛异常（或构造 Mock 子类覆盖），断言返回空列表且主流程不抛错（Controller 级可用 @SpringBootTest 验证单轮对话正常）。
- **并发安全**：起 N 个线程对同 session 并发 appendTurn + 同时 loadHistory，运行足够轮次，断言无 ConcurrentModificationException 且最终大小符合预期。

### 实现注意事项

- 测试不修改生产源码；`SessionContextServiceMock` 的 `store` 为 private，测试通过公共方法（appendTurn/loadHistory）验证行为，不破坏封装。
- 异步测试需注意 JVM 退出前让 metrics 线程有机会执行（可用 `Thread.sleep` 小延时后在测试中忽略具体计数，仅验证"调用即返回"）。
- README 中的 curl 示例需对应 Controller 的 `/api/cs/chat?merchantId=M-1001&sessionId=s1&message=...` GET 端点与 merchant_id 参数（非 header，工程当前用 query 参数）。
- 所有 Mock 替换指引须对照现有接口契约：`LlmClient`、`RagService`、`SessionContextService`、`MerchantConfigService`、`ProductDataGateway`，并说明 `@Profile("mock")` 切换机制。

## 架构设计

本次为测试与文档补充，不改动业务架构。测试针对已有分层服务（context / prompt / metrics）的红线契约进行验证，形成"红线回归测试集"守护。

## 目录结构（新增文件）

```
uniselect-cs-agent/
├── src/test/java/com/uniselect/cs/
│   ├── service/
│   │   └── SessionContextServiceMockTest.java   # [NEW] 红线测试：联合Key隔离(1)、Token防爆滑动窗口+摘要(2)、降级纯粹性(5)、并发安全(6)
│   ├── controller/
│   │   └── PromptAssemblerTest.java             # [NEW] 红线测试：Prompt注入位置(4)，直接构造 PromptAssembler + MerchantConfigServiceMock
│   └── aspect/
│       └── MetricsCollectorMockTest.java        # [NEW] 红线测试：异步埋点不阻塞(3)，验证 recordLayer2Violation 调用即返回
└── README.md                                    # [NEW] 工程文档：架构图/启动/curl/切换真实依赖/5步红线清单
```

注：`pom.xml` 已含 `spring-boot-starter-test`，无需修改；若纯单元测试（new 实例）已足够，则不引入 `@SpringBootTest` 以减少启动开销。

## 关键代码结构（测试断言示例，非实现体）

- PromptAssemblerTest：断言 `assemble(...)` 结果串中 `"最高优先级"`(系统规则层标记) 出现位置 < `"历史对话上下文"` < `"商家业务层"`。
- SessionContextServiceMockTest：断言 `loadHistory("M-1001","s1")` 与 `loadHistory("M-1002","s1")` 返回列表内容互不包含；超 8 轮后 size<=8；超字符阈值后存在以 `"[历史对话摘要"` 开头的元素。