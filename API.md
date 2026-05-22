# Weekend Planner Agent API 文档

**Base URL:** `http://localhost:8080`

---

## 1. 健康检查

```
GET /api/v1/agent/health
```

**响应:**
```
Agent is running
```

---

## 2. 同步规划（推荐调试用）

```
POST /api/v1/agent/plan
Content-Type: application/json
```

### 请求体

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| userId | string | 是 | 用户标识，如 `"U001"` |
| prompt | string | 是 | 自然语言需求，≤500字 |

```json
{
  "userId": "U001",
  "prompt": "今天下午想带老婆孩子出去玩，别离家太远，帮我安排一下"
}
```

### 响应体

```json
{
  "planId": "82b34683",
  "userId": "U001",
  "status": "SUCCESS",
  "summary": "已为您安排好周末家庭活动：\n14:00-15:30 星海儿童探索馆...",
  "timeline": [
    { "timeRange": "14:00-16:00", "phase": "ACTIVITY", "poiName": "星海儿童探索馆", "poiId": "P008", "action": "游玩", "bookingStatus": "已确认", "note": "" },
    { "timeRange": "16:00-16:30", "phase": "TRANSIT", "poiName": "", "poiId": "", "action": "交通", "bookingStatus": "", "note": "" },
    { "timeRange": "16:30-18:30", "phase": "DINING", "poiName": "绿意轻食馆", "poiId": "P002", "action": "餐饮", "bookingStatus": "已确认", "note": "" },
    { "timeRange": "18:30-20:00", "phase": "EVENING", "poiName": "", "poiId": "", "action": "轻度活动/返程", "bookingStatus": "", "note": "" }
  ],
  "trace": [
    { "step": 1, "type": "ACTION", "content": "Tool: searchNearby, Params: {...}" },
    { "step": 2, "type": "OBSERVATION", "content": "{...} " },
    { "step": 19, "type": "FINISH", "content": "最终方案文本" }
  ],
  "orderGroupId": "G628",
  "notificationText": "搞定了，14:00出发。方案已安排好...",
  "degradationNote": null
}
```

### 字段说明

| 字段 | 类型 | 说明 |
|------|------|------|
| planId | string | 规划唯一标识 |
| status | string | `SUCCESS` 正常 / `DEGRADED` 降级 / `FAILED` 失败 |
| summary | string | 最终方案自然语言文本，前端可直接渲染 |
| timeline | array | 时间线步骤列表 |
| timeline[].phase | string | `ACTIVITY` / `TRANSIT` / `DINING` / `EVENING` |
| timeline[].bookingStatus | string | 预订状态，空字符串表示无需预订 |
| trace | array | ReAct 思考链，前端可展示推理过程 |
| trace[].type | string | `THOUGHT` / `ACTION` / `OBSERVATION` / `FINISH` |
| orderGroupId | string | 订单组号 |
| notificationText | string | 可复制发送给联系人的消息文本 |
| degradationNote | string\|null | 降级情况下的友好提示 |

### 错误响应

```json
{
  "timestamp": "2026-05-22T17:43:27",
  "status": 500,
  "error": "Internal Server Error",
  "message": "规划迭代超出最大步数上限(15)，触发安全熔断"
}
```

| status | 说明 |
|--------|------|
| 400 | 请求参数校验失败 |
| 500 | 规划异常（超时/熔断/LLM错误） |

---

## 3. SSE 流式规划（推荐前端用）

```
GET /api/v1/agent/plan/stream?userId=U001&prompt=今天下午想带老婆孩子出去玩...
Accept: text/event-stream
```

### 请求参数（Query String）

| 参数 | 必填 | 说明 |
|------|------|------|
| userId | 是 | 用户标识 |
| prompt | 是 | 自然语言需求 |

### SSE 事件格式

每个事件的 `event` 字段为类型名，`data` 为 JSON：

```
event: START
data: {"type":"START","step":0,"content":"开始规划...","timeline":null}

event: THOUGHT
data: {"type":"THOUGHT","step":1,"content":"先搜索附近亲子活动...","timeline":null}

event: ACTION
data: {"type":"ACTION","step":2,"content":"Tool: searchNearby, Params: {...}","timeline":null}

event: OBSERVATION
data: {"type":"OBSERVATION","step":3,"content":"{\"results\":[...]}","timeline":null}

event: FINISH
data: {"type":"FINISH","step":19,"content":"完整方案","timeline":[...]}
```

### 前端接入示例

```javascript
const url = '/api/v1/agent/plan/stream'
  + '?userId=U001'
  + '&prompt=' + encodeURIComponent('今天下午想带老婆孩子出去玩');

const es = new EventSource(url);

es.addEventListener('START', e => {
  console.log('开始', JSON.parse(e.data));
});
es.addEventListener('THOUGHT', e => {
  renderThought(JSON.parse(e.data));
});
es.addEventListener('ACTION', e => {
  renderAction(JSON.parse(e.data));
});
es.addEventListener('OBSERVATION', e => {
  renderObservation(JSON.parse(e.data));
});
es.addEventListener('FINISH', e => {
  const data = JSON.parse(e.data);
  renderTimeline(data.timeline);
  renderSummary(data.content);
  es.close();
});
es.addEventListener('ERROR', e => {
  showError(JSON.parse(e.data).content);
  es.close();
});
```

---

## 4. 测试用例

### 场景A：家庭周末休闲
```bash
curl -X POST http://localhost:8080/api/v1/agent/plan \
  -H "Content-Type: application/json" \
  -d '{"userId":"U001","prompt":"今天下午想带老婆孩子出去玩，别离家太远，帮我安排一下"}'
```

### 场景B：朋友社交聚会
```bash
curl -X POST http://localhost:8080/api/v1/agent/plan \
  -H "Content-Type: application/json" \
  -d '{"userId":"U002","prompt":"今天下午想和朋友出去玩，4个人，帮我安排一下"}'
```
