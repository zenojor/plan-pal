# PlanPal

PlanPal is a weekend planning assistant monorepo with a React frontend and a Spring Boot backend.

## Layout

```text
plan-pal/
  frontend/   React + Vite + TypeScript UI
  backend/    Spring Boot + Spring AI runtime
  docs/       API, handoff, and architecture notes
  scratch/    local scratch space
```

## Requirements

- Node.js 20+
- pnpm 9+
- Java 17+
- Maven 3.9+

## Install

```bash
pnpm install
```

## Run

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

Default backend URL: `http://localhost:8081`

## Workspace Commands

```bash
pnpm dev:frontend
pnpm build:frontend
pnpm lint:frontend
pnpm test:backend
pnpm dev:backend
pnpm build
```

`pnpm build` runs the frontend build and backend tests.

## Runtime Notes

- `POST /api/v1/agent/plan` creates a draft plan.
- `GET /api/v1/agent/plan/stream` streams plan generation over SSE.
- `GET /api/v1/agent/plan/{planId}/chat/stream` handles follow-up chat and patch turns.
- `POST /api/v1/agent/plan/{planId}/confirm` executes the confirmed timeline.
- Normal first-turn planning returns a `PLAN_CHOICE` / `OPTIONS_READY` decision state before generating the executable timeline.
- SSE streams can include `BACKEND_NOTICE` diagnostic events in addition to planning progress and terminal `FINISH` / `ERROR` events.

Backend runtime state is in memory:

- `PlanExecutionStore` stores drafts and versions.
- `SessionStateStore` stores pending actions, candidate sets, and recent events.

## Environment

Put local secrets in `.env.local` at the repository root.

Important backend variables:

- `DEEPSEEK_API_KEY`
- `DEEPSEEK_BASE_URL`
- `DEEPSEEK_MODEL`

The frontend can override the API base URL with `VITE_API_BASE_URL`.

## Docs

- [API](docs/API.md)
- [Handoff](docs/HANDOFF.md)
- [Interaction routing architecture](docs/INTERACTION_ROUTING_ARCHITECTURE.md)
