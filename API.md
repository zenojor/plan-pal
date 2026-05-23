# Weekend Planner Agent API

**Base URL:** `http://localhost:8081`

## Health

```http
GET /api/v1/agent/health
```

## Plan Draft

```http
POST /api/v1/agent/plan
Content-Type: application/json
```

```json
{
  "userId": "U001",
  "prompt": "14:00-18:00，想看展，再找个近一点的烧烤"
}
```

返回的是可编辑草案，不会下单。

```json
{
  "planId": "bf834fc2",
  "userId": "U001",
  "status": "SUCCESS",
  "executionStatus": "PENDING_CONFIRMATION",
  "intent": {
    "headcount": 2,
    "startTime": "14:00",
    "endTime": "18:00",
    "requestedSegments": ["ACTIVITY", "DINING"],
    "dietaryConstraints": []
  },
  "timeline": [
    {
      "durationMinutes": 90,
      "startTime": "14:00",
      "endTime": "15:30",
      "phase": "ACTIVITY",
      "action": "城市艺术展览",
      "poiId": "P001",
      "poiName": "城市艺术展览中心",
      "bookingStatus": "待确认",
      "isTransit": false,
      "lnglat": [121.4702, 31.2298]
    },
    {
      "durationMinutes": 18,
      "startTime": "15:30",
      "endTime": "15:48",
      "phase": "TRANSIT",
      "action": "步行 18 分钟",
      "poiId": "",
      "poiName": "城市艺术展览中心 → 椒朋友川味烧烤",
      "bookingStatus": "无需预约",
      "isTransit": true,
      "transportMode": "步行",
      "distanceKm": 0.9,
      "fromPoiName": "城市艺术展览中心",
      "toPoiName": "椒朋友川味烧烤"
    },
    {
      "durationMinutes": 60,
      "startTime": "15:48",
      "endTime": "16:48",
      "phase": "DINING",
      "action": "近一点的川味烧烤",
      "poiId": "P021",
      "poiName": "椒朋友川味烧烤",
      "bookingStatus": "待确认",
      "isTransit": false,
      "orderIntentId": "OI-bf834fc2-2",
      "lnglat": [121.4748, 31.2288]
    },
    {
      "durationMinutes": 72,
      "startTime": "16:48",
      "endTime": "18:00",
      "phase": "LEISURE",
      "action": "自由缓冲 / 散步返程",
      "bookingStatus": "无需预约",
      "isTransit": false
    }
  ],
  "orderIntents": [
    {
      "orderIntentId": "OI-bf834fc2-2",
      "type": "RESERVE_TABLE",
      "poiId": "P021",
      "poiName": "椒朋友川味烧烤",
      "headcount": 2,
      "targetTime": "15:48",
      "status": "PENDING"
    }
  ],
  "orderGroupId": "",
  "notificationText": "方案已生成，确认后再下单。",
  "degradationNote": null
}
```

## Stream Draft

```http
GET /api/v1/agent/plan/stream?userId=U001&prompt=...
Accept: text/event-stream
```

事件顺序：

```text
START -> INTENT -> THOUGHT -> ACTION/OBSERVATION -> PLAN_STEP... -> FINISH
```

`PLAN_STEP` 携带累计 `timeline`，前端可逐个追加拼图。`INTENT`、`THOUGHT`、`PLAN_STEP` 在前端会转换成“与 PlanPal 对话”里的用户可读说明，不展示 raw JSON、tool params 或工程日志。`FINISH` 表示草案完成，不代表已下单。

## Confirm And Execute

点击“确认方案”时，前端先打开确认弹窗，展示下单人数、节点时间、地点、商品类型、预估价格和待执行动作。用户在弹窗中点击“确认下单”后，才调用确认接口。

```http
POST /api/v1/agent/plan/{planId}/confirm
Content-Type: application/json
```

```json
{
  "planId": "bf834fc2",
  "userId": "U001",
  "headcount": 2,
  "timeline": [],
  "notificationText": "方案已生成，确认后再下单。"
}
```

响应：

```json
{
  "orderGroupId": "G628",
  "status": "DISPATCHED",
  "executedOrders": ["R456"],
  "failedOrders": [],
  "notificationText": "已按 2 人执行下单。",
  "timeline": [
    {
      "phase": "DINING",
      "bookingStatus": "已下单",
      "executionStatus": "EXECUTED"
    }
  ]
}
```

## Timeline Contract

`timeline` 同时包含业务节点和交通节点。业务节点包括 `ACTIVITY`、`DINING`、`DRINKS`、`LEISURE`；交通节点使用 `phase=TRANSIT` 且 `isTransit=true`。

`durationMinutes` 对业务节点表示真实停留时长，对交通节点表示移动耗时。业务节点的时间跨度必须等于 `durationMinutes`，例如 `15:48-16:48` 必须展示为停留 60 分钟，不能把剩余空档硬塞进餐饮节点。

交通节点由后端生成，前端在拖拽业务节点或 PlanPal 局部改计划后会重新生成相邻交通拼图。交通节点需要展示 `transportMode`、`distanceKm`、`fromPoiName`、`toPoiName`，并使用比业务拼图更矮、颜色不同的 Animal Island 衔接条样式。
