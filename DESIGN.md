# Weekend Planner Agent Design

## 目标

比赛版本默认使用有界混合 Agent，在 30 秒内稳定生成“可编辑行程草案”。LLM 只做一次结构化意图抽取，后端 Planner 负责确定性工具编排、候选打分、可用性检查、时间线排布、交通拼图和降级；用户点击“确认方案”后，前端先展示确认弹窗，用户二次确认后后端才模拟订票、订座和通知。

## 意图理解

`IntentExtractor` 输出 `PlanIntent`：`headcount`、`participants`、`startTime`、`endTime`、`totalMinutes`、`sceneType`、`requestedSegments`、`dietaryConstraints`、`drinkPreference`、`locationScope`。

规则意图覆盖常见自然语言场景：吃辣、不能吃辣、烧烤、火锅、川菜、湘菜、夜宵、小龙虾、安静 bar、精酿、鸡尾酒、wine bar、club、livehouse、冰沙、果汁、甜品、咖啡、奶茶。

## Planning 策略

1. **解析意图 (LLM-First + 本地 Validator 校验兜底)**：
   - 彻底升级为 **LLM-First** 主导架构，优先发起异步并发的 LLM `PlanIntent` 结构化提取与微调合并提取（`mergeByLlm`），并在 Schema 中注入极强且对齐人类语义的 `sceneType` 场景分类指引（如区分 `DATE` 浪漫情侣约会与 `SOCIAL` 朋友/老战友社交，从而在底层避免了儿童馆误推荐 Bug）。
   - 若 LLM 发生网络超时（2.5秒）或解析异常，立即启用规则提取（`extractByRules`/`mergeByRules`）进行降级兜底。
   - 所有意图实体均必须经过 **`IntentValidator` 本地校验层**，负责对人数、起止时间段、sceneType 分类、非合规/越界 segments 业务项等进行强制结构规约、合规修复与自适应纠错。
2. **物理线程池隔离 (根治线程饥饿死锁)**：
   - 在 `IntentExtractor` 中引入专属的高空闲守护线程池 `llm-intent-extractor-pool`，专门承载 LLM 抽取与意图微调合并任务。
   - 使其与默认的 `ForkJoinPool.commonPool()` 逻辑物理双重隔离，彻底根除了嵌套 `CompletableFuture.get()` 带来的 **线程池饥饿死锁 (Starvation Deadlock)** 致命漏洞，即使遭遇网络堵塞也能 100% 秒级降级并流畅响应。
3. 固定停留时长：业务节点按真实停留时长生成 `startTime/endTime/durationMinutes`，不把剩余时间硬塞给餐饮或活动。
4. 交通衔接：两个不同 POI 的业务节点之间自动插入 `TRANSIT` 节点，记录步行/公交/地铁、耗时、距离、起终点。
5. 分层搜索：强偏好标签、弱偏好标签、扩大半径、空标签兜底。
6. 候选打分：基于 `category`、`tags`、`distanceKm`、`recommendedDurationMinutes`、坐标和可用性，并引入针对 `DATE` 等全新场景的分级加分与特定排队过滤。
7. 有界检查：每类最多检查 `agent.fast.max-checks-per-category` 个候选，排队过长、售罄、未知状态自动跳过。
8. 准入过滤：在 `isAllowedForIntent` 中排除成人专属商户（除非 `SOCIAL` / `DATE` 场景且无儿童），且当 `SOCIAL` / `DATE` 场景无儿童时，**通过 `isChildOnlyVenue` 强制排除纯儿童游乐场所**，防止情侣或老战友聚会被引流到儿童乐园。
9. 降级返回：候选不足时返回 `DEGRADED` 和 `degradationNote`，仍输出可渲染节点，不熔断。

## 工具链路

规划阶段固定链路：

```text
searchNearby -> checkAvailability -> buildTimelineWithTransit -> orderIntents
```

确认阶段链路：

```text
Confirm modal -> POST /api/v1/agent/plan/{planId}/confirm -> bookTickets / reserveRestaurant -> executeOrderAndNotify
```

规划阶段 `orderGroupId` 为空，`executionStatus=PENDING_CONFIRMATION`，拼图状态显示“待确认/无需预约”。确认成功后才更新为“已下单/已预约/已通知”，并返回 `orderGroupId`。

## 流式输出与对话列

`GET /api/v1/agent/plan/stream` 事件顺序：

```text
START -> INTENT -> THOUGHT -> ACTION / OBSERVATION -> PLAN_STEP ... -> FINISH
```

前端提交后清空拼图栏。每收到一个 `PLAN_STEP`，用累计 `timeline` 渲染拼图。`INTENT`、`THOUGHT` 和 `PLAN_STEP` 同步映射成“与 PlanPal 对话”里的可读说明，例如“我先锁定吃饭节点，按近一点和烧烤重新筛选”。对话列不展示 raw JSON、tool params、OBSERVATION 或工程日志。

对话列支持规则化局部改计划：用户输入“吃饭太远，换个近一点的烧烤”会识别目标节点为 `DINING`，偏好为 `bbq + nearer`，只替换相关节点并重算相邻交通，不推倒重来整份行程。

## 交通

后端 timeline 输出 `TRANSIT` 节点。交通字段包括 `isTransit`、`transportMode`、`distanceKm`、`fromPoiName`、`toPoiName`。

前端展示交通拼图时使用更矮的浅蓝/薄荷衔接条，和普通业务拼图颜色明显区分。拖拽只作用于业务节点；排序变化后，前端按当前业务节点坐标重算相邻交通拼图和时间。

## UI 约束

界面遵守 Animal Island 风格：奶油米白背景、温暖棕色文字、薄荷青绿主色、大圆角、厚底 3D 阴影、hover 上浮、active 下压。确认弹窗也使用 Animal Island 的温暖样式，不使用冷灰、纯黑或直角面板。

## 配置

- `agent.mode=fast`：默认有界混合 Agent。
- `agent.fast.deadline-ms=25000`：后端总预算。
- `agent.fast.max-checks-per-category=3`：每类候选检查上限。
- `agent.intent.llm-enabled=true`：启用一次结构化意图抽取。
- `agent.intent.timeout-ms=30000`：LLM 意图抽取超时。
- `agent.llm-finalizer.enabled=false`：默认模板文案，避免二次 LLM 拖慢。
