# PlanPal API

Base URL: `http://localhost:8081`

## Health

```http
GET /api/v1/agent/health
```

Response:

```text
Agent is running
```

## Create Draft

```http
POST /api/v1/agent/plan
Content-Type: application/json
```

Request:

```json
{
  "userId": "U001",
  "prompt": "14:00-18:00，想看展，再找个近一点的烧烤"
}
```

`planId` is optional on the request body. When present, the backend may use it to continue or merge intent from an existing draft.

Response: `PlanResponse`

Normal first-turn planning now returns a decision-only draft first: 3 plan directions in `actionCard`, an empty `timeline`, and `executionStatus=OPTIONS_READY`. The puzzle timeline is generated only after the user selects one direction, which sends a `BUILD_PLAN` action through the follow-up chat stream.

```json
{
  "planId": "bf834fc2",
  "userId": "U001",
  "status": "SUCCESS",
  "summary": "我先给你 3 个方向，选一个后再把对应地点放进拼图。",
  "timeline": [],
  "trace": [],
  "orderGroupId": "",
  "notificationText": "我先给你 3 个方向，选一个后再把对应地点放进拼图。",
  "degradationNote": null,
  "intent": {},
  "orderIntents": [],
  "executionStatus": "OPTIONS_READY",
  "version": 1,
  "planStatus": "PENDING_CONFIRMATION",
  "conflicts": [],
  "repairOptions": [],
  "weather": null,
  "variants": []
}
```

## Stream Draft

```http
GET /api/v1/agent/plan/stream?userId=U001&prompt=...
Accept: text/event-stream
```

This streams `SseEvent` objects.

Common event types:

- `START`
- `INTENT`
- `THOUGHT`
- `ACTION`
- `OBSERVATION`
- `PLAN_STEP`
- `PLAN_STARTED`
- `INTENT_EXTRACTED`
- `WEATHER_CHECKED`
- `CANDIDATES_SEARCHING`
- `CANDIDATES_FOUND`
- `AVAILABILITY_CHECKED`
- `SEGMENT_PLANNED`
- `CONFLICT_DETECTED`
- `REPAIR_OPTIONS_READY`
- `PLAN_ASSEMBLED`
- `PLAN_FINISHED`
- `PLAN_NARRATIVE`
- `FINISH`
- `ERROR`

`FINISH` carries the final `PlanResponse` shape in event form, including `timeline`, `planId`, `intent`, `orderIntents`, `actionCard`, `planPatch`, `planDelta`, `conflicts`, `repairOptions`, `version`, `planStatus`, `weather`, and `summary`.

Example finish payload:

```json
{
  "type": "FINISH",
  "step": 1,
  "content": "我先给你 3 个方向，选一个后再把对应地点放进拼图。",
  "planId": "bf834fc2",
  "status": "SUCCESS",
  "executionStatus": "OPTIONS_READY",
  "timeline": [],
  "actionCard": {
    "cardKind": "PLAN_CHOICE",
    "options": [
      {
        "actionType": "BUILD_PLAN",
        "optionKind": "PLAN_CHOICE",
        "prompt": "BUILD_PLAN:choice-1"
      }
    ]
  },
  "summary": "我先给你 3 个方向，选一个后再把对应地点放进拼图。"
}
```

## Follow-up Chat

```http
GET /api/v1/agent/plan/{planId}/chat/stream?userId=U001&prompt=...
Accept: text/event-stream
```

Optional query params:

- `segmentId`
- `source`
- `clientActionId`
- `patch` (JSON string)

This endpoint handles:

- read-only Q&A
- pending workflow continuation
- plan choice selection (`PLAN_CHOICE` plus `BUILD_PLAN:choice-N`)
- plan modification
- new plan restarts
- cancellation of pending actions

## Confirm And Execute

```http
POST /api/v1/agent/plan/{planId}/confirm
Content-Type: application/json
```

Request:

```json
{
  "planId": "bf834fc2",
  "userId": "U001",
  "timeline": [],
  "headcount": 2,
  "notificationText": "方案已生成，确认后再下单",
  "version": 1,
  "idempotencyKey": "confirm-bf834fc2-v1"
}
```

`version` and `idempotencyKey` are recommended. The backend still accepts missing values.

Response: `ConfirmPlanResponse`

```json
{
  "orderGroupId": "G628",
  "status": "DISPATCHED",
  "executedOrders": ["R456"],
  "failedOrders": [],
  "notificationText": "已按 2 人执行下单",
  "timeline": [],
  "version": 1,
  "planStatus": "CONFIRMED"
}
```

## Timeline Contract

- Business phases: `ACTIVITY`, `DINING`, `DRINKS`, `LEISURE`
- Transit phase: `TRANSIT` with `isTransit=true`
- `durationMinutes` is the actual stay time for business stops and travel time for transit stops
- `segmentId` is the stable key for editing and reordering
- `orderIntentId` links a step to a confirm-time external write action

## SSE Contract Notes

- `CHAT_ONLY` means read-only reply, no editable draft should be created.
- `OPTIONS_READY` with `actionCard.cardKind=PLAN_CHOICE` means render options in chat and keep the puzzle timeline empty.
- `BUILD_PLAN` action-card selections are sent through follow-up chat; only the resulting selected-plan response should populate the puzzle timeline.
- `FINISH + actionCard + timeline` means the draft is visible but the user still has a pending decision.
- `SLOT_COLLECTION` is backend-owned and should be rendered as provided.
- `QUEUE_REPAIR` and `PRODUCT_RESEARCH` are workflow states, not decorative UI labels.
