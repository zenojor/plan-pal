# Weekend Planner Handoff

> This is the main working handoff between frontend and backend.

Repo: `E:/Coding/plan-pal`
Backend port: `8081`

## Local startup

Backend:

```bash
cd backend
mvn spring-boot:run
```

Frontend:

```bash
cd frontend
pnpm dev
```

Health check:

```bash
curl http://localhost:8081/api/v1/agent/health
```

Expected response:

```text
Agent is running
```

## What the app currently does

- `POST /api/v1/agent/plan` returns a decision-only draft for normal first-turn planning: three `PLAN_CHOICE` options, `executionStatus=OPTIONS_READY`, and an empty timeline.
- `GET /api/v1/agent/plan/stream` streams creation events over SSE. Normal first-turn planning should end with the same `PLAN_CHOICE` decision state.
- `GET /api/v1/agent/plan/{planId}/chat/stream` handles follow-up chat, selection, patching, and pending workflows.
- `POST /api/v1/agent/plan/{planId}/confirm` executes the confirmed timeline.

## Frontend contract

The frontend treats the plan as a timeline of nodes and keeps the confirm modal separate from the draft view.

Initial `OPTIONS_READY` / `PLAN_CHOICE` responses are not editable timelines. The frontend should render their action card in chat and keep the puzzle column empty. A puzzle timeline is created only after a `BUILD_PLAN` action-card selection goes through chat stream and returns a selected-plan response with timeline steps.

Important pieces:

- `frontend/src/App.tsx`
- `frontend/src/hooks/usePlanStream.ts`
- `frontend/src/hooks/useConfirmOrder.ts`
- `frontend/src/api/agent.ts`
- `frontend/src/components/PlanPalChatColumn.tsx`

## Backend contract

Important pieces:

- `backend/src/main/java/com/weekendplanner/controller/AgentController.java`
- `backend/src/main/java/com/weekendplanner/service/AgentService.java`
- `backend/src/main/java/com/weekendplanner/engine/graph/PlanPalGraphRuntime.java`
- `backend/src/main/java/com/weekendplanner/engine/workflow/WorkflowActionService.java`
- `backend/src/main/java/com/weekendplanner/engine/interaction/InteractionRouter.java`
- `backend/src/main/java/com/weekendplanner/engine/routing/AgentRouter.java`
- `backend/src/main/java/com/weekendplanner/engine/workflow/FastPlanEngine.java`
- `backend/src/main/java/com/weekendplanner/engine/patch/PlanEditorEngine.java`

## State model

- `PlanExecutionStore` keeps drafts and versions in memory.
- `SessionStateStore` keeps pending actions, candidate sets, and recent events in memory.
- Both stores are lost on restart.

## Verify after changes

Minimum checks:

```bash
pnpm lint:frontend
pnpm build:frontend
pnpm test:backend
```

If the change touches plan routing or chat behavior, also run the focused backend tests around:

- `AgentWorkflowEngineTest`
- `InteractionRouterPendingWorkflowTest`
- `PlanPalGraphRuntimeSmokeTest`

## Current notes

- `AgentWorkflowEngine` is still present, but it is a compatibility wrapper around `PlanPalGraphRuntime`.
- `PlanGraphConfig.enabled()` and `chatEnabled()` currently return `true`.
- `CHAT_ONLY` means the backend answered without mutating the draft.
- `SLOT_COLLECTION` and `PLAN_CHOICE` are backend-owned interaction states.
- `PLAN_CHOICE` is now the default gate before FastPlan for normal initial planning. `BUILD_SELECTED_PLAN` is the structured marker that bypasses the gate and allows FastPlan to generate the executable timeline.
