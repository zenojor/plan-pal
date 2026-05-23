# Weekend Planner Agent Design

## 目标

比赛版本默认使用有界混合 Agent，在 30 秒内稳定生成“可编辑行程草案”。LLM 只做一次结构化意图抽取，后端 Planner 负责确定性工具编排、候选打分、可用性检查、时间线排布、交通拼图和降级；用户点击“确认方案”后，前端先展示确认弹窗，用户二次确认后后端才模拟订票、订座和通知。

## 意图理解

`IntentExtractor` 输出 `PlanIntent`：`headcount`、`participants`、`startTime`、`endTime`、`totalMinutes`、`sceneType`、`requestedSegments`、`dietaryConstraints`、`drinkPreference`、`locationScope`。

规则意图覆盖常见自然语言场景：吃辣、不能吃辣、烧烤、火锅、川菜、湘菜、夜宵、小龙虾、安静 bar、精酿、鸡尾酒、wine bar、club、livehouse、冰沙、果汁、甜品、咖啡、奶茶。

## Planning 策略

1. 解析意图：优先一次 LLM JSON 抽取，超时或失败立即规则兜底。
2. 固定停留时长：业务节点按真实停留时长生成 `startTime/endTime/durationMinutes`，不把剩余时间硬塞给餐饮或活动。
3. 交通衔接：两个不同 POI 的业务节点之间自动插入 `TRANSIT` 节点，记录步行/公交/地铁、耗时、距离、起终点。
4. 缓冲收尾：如果时间窗还有明显剩余，生成 `LEISURE` 缓冲节点，例如自由散步、返程建议或轻活动。
5. 分层搜索：强偏好标签、弱偏好标签、扩大半径、空标签兜底。
6. 候选打分：基于 `category`、`tags`、`distanceKm`、`recommendedDurationMinutes`、坐标和可用性，不依赖固定 Mock POI ID。
7. 有界检查：每类最多检查 `agent.fast.max-checks-per-category` 个候选，排队过长、售罄、未知状态自动跳过。
8. 降级返回：候选不足时返回 `DEGRADED` 和 `degradationNote`，仍输出可渲染节点，不熔断。

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
- `agent.intent.timeout-ms=2500`：LLM 意图抽取超时。
- `agent.llm-finalizer.enabled=false`：默认模板文案，避免二次 LLM 拖慢。
