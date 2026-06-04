# PlanPal

PlanPal is a weekend planning assistant. The repository is organized as a single monorepo with separate frontend and backend projects.

## Project Layout

```text
plan-pal/
  frontend/   React + Vite + TypeScript client
  backend/    Spring Boot + Spring AI service
  docs/       API, design, handoff, and architecture notes
  scratch/    local scratch space
```

## Requirements

- Node.js 20+
- pnpm 9+
- Java 17+
- Maven 3.9+

## Quick Start

Install workspace dependencies:

```bash
pnpm install
```

The pnpm workspace lockfile lives at the repository root.

Run the backend on port `8081`:

```bash
cd backend
mvn spring-boot:run
```

Run the frontend on port `5173`:

```bash
cd frontend
pnpm dev
```

The frontend API base URL still defaults to `http://localhost:8081`. Override it with `VITE_API_BASE_URL` when needed.

## Root Commands

From the repository root:

```bash
pnpm dev:frontend
pnpm build:frontend
pnpm lint:frontend
pnpm test:backend
pnpm dev:backend
pnpm build
```

`pnpm build` runs the frontend production build and backend tests.

## Environment

Keep local secrets in `.env.local` at the repository root. The backend loads `.env.local` and `.env` from both the current working directory and the parent directory, so it works when started from either the repo root or `backend/`.

Committed environment examples live in `.env.example`.

Important backend variables:

- `DEEPSEEK_API_KEY`
- `DEEPSEEK_BASE_URL`
- `DEEPSEEK_MODEL`

## Documentation

- [API](docs/API.md)
- [Handoff](docs/HANDOFF.md)
- [Interaction routing architecture](docs/INTERACTION_ROUTING_ARCHITECTURE.md)
