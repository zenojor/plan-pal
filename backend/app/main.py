import json
import time
from typing import Dict, Iterable
from uuid import uuid4

from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import StreamingResponse

from app.agent.ai_planner import draft_plan_with_deepseek
from app.agent.graph import run_planning_agent
from app.agent.state import AgentRun, ExecutionAction, PlanningInput, RunStatus
from app.tools.local_tools import (
    check_availability,
    compose_plan,
    create_execution_actions,
    estimate_routes,
    execute_mock_action,
    get_mock_data_summary,
    parse_user_goal,
    search_activities,
    search_restaurants,
)

app = FastAPI(title="Plan Pal Agent API")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["http://localhost:5173", "http://127.0.0.1:5173"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

RUNS: Dict[str, AgentRun] = {}
ACTIONS: Dict[str, ExecutionAction] = {}


@app.get("/api/health")
def health():
    return {"ok": True}


@app.post("/api/agent/runs", response_model=AgentRun)
def create_agent_run(payload: PlanningInput):
    run = run_planning_agent(payload.input)
    RUNS[run.run_id] = run
    for action in run.execution_actions:
        ACTIONS[action.id] = action
    return run


@app.post("/api/agent/runs/stream")
def stream_agent_run(payload: PlanningInput):
    return StreamingResponse(
        _stream_agent_run(payload.input),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "Connection": "keep-alive",
            "X-Accel-Buffering": "no",
        },
    )


@app.get("/api/agent/runs/{run_id}", response_model=AgentRun)
def get_agent_run(run_id: str):
    run = RUNS.get(run_id)
    if run is None:
        raise HTTPException(status_code=404, detail="Agent run not found")
    return run


@app.post("/api/actions/{action_id}/execute", response_model=ExecutionAction)
def execute_action(action_id: str):
    action = ACTIONS.get(action_id)
    if action is None:
        raise HTTPException(status_code=404, detail="Action not found")
    updated = execute_mock_action(action)
    ACTIONS[action_id] = updated
    run = RUNS.get(updated.run_id)
    if run is not None:
        run.status = RunStatus.DONE
        run.execution_actions = [
            updated if item.id == action_id else item for item in run.execution_actions
        ]
        RUNS[run.run_id] = run
    return updated


@app.get("/api/mock-data")
def mock_data():
    return get_mock_data_summary()


def _sse(event: str, data: dict) -> str:
    return "event: %s\ndata: %s\n\n" % (event, json.dumps(data, ensure_ascii=False))


def _stream_agent_run(user_input: str) -> Iterable[str]:
    run = AgentRun(run_id=str(uuid4()), status=RunStatus.THINKING, input=user_input)
    try:
        yield _sse("run_started", run.model_dump(mode="json"))

        request, record = parse_user_goal(user_input)
        run.request = request
        run.tool_calls.append(record)
        yield _sse("tool_call", record.model_dump(mode="json"))
        if request.missing_fields:
            run.status = RunStatus.NEEDS_MORE_INFO
            run.message = "信息还不够，请补充出行时间、人数或场景。"
            RUNS[run.run_id] = run
            yield _sse("completed", run.model_dump(mode="json"))
            return

        run.status = RunStatus.PLANNING
        activities, record = search_activities(request)
        run.tool_calls.append(record)
        yield _sse("tool_call", record.model_dump(mode="json"))
        time.sleep(0.12)

        restaurants, record = search_restaurants(request)
        run.tool_calls.append(record)
        yield _sse("tool_call", record.model_dump(mode="json"))
        time.sleep(0.12)

        activities, restaurants, record = check_availability(activities, restaurants)
        run.tool_calls.append(record)
        yield _sse("tool_call", record.model_dump(mode="json"))
        time.sleep(0.12)

        ai_nodes, record = draft_plan_with_deepseek(request, activities, restaurants)
        run.tool_calls.append(record)
        yield _sse("tool_call", record.model_dump(mode="json"))

        nodes, profiles, record = compose_plan(request, activities, restaurants, ai_nodes)
        run.merchant_profiles = profiles
        run.tool_calls.append(record)
        yield _sse("tool_call", record.model_dump(mode="json"))

        for node in nodes:
            run.plan_nodes.append(node)
            yield _sse("plan_node", node.model_dump(mode="json"))
            time.sleep(0.18)

        route_segments, record = estimate_routes(run.plan_nodes)
        run.route_segments = route_segments
        run.tool_calls.append(record)
        yield _sse("tool_call", record.model_dump(mode="json"))
        yield _sse("route_segments", {"segments": [item.model_dump(mode="json") for item in route_segments]})

        actions, record = create_execution_actions(run.run_id, run.plan_nodes)
        run.execution_actions = actions
        run.tool_calls.append(record)
        for action in actions:
            ACTIONS[action.id] = action
        yield _sse("tool_call", record.model_dump(mode="json"))
        yield _sse("execution_actions", {"actions": [item.model_dump(mode="json") for item in actions]})

        run.status = RunStatus.READY
        run.message = "方案已生成，可以执行预约、订位和发送计划。"
        RUNS[run.run_id] = run
        yield _sse("completed", run.model_dump(mode="json"))
    except Exception as exc:
        run.status = RunStatus.FAILED
        run.message = str(exc)
        RUNS[run.run_id] = run
        yield _sse("error", {"message": str(exc)})
