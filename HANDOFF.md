# 前后端联调文档 - Weekend Planner Agent

> 这是前后端之间的沟通文档。每次后端契约变化，都在文末追加更新记录。

**仓库:** https://github.com/zenojor/plan-pal.git
**后端端口:** 8081

## 一、启动后端

```bash
# Windows PowerShell
$env:DEEPSEEK_API_KEY = "sk-你的key"
mvn spring-boot:run

# 验证
curl http://localhost:8081/api/v1/agent/health
```

## 二、接口

### POST /api/v1/agent/plan

同步规划接口，调试使用。返回可编辑草案，不会下单。

```json
{
  "userId": "U001",
  "prompt": "14:00-18:00，想看展，再找个近一点的烧烤"
}
```

### GET /api/v1/agent/plan/stream

生产前端推荐使用 SSE。

```http
GET /api/v1/agent/plan/stream?userId=U001&prompt=...
Accept: text/event-stream
```

事件流：

```text
START -> INTENT -> THOUGHT -> ACTION/OBSERVATION -> PLAN_STEP... -> FINISH
```

`PLAN_STEP` 携带累计 `timeline`，前端用它逐步渲染拼图。`INTENT`、`THOUGHT`、`PLAN_STEP` 可以映射到“与 PlanPal 对话”列，但不要把 raw JSON、tool params、OBSERVATION 或工程日志直接展示给用户。

### POST /api/v1/agent/plan/{planId}/confirm

点击“确认方案”后，前端必须先打开确认弹窗，展示人数和待下单商品。用户在弹窗里点击“确认下单”后，才调用此接口。

```json
{
  "planId": "bf834fc2",
  "userId": "U001",
  "headcount": 2,
  "timeline": [],
  "notificationText": "方案已生成，确认后再下单。"
}
```

后端按提交的当前 timeline 和人数模拟订票/订座/通知，返回 `orderGroupId`、`executedOrders`、`failedOrders` 和更新后的 `timeline`。如果存在失败项，前端保留弹窗并展示可重试状态，不把整份计划标成成功。

## 三、响应字段速查

### 顶层

| 字段 | 类型 | 说明 |
|------|------|------|
| `planId` | string | 规划唯一 ID |
| `status` | `SUCCESS` \| `DEGRADED` \| `FAILED` | 规划状态 |
| `executionStatus` | string | `PENDING_CONFIRMATION` / `EXECUTED` / `FAILED` |
| `intent` | object | 结构化意图，含人数和时间窗 |
| `timeline` | array | 业务节点、交通节点和缓冲节点 |
| `orderIntents` | array | 待确认执行的订票/订座意图 |
| `orderGroupId` | string | 确认前为空，确认后返回订单组号 |
| `notificationText` | string | 分享文案 |
| `degradationNote` | string \| null | 降级提示 |

### timeline[N]

| 字段 | 类型 | 说明 |
|------|------|------|
| `durationMinutes` | int | 业务节点表示停留时长；交通节点表示移动耗时 |
| `startTime` | string | 节点开始时间 |
| `endTime` | string | 节点结束时间 |
| `phase` | `ACTIVITY` \| `DINING` \| `DRINKS` \| `LEISURE` \| `TRANSIT` | 节点类型 |
| `action` | string | 节点标题 |
| `poiId` | string | POI 唯一标识，交通/缓冲可为空 |
| `poiName` | string | 地点名称或路线标题 |
| `bookingStatus` | string | 预订/下单状态 |
| `lnglat` | `[lng, lat]` \| null | 地图坐标 |
| `audience` | string | 适合人群 |
| `reason` | string | 选择理由 |
| `budget` | string | 费用预估 |
| `orderIntentId` | string | 可执行节点对应的下单意图 |
| `isTransit` | boolean | 是否交通节点 |
| `transportMode` | string | `步行` / `公交/地铁` / `地铁` |
| `distanceKm` | number | 交通距离 |
| `fromPoiName` | string | 交通起点 |
| `toPoiName` | string | 交通终点 |

## 四、关键约定

1. **业务时长不拉伸**：业务节点时间跨度必须等于 `durationMinutes`。例如吃饭 60 分钟必须展示为 60 分钟，不允许显示 `15:30-18:00`。
2. **timeline 含交通步骤**：不同 POI 的业务节点之间必须有 `TRANSIT`，并写清交通方式、分钟数、距离、起终点。
3. **拖拽只动业务节点**：交通节点不可拖拽；业务节点顺序变化后，前端重新生成相邻交通节点。
4. **规划阶段不下单**：`plan/stream` 返回草案与 `orderIntents`，`executionStatus=PENDING_CONFIRMATION`，`orderGroupId` 为空。
5. **确认弹窗后二次执行**：只有弹窗内点击“确认下单”才调用 confirm 接口。
6. **PlanPal 对话列面向用户**：展示“我正在帮你调整哪一块、为什么这么换”，不展示调试日志。
7. **局部改计划**：自然语言修改只替换相关节点并重算相邻交通，不推倒重来整份行程。

## 五、前端接入速查

```typescript
type AgentPlanStep = {
  durationMinutes: number
  startTime: string
  endTime: string
  phase: 'ACTIVITY' | 'DINING' | 'DRINKS' | 'LEISURE' | 'TRANSIT'
  action: string
  poiId: string
  poiName: string
  bookingStatus: string
  note: string
  lnglat: [number, number] | null
  audience: string
  reason: string
  budget: string
  orderIntentId?: string
  isTransit?: boolean
  transportMode?: string
  distanceKm?: number
  fromPoiName?: string
  toPoiName?: string
}
```

## 六、更新记录

| 日期 | 更新内容 |
|------|---------|
| 2026-05-23 | timeline 改为业务节点 + `TRANSIT` 交通节点 + 可选缓冲节点；新增 `isTransit`、`transportMode`、`distanceKm`、`fromPoiName`、`toPoiName` |
| 2026-05-23 | 新增“与 PlanPal 对话”列，支持规则化局部改计划并重算相邻交通 |
| 2026-05-23 | 点击确认方案先展示确认弹窗，二次确认后才调用 confirm 接口执行下单 |
| 2026-05-23 | 扩充 Mock POI，覆盖吃辣、不能吃辣、烧烤、火锅、夜宵、安静 bar、club、livehouse、冰沙/饮品等场景 |
