# PlanPal 交互路由架构

> Current runtime: PlanPal uses Spring AI Alibaba Graph Core as the workflow orchestration boundary,
> Spring AI/Spring AI Alibaba `ToolCallback` integration through `ToolCatalog` / `ToolRunner`,
> and `ContextPack` as the canonical context source. The legacy reasoning runtime is not part of the active path.
>
> Runtime ownership update: `PlanPalGraphRuntime` now owns the create-plan graph and the optional chat graph.
> `AgentWorkflowEngine` is a compatibility facade; reusable business actions live in `WorkflowActionService`.
> `agent.graph.enabled=false` falls back to the facade path, and `agent.graph.chat-enabled=true` routes chat turns
> through the graph state machine. Existing HTTP endpoints, SSE event types, timeline shape, and `TRANSIT`
> contract are unchanged.

本文说明 PlanPal 的交互路由、规划编排、二次对话分流和上下文状态链路。

PlanPal 的运行结构：

```text
LLM intent router
+ workflow executor
+ plan generator/editor
+ contextual QA
+ tool providers
+ SSE-driven frontend
```

核心原则：

```text
二次对话先判断用户意图。
pending action、候选卡、当前 timeline、recent events 都是路由上下文。
```

## 1. 总览

```mermaid
flowchart LR
  User["User"]
  FE["Frontend React App"]
  Controller["AgentController"]
  Service["AgentService"]
  Workflow["AgentWorkflowEngine"]
  InitialRouter["InitialRequestRouter"]
  InteractionRouter["InteractionRouter"]
  AgentRouter["AgentRouter"]
  FastPlan["FastPlanEngine"]
  Editor["PlanEditorEngine"]
  QA["ConversationalQaService"]
  Store["PlanExecutionStore"]
  Session["SessionStateStore"]
  Tools["ToolCatalog / ToolRunner / Providers"]

  User --> FE
  FE --> Controller
  Controller --> Service
  Service --> Workflow

  Workflow --> InitialRouter
  Workflow --> InteractionRouter
  InteractionRouter --> QA
  InteractionRouter --> AgentRouter
  AgentRouter --> Editor
  InitialRouter --> FastPlan
  AgentRouter --> FastPlan

  FastPlan --> Tools
  Editor --> Tools
  Service --> Tools

  FastPlan --> Store
  Editor --> Store
  Store --> Workflow
  Workflow --> Session
  Session --> Workflow

  Workflow --> FE
```

| 模块 | 职责 |
| --- | --- |
| `frontend/src/App.tsx` | 管理页面阶段、拼图节点、聊天消息、SSE 事件、确认弹窗 |
| `frontend/src/api/agent.ts` | 封装 plan stream、chat stream、confirm API |
| `AgentController` | 暴露 REST/SSE 接口 |
| `AgentService` | 创建 SSE emitter，调用 workflow，执行 confirm 下单 |
| `AgentWorkflowEngine` | 统一编排首次规划、二次对话、候选卡、patch、QA |
| `InitialRequestRouter` | 首次输入路由 |
| `InteractionRouter` | 二次对话意图路由 |
| `AgentRouter` | workflow 内部命令路由 |
| `FastPlanEngine` | 生成完整 timeline |
| `PlanEditorEngine` | 应用 `PlanDelta` / `PlanPatch` 修改 timeline |
| `ConversationalQaService` | 流程内问答，只读上下文并保留当前卡片 |
| `PlanExecutionStore` | 保存 draft plan、version、status |
| `SessionStateStore` | 保存 pending action、last candidates、recent events |
| `ToolCatalog / ToolRunner / Providers` | 提供 POI、电影、可用性、订座、票务、打车、通知等能力 |

## 2. API 入口

| 场景 | 前端函数 | 后端接口 | 后端入口 |
| --- | --- | --- | --- |
| 首次生成计划 | `requestPlanStream` | `GET /api/v1/agent/plan/stream` | `AgentService.planStream` |
| 二次对话/改计划 | `requestPlanChatStream` | `GET /api/v1/agent/plan/{planId}/chat/stream` | `AgentService.planChatStream` |
| 确认执行 | `confirmPlan` | `POST /api/v1/agent/plan/{planId}/confirm` | `AgentService.confirmPlan` |

## 3. 首次规划链路

首次输入用于创建草稿计划，入口是 `requestPlanStream`。

```mermaid
sequenceDiagram
  participant U as User
  participant FE as Frontend
  participant API as AgentController
  participant S as AgentService
  participant W as AgentWorkflowEngine
  participant R as InitialRequestRouter
  participant FP as FastPlanEngine
  participant Store as PlanExecutionStore

  U->>FE: 输入出行需求
  FE->>API: GET /api/v1/agent/plan/stream
  API->>S: planStream(request)
  S->>W: createPlanStreaming(request, emitter)
  W->>R: route(prompt)

  alt CONSULT_CHAT
    W-->>FE: FINISH + consult response/actionCard
  else ASK_CLARIFICATION
    W-->>FE: FINISH + clarification question
  else RESEARCH_AND_RENDER
    W-->>FE: research events + FINISH
  else CREATE_PLAN
    W->>FP: executePlanStreaming()
    FP->>Store: save DraftPlan
    FP-->>FE: START/INTENT/ACTION/PLAN_STEP/FINISH
  end
```

首次规划输出：

- `planId`
- `intent`
- `timeline`
- `orderIntents`
- `summary`
- `notificationText`
- `version`
- `planStatus`
- `actionCard`，可选

## 4. 二次对话链路

二次对话用于处理已有 plan 上的追问、选择、修改、取消和新计划请求，入口是 `requestPlanChatStream`。

```mermaid
sequenceDiagram
  participant U as User
  participant FE as Frontend
  participant API as AgentController
  participant S as AgentService
  participant Store as PlanExecutionStore
  participant W as AgentWorkflowEngine
  participant Ctx as ContextAssembler
  participant Session as SessionStateStore
  participant IR as InteractionRouter
  participant QA as ConversationalQaService
  participant AR as AgentRouter
  participant Edit as PlanEditorEngine
  participant FP as FastPlanEngine

  U->>FE: 输入消息 / 点击卡片 / 拖拽拼图
  FE->>API: GET /api/v1/agent/plan/{planId}/chat/stream
  API->>S: planChatStream(...)
  S->>Store: find(planId)
  S->>W: executeChat(...)

  W->>Ctx: assemble(planId, userId, prompt, segmentId, source, clientActionId)
  Ctx->>Store: read draft
  Ctx->>Session: read pending/candidates/recentEvents
  Ctx-->>W: ContextPack view

  W->>IR: route(context, source, patchPayload)

  alt CONVERSATIONAL_QA
    W->>QA: answer(context)
    QA-->>W: answer + preserved actionCard
    W-->>FE: FINISH, timeline unchanged
  else CONTINUE_WORKFLOW
    W->>AR: route(context)
    AR-->>W: AgentCommand
    W->>Edit: apply selected candidate / continue workflow
    Edit->>Store: save next version
    W-->>FE: PLAN_STEP/FINISH
  else MODIFY_PLAN
    W->>AR: route(context) or use direct patch
    W->>Edit: applyDelta()
    Edit->>Store: save next version
    W-->>FE: PLAN_STEP/FINISH
  else START_NEW_PLAN
    W->>FP: executePlanStreaming(new request)
    FP->>Store: save DraftPlan
    FP-->>FE: new plan events
  else CANCEL_PENDING
    W->>Session: clearPending()
    W-->>FE: FINISH, timeline unchanged
  else SMALLTALK_HELP
    W->>QA: answer(context)
    W-->>FE: FINISH, timeline unchanged
  end
```

二次对话的上下文对象 `ContextPack view` 包含：

- 当前 `DraftPlan`
- 当前用户输入
- `segmentId`
- `source`
- `clientActionId`
- `pendingAction`
- `lastCandidates`
- `recentEvents`
- 当前用户约束

## 5. InteractionRouter

`InteractionRouter` 是二次对话的第一层路由。

```mermaid
flowchart TD
  Input["User turn"]
  Context["ContextPack view<br/>draft / pending / candidates / recent events"]
  Source["source + clientActionId + patchPayload"]
  Router["InteractionRouter"]
  Explicit{"Explicit UI action<br/>or structured patch"}
  LLM["LLM route"]
  Rules["Fallback rules"]
  Decision["InteractionDecision"]

  Input --> Context
  Source --> Context
  Context --> Router
  Router --> Explicit
  Explicit -- yes --> Decision
  Explicit -- no --> LLM
  LLM -- success --> Decision
  LLM -- fail/unavailable --> Rules
  Rules --> Decision

  Decision --> QA["CONVERSATIONAL_QA"]
  Decision --> Continue["CONTINUE_WORKFLOW"]
  Decision --> Modify["MODIFY_PLAN"]
  Decision --> NewPlan["START_NEW_PLAN"]
  Decision --> Cancel["CANCEL_PENDING"]
  Decision --> Help["SMALLTALK_HELP"]
```

| command | 典型输入 | 后续处理 |
| --- | --- | --- |
| `CONVERSATIONAL_QA` | “这几个有什么区别？”、“第二个适合聊天吗？”、“头孢能喝酒吗？” | `ConversationalQaService.answer` |
| `CONTINUE_WORKFLOW` | “就第二个”、“换一批”、“按这个偏好继续” | `AgentRouter` 继续候选/偏好/pending 流程 |
| `MODIFY_PLAN` | “把晚饭换成火锅”、“延长到十点”、“删掉酒吧” | `PlanPatchExtractor` / `PlanEditorEngine` |
| `START_NEW_PLAN` | “重新来，明天下午两个人看展吃饭” | `FastPlanEngine` 创建新草稿 |
| `CANCEL_PENDING` | “算了，不选这个了”、“取消当前候选” | `SessionStateStore.clearPending` |
| `SMALLTALK_HELP` | “你能做什么？”、“这个页面怎么用？” | `ConversationalQaService.answer` |

路由优先级：

1. 显式 UI action 或结构化 patch。
2. LLM route，用于自由文本。
3. fallback rules，用于 LLM 不可用或解析失败。

## 6. AgentRouter

`AgentRouter` 处理 workflow 内部命令。

```mermaid
flowchart TD
  Decision["InteractionDecision"]
  Continue["CONTINUE_WORKFLOW / MODIFY_PLAN"]
  AgentRouter["AgentRouter"]
  Select["APPLY_CANDIDATE_TO_PLAN"]
  Replace["REPLACE_SEGMENT_WITH_CANDIDATES"]
  Extend["EXTEND_PLAN_END_TIME"]
  Feedback["APPLY_FEEDBACK_PATCH"]
  Reason["PLAN_REASONING"]
  Direct["APPLY_DIRECT_PATCH"]

  Decision --> Continue
  Continue --> AgentRouter
  AgentRouter --> Select
  AgentRouter --> Replace
  AgentRouter --> Extend
  AgentRouter --> Feedback
  AgentRouter --> Reason
  Continue --> Direct
```

`AgentRouter` 关注的问题：

- 用户是否选择了候选 index
- 是否要求替换当前 segment
- 是否要求延长结束时间
- 是否需要 LLM reasoning fallback
- 是否应用自然语言反馈 patch

## 7. ConversationalQaService

`CONVERSATIONAL_QA` 用于流程内问答。

```mermaid
flowchart LR
  QA["CONVERSATIONAL_QA"]
  Read["Read context<br/>timeline / candidates / pending / recent events"]
  Answer["Generate answer"]
  Preserve["Preserve current actionCard"]
  Return["FINISH<br/>timeline unchanged"]

  QA --> Read
  Read --> Answer
  Answer --> Preserve
  Preserve --> Return
```

可读取：

- 当前 timeline
- 当前 pending action
- 最近候选集 `lastCandidates`
- 最近事件 `recentEvents`
- 当前 action card 相关信息

输出约束：

- 返回自然语言回答
- 可返回保留后的 action card
- 不应用 patch
- 不自动选择候选
- 不清除 pending
- 不更新 timeline
- 不声明订票、订座或下单完成

典型场景：

| 输入 | pending | 路由 |
| --- | --- | --- |
| “这几个有什么区别？” | `SELECT_CANDIDATE` | `CONVERSATIONAL_QA` |
| “第二个是不是太吵？” | `SELECT_CANDIDATE` | `CONVERSATIONAL_QA` |
| “头孢能喝酒吗？” | 任意 | `CONVERSATIONAL_QA` |
| “那就第二个” | `SELECT_CANDIDATE` | `CONTINUE_WORKFLOW` |
| “换一批” | `SELECT_CANDIDATE` | `CONTINUE_WORKFLOW` |
| “把晚饭换成火锅” | 任意 | `MODIFY_PLAN` |
| “取消这个候选” | `SELECT_CANDIDATE` | `CANCEL_PENDING` |

## 8. 状态与版本

```mermaid
flowchart TD
  Plan["DraftPlan"]
  Store["PlanExecutionStore"]
  Session["SessionStateStore"]
  Pending["PendingAction"]
  Candidates["LastCandidates"]
  Events["RecentEvents"]
  Version["version / previousVersionId"]
  Status["PlanStatus"]

  Plan --> Store
  Store --> Version
  Store --> Status
  Plan --> Session
  Session --> Pending
  Session --> Candidates
  Session --> Events
```

`PlanExecutionStore.DraftPlan` 保存：

- `planId`
- `userId`
- `intent`
- `timeline`
- `orderIntents`
- `notificationText`
- `version`
- `previousVersionId`
- `status`
- `lastConfirmedVersion`
- `idempotencyKey`
- `updatedAt`

`SessionStateStore` 保存交互状态：

- 当前 pending action
- 最近候选集
- 用户约束
- recent events

## 9. 确认执行链路

确认执行由前端确认弹窗触发。

```mermaid
sequenceDiagram
  participant FE as Frontend
  participant API as AgentController
  participant S as AgentService
  participant Store as PlanExecutionStore
  participant Tools as ToolCatalog / ToolRunner

  FE->>API: POST /api/v1/agent/plan/{planId}/confirm
  API->>S: confirmPlan(planId, request)
  S->>Store: find draft
  S->>S: validate version / idempotencyKey
  S->>S: build OrderIntent from submitted timeline
  S->>Tools: bookTickets / reserveRestaurant / hailRide
  S->>Tools: executeOrderAndNotify
  S->>Store: save confirmed/failed status
  S-->>FE: ConfirmPlanResponse
```

确认阶段会使用前端提交的当前 timeline。这样地图交通选择、拖拽排序和本地确认人数都能进入最终执行请求。

## 10. 后续扩展：只读工具

当前 `ConversationalQaService` 主要读取上下文。后续可以接入只读工具，增强流程内问答。

```mermaid
flowchart TD
  QA["ConversationalQaService"]
  NeedTool{"Need read-only data"}
  Candidate["Candidate metadata"]
  Route["Route estimate"]
  Hours["Business hours"]
  Movie["Movie listing"]
  Safety["Safety guidance"]
  Final["Answer<br/>no patch"]

  QA --> NeedTool
  NeedTool -- no --> Final
  NeedTool -- yes --> Candidate
  NeedTool -- yes --> Route
  NeedTool -- yes --> Hours
  NeedTool -- yes --> Movie
  NeedTool -- yes --> Safety
  Candidate --> Final
  Route --> Final
  Hours --> Final
  Movie --> Final
  Safety --> Final
```

建议能力：

| 工具 | 用途 |
| --- | --- |
| `CandidateExplainTool` | 解释候选差异、适合人群、优缺点 |
| `RouteEstimateReader` | 查询距离、交通耗时、步行压力 |
| `BusinessHoursReader` | 查询营业时间、闭店风险 |
| `MovieListingReader` | 查询电影时长、场次、结束时间 |
| `SafetyAdviceReader` | 提供保守安全建议 |

只读工具的约束：

```text
可以读取上下文和外部信息。
不写 timeline。
不提交订单。
不替用户选择。
```

## 11. 关键文件

| 文件 | 说明 |
| --- | --- |
| `frontend/src/App.tsx` | 前端状态、SSE 消费、二次对话提交、确认弹窗 |
| `frontend/src/api/agent.ts` | API 封装和 SSE event 处理 |
| `backend/src/main/java/com/weekendplanner/controller/AgentController.java` | HTTP/SSE controller |
| `backend/src/main/java/com/weekendplanner/service/AgentService.java` | service 编排和 confirm 执行 |
| `backend/src/main/java/com/weekendplanner/engine/workflow/AgentWorkflowEngine.java` | 核心 workflow |
| `backend/src/main/java/com/weekendplanner/engine/interaction/InteractionRouter.java` | 二次对话路由 |
| `backend/src/main/java/com/weekendplanner/engine/interaction/ConversationalQaService.java` | 流程内问答 |
| `backend/src/main/java/com/weekendplanner/engine/routing/AgentRouter.java` | workflow 命令路由 |
| `backend/src/main/java/com/weekendplanner/engine/workflow/FastPlanEngine.java` | 快速规划 |
| `backend/src/main/java/com/weekendplanner/engine/patch/PlanEditorEngine.java` | 修改计划 |
| `backend/src/main/java/com/weekendplanner/engine/runtime/PlanExecutionStore.java` | 草稿和版本 |

## Current Action Card Contract

- `CHAT_ONLY` responses are read-only answers and must not become an active editable draft on the frontend.
- `FINISH + actionCard + timeline` means the timeline is visible but a user decision is still pending; the frontend must preserve the card instead of replacing it with a generic completion message.
- `SLOT_COLLECTION` is backend-owned. The frontend renders the provided option groups and must not infer missing fields from chat text.
- `QUEUE_REPAIR` and `REPLACEMENT_FALLBACK` are workflow states, not decoration. They should remain visible until the user selects a repair, replacement, or fallback action.
- `PRODUCT_RESEARCH` renders product/merchant candidates first; selecting one converts it to a normal plan patch for the linked POI.
