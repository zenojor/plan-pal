# ReActEngine → LangChain4j 迁移文档

> 2026-06-04 | 分支: `refactor-try-1` | 提交: 待提交

---

## 迁移背景

原 `ReActEngine`（490行）纯手写 ReAct 循环，存在以下问题：

1. **手写 JSON 解析** — LLM 输出文本后用 brace-matching 提取 JSON，格式错误就跳过整轮
2. **手动消息管理** — `ContextLedger` 174行手动维护 `List<Message>`，无 context window 控制
3. **工具定义分散** — `system-prompt.txt` 手写工具参数表，新增工具需同时改 prompt 和代码
4. **不是真正的 function calling** — LLM 猜工具名和参数格式，容易跑偏

## 目标

用 **LangChain4j 1.0.0-rc1** 替换手写 ReAct 循环，LLM 通过标准 OpenAI function calling 协议调用工具，无需手写 JSON 解析。

---

## 变更清单

### 新增文件

| 文件 | 说明 |
|------|------|
| `config/LangChain4jConfig.java` | `OpenAiChatModel` Bean 配置，指向 DeepSeek API |
| `tool/LangChain4jTools.java` | 6 个 `@Tool` 注解方法，自动生成 JSON Schema 给 LLM |
| `workflow/LangChain4jReActEngine.java` | 新 Agent 引擎，~380 行 |

### 修改文件

| 文件 | 变更 |
|------|------|
| `pom.xml` | 新增 `langchain4j-bom` (1.0.0-rc1) + `langchain4j-open-ai` 依赖 |
| `application.yml` | 新增 `langchain4j.open-ai.chat-model.*` 配置块 |
| `workflow/AgentWorkflowEngine.java` | 字段类型 `ReActEngine` → `LangChain4jReActEngine` |
| `workflow/ConsultantEngine.java` | 同上 |
| `service/AgentService.java` | 同上 |
| `prompts/system-prompt.txt` | 109行 → 57行，删除手写工具定义和 JSON 格式说明 |

### 删除文件

| 文件 | 原因 |
|------|------|
| `workflow/ReActEngine.java` | 被 `LangChain4jReActEngine` 替代 |
| `context/ContextLedger.java` | 被 LangChain4j `ChatMemory` 替代 |
| `prompts/replan-prompt.txt` | 代码中从未引用，死文件 |

### 不改的文件

`FastPlanEngine`、`PlanEditorEngine`、`PlanPatchExtractor`、`ToolRegistry`、所有 Tool/Provider/DTO/Controller 保持原样。

---

## 架构变化

### 旧架构（ReActEngine）

```
while (step < maxSteps):
  1. chatModel.call(Prompt)        ← 纯文本，无 function calling
  2. brace-matching 提取 JSON       ← 手动解析 LLM 输出
  3. 读 "action" 字段 → 找工具名    ← 字符串匹配
  4. toolRegistry.execute(name, json) ← JSON 参数
  5. addObservation("Observation: " + result)
```

### 新架构（LangChain4jReActEngine）

```
while (step < maxSteps):
  1. OpenAiChatModel.chat(ChatRequest)
     → 请求自带 ToolSpecification（自动生成 JSON Schema）
  2. AiMessage 分析：
     a. hasToolExecutionRequests() → 执行工具 → ToolExecutionResultMessage 喂回
     b. 纯文本 → 检测 FINISH → 解析 summary/timeline
  3. 上下文通过 trimMessages() 控制窗口大小
```

### 关键改进

| 维度 | 旧 | 新 |
|------|----|----|
| LLM 调工具 | 猜工具名+参数拼 JSON | 标准 function calling 协议 |
| 工具定义 | system-prompt.txt 手写 | @Tool 注解自动生成 Schema |
| JSON 解析 | brace-matching 正则 | LangChain4j 内置 |
| 消息管理 | ContextLedger 手动 add | List<ChatMessage> + trimMessages |
| 上下文控制 | 无限制 | 最多 50 条消息 |
| 代码量 | ReActEngine 490行 | LangChain4jReActEngine ~380行 |

---

## 运行方式

```bash
cd backend
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

默认 `agent.mode=fast` 走 FastPlanEngine（确定性管线），不受此迁移影响。

如需启用新 Agent 推理：设置 `agent.mode=react`，路由将走 `LangChain4jReActEngine`。

---

## 依赖版本

| 依赖 | 版本 |
|------|------|
| LangChain4j BOM | 1.0.0-rc1 |
| langchain4j-open-ai | 1.0.0-rc1 |
| langchain4j-core | 1.0.0-rc1（传递） |
| DeepSeek Model | deepseek-v4-flash |

---

## 注意事项

1. **DeepSeek function calling** 已通过 OpenAI 兼容 API 验证，`strictTools=false` 确保兼容性
2. **SSE 事件格式** 保持不变——`START` / `THOUGHT` / `ACTION` / `OBSERVATION` / `FINISH`
3. **FastPlanEngine 零影响** — 确定性管线不经过 LangChain4j
4. **ToolRegistry 保留** — FastPlanEngine 仍通过它调用工具
5. **LangChain4jTools 独立** — 直接调用 Provider 接口，不经过 ToolRegistry
