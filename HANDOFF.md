# 前端联调文档 — Weekend Planner Agent

> **这是前后端之间的唯一沟通文档。每次后端更新，在此文档末尾追加更新记录。**

**仓库:** https://github.com/zenojor/plan-pal.git
**后端端口:** 8081 | **前端端口:** 5173

---

## 零、项目结构（v5 重组）

```text
weekend-planner-backend/
├── README.md
├── HANDOFF.md              ← 当前文档（前后端沟通唯一入口）
├── backend/                ← 后端代码（Spring Boot + Maven）
│   ├── pom.xml
│   └── src/
├── frontend/               ← 前端代码（React + Vite + pnpm）
│   ├── package.json
│   ├── vite.config.ts
│   └── src/
└── docs/
    ├── test.http
    └── competition/        ← 比赛文档
```

### 各端启动方式（目录已经变更！）

```bash
# 后端（在 backend/ 目录下运行）
cd backend
$env:DEEPSEEK_API_KEY = “sk-你的key”
mvn spring-boot:run
# 验证: curl http://localhost:8081/api/v1/agent/health

# 前端（在 frontend/ 目录下运行）
cd frontend
pnpm install   # 首次
pnpm dev
# → http://localhost:5173
```

---

## 一、启动后端

```bash
# Windows PowerShell
$env:DEEPSEEK_API_KEY = "sk-你的key"
mvn spring-boot:run

# 验证
curl http://localhost:8081/api/v1/agent/health
# → "Agent is running"
```

---

## 二、接口

### POST /api/v1/agent/plan（同步，调试用）

**请求：**

```json
{
  "userId": "U001",
  "prompt": "周末带老婆和5岁孩子出去玩半天，下午2点出发，找亲子活动和轻食餐厅"
}
```

**响应：**

```json
{
  "planId": "7cab3549",
  "userId": "U001",
  "status": "SUCCESS",
  "summary": "下午2点出发，先前往星海儿童探索馆体验亲子科学互动...",
  "timeline": [
    {
      "durationMinutes": 90,
      "phase": "ACTIVITY",
      "action": "亲子科学互动探索",
      "poiId": "P008",
      "poiName": "星海儿童探索馆",
      "bookingStatus": "已确认",
      "note": "室内科学探索，无需排队，已购票3张共240元",
      "lnglat": [121.478, 31.218],
      "audience": "亲子家庭",
      "reason": "儿童友好、零排队、室内不受天气影响",
      "budget": "门票80元/人"
    },
    {
      "durationMinutes": 80,
      "phase": "DINING",
      "action": "素食轻食晚餐",
      "poiId": "P010",
      "poiName": "蔬心素食坊",
      "bookingStatus": "待确认",
      "note": "安静素食餐厅，建议提前确认座位",
      "lnglat": [121.465, 31.228],
      "audience": "亲子家庭",
      "reason": "轻食健康、环境安静适合家庭",
      "budget": "预计60-80元/人"
    }
  ],
  "trace": [
    { "step": 1, "type": "THOUGHT", "content": "用户是家庭出游..." },
    { "step": 2, "type": "ACTION", "content": "Tool: searchNearby, Params: {...}" },
    { "step": 3, "type": "OBSERVATION", "content": "{\"results\":[...]}" }
  ],
  "orderGroupId": "G764",
  "notificationText": "周末计划已安排好：14:00星海儿童探索馆...",
  "degradationNote": null
}
```

### GET /api/v1/agent/plan/stream（SSE，生产用）

```
GET /api/v1/agent/plan/stream?userId=U001&prompt=周末带老婆和5岁孩子出去玩
Accept: text/event-stream
```

**SSE 事件流：**

```text
event: START
data: {"type":"START","step":0,"content":"开始规划...","timeline":null}

event: THOUGHT
data: {"type":"THOUGHT","step":1,"content":"用户是家庭出游..." ,"timeline":null}

event: ACTION
data: {"type":"ACTION","step":2,"content":"searchNearby: {...}","timeline":null}

event: FINISH
data: {"type":"FINISH","step":19,"content":"下午2点出发...","timeline":[{...}]}
```

---

## 三、响应字段速查

### 顶层

| 字段 | 类型 | 说明 |
|------|------|------|
| `planId` | string | 规划唯一ID |
| `status` | `SUCCESS` \| `DEGRADED` | DEGRADED 时读 degradationNote |
| `summary` | string | 自然语言方案，直接渲染 |
| `timeline` | array | 见下方 |
| `trace` | array | 思考链，可折叠展示 |
| `orderGroupId` | string | 订单组号 |
| `notificationText` | string | 分享文案，一键复制 |
| `degradationNote` | string\|null | 非空时弹黄色 toast |

### timeline[N]

| 字段 | 类型 | 说明 |
|------|------|------|
| `durationMinutes` | int | **在该 POI 停留的分钟数** |
| `phase` | `ACTIVITY`\|`DINING`\|`LEISURE`\|`CINEMA`\|`SHOPPING`\|`HOTEL` | 阶段类型 |
| `action` | string | 活动标题 |
| `poiId` | string | POI 唯一标识 |
| `poiName` | string | 地点名称 |
| `bookingStatus` | string | 预订状态（空字符串 = 无需预订） |
| `note` | string | 备注 |
| `lnglat` | `[lng, lat]` \| null | **高德地图坐标**，null 表示无地点 |
| `audience` | string | 适合人群 |
| `reason` | string | 选择理由 |
| `budget` | string | 费用预估 |

### trace[N]

| 字段 | 类型 | 说明 |
|------|------|------|
| `step` | int | 步骤序号 |
| `type` | `THOUGHT`\|`ACTION`\|`OBSERVATION`\|`FINISH` | 步骤类型 |
| `content` | string | 步骤内容 |

---

## 四、关键约定

1. **timeline 不含交通步骤** — 前端根据 `durationMinutes` 自己反推时间窗口，调用高德地图 API 算路线和交通耗时
2. **lnglat 格式** — `[经度, 纬度]`，例如 `[121.478, 31.218]`，直接喂给高德 `LngLat` 构造函数
3. **phase 目前有六种** — `ACTIVITY`（活动）、`DINING`（餐饮）、`LEISURE`（轻度活动）、`CINEMA`（看电影）、`SHOPPING`（购物）、`HOTEL`（住宿）。没有 `TRANSIT`/`TRANSPORT`
4. **plan/stream 选择** — 开发调试用 POST plan 看完整响应；生产用 GET stream 展示逐步加载动画
5. **DEGRADED 状态** — timeline 正常渲染，同时弹出 `degradationNote` 文字提示用户方案有妥协

---

## 五、错误处理

| HTTP 状态码 | 含义 | 处理 |
|-------------|------|------|
| 200 | 成功 | 正常渲染 |
| 500 | 规划熔断/LLM 异常 | 提示"规划失败，请稍后重试" |

500 响应体：
```text
{ "status": 500, "message": "规划迭代超出最大步数上限(15)，触发安全熔断" }
```

---

## 六、前端接入速查

```typescript
// 类型定义（与 src/api/agent.ts 同步）
type AgentPlanStep = {
  durationMinutes: number
  phase: string
  action: string
  poiId: string
  poiName: string
  bookingStatus: string
  note: string
  lnglat: number[] | null   // [lng, lat]
  audience: string
  reason: string
  budget: string
}

type AgentPlanResponse = {
  planId: string
  userId: string
  status: "SUCCESS" | "DEGRADED"
  summary: string
  timeline: AgentPlanStep[]
  trace: { step: number; type: string; content: string }[]
  orderGroupId: string
  notificationText: string
  degradationNote: string | null
}

// SSE 接入
const url = `http://localhost:8081/api/v1/agent/plan/stream?userId=${uid}&prompt=${encodeURIComponent(p)}`
const es = new EventSource(url)
es.addEventListener("FINISH", e => {
  const data = JSON.parse(e.data)
  // data.timeline → 渲染时间轴
  // data.content → 渲染方案摘要
  es.close()
})
es.addEventListener("THOUGHT", e => {
  // 渲染 "正在思考..." 加载态
})
```

---

## 七、更新记录

| 日期 | 更新内容 |
|------|---------|
| 2026-05-25 | **v5**: 项目结构重组（backend/frontend 分离目录）；接入和风天气 API（FastPlanEngine 自动查天气，遇雨/高温优先室内方案；新增 WeatherTool 工具）；启动命令改为 `cd backend && mvn spring-boot:run` 和 `cd frontend && pnpm dev` |
| 2026-05-25 | **v4**: PlanningToolOrchestrator 编排器、CandidateScorer 候选评分系统、PoiProvider 接口抽象（支持 Amap 高德真实 POI 和 Sandbox 沙盒）、SearchTask/SearchTaskCompiler 搜索任务编译、AvailabilitySelection 可用性选择、TRANSIT 节点恢复 |
| 2026-05-23 | **v2**: 新增 CINEMA/HOTEL/SHOPPING 三类 POI（共 7 个新 POI）；新增 `searchMovies` 工具（查排片/场次/票價）；phase 新增 CINEMA/SHOPPING/HOTEL；ReAct 流水线优化（防死循环提示、去重检测、max-steps 提升到 20）；支持电影+火锅、逛街+晚餐等新场景 |
| 2026-05-23 | **v1**: `timeRange` → `durationMinutes`(int)；不再输出 TRANSIT/TRANSPORT 步骤；新增 `lnglat`/`audience`/`reason`/`budget` 字段；tool 参数支持顶层与嵌套两种 JSON 格式；端口确认为 8081 |
