from typing import Any, Dict
from uuid import uuid4

from app.agent.ai_planner import draft_plan_with_deepseek
from app.agent.state import AgentRun, AgentState, RunStatus
from app.tools.local_tools import (
    check_availability,
    compose_plan,
    create_execution_actions,
    estimate_routes,
    parse_user_goal,
    search_activities,
    search_restaurants,
)

try:
    from langgraph.graph import END, StateGraph
except Exception:  # pragma: no cover - keeps core logic importable without optional deps.
    END = None
    StateGraph = None


def parse_goal(state: AgentState) -> AgentState:
    request, record = parse_user_goal(state["input"])
    state["request"] = request
    state["tool_calls"].append(record)
    state["status"] = RunStatus.NEEDS_MORE_INFO if request.missing_fields else RunStatus.PLANNING
    return state


def find_activities(state: AgentState) -> AgentState:
    activities, record = search_activities(state["request"])
    state["activities"] = activities
    state["tool_calls"].append(record)
    return state


def find_restaurants(state: AgentState) -> AgentState:
    restaurants, record = search_restaurants(state["request"])
    state["restaurants"] = restaurants
    state["tool_calls"].append(record)
    return state


def check_tools_availability(state: AgentState) -> AgentState:
    activities, restaurants, record = check_availability(state["activities"], state["restaurants"])
    state["activities"] = activities
    state["restaurants"] = restaurants
    state["tool_calls"].append(record)
    return state


def handle_failure(state: AgentState) -> AgentState:
    if not state.get("activities") or not state.get("restaurants"):
        state["status"] = RunStatus.FAILED
        state["message"] = "没有找到可恢复的活动或餐厅备选。"
    return state


def compose_plan_node(state: AgentState) -> AgentState:
    ai_nodes, ai_record = draft_plan_with_deepseek(state["request"], state["activities"], state["restaurants"])
    state["tool_calls"].append(ai_record)
    nodes, profiles, record = compose_plan(state["request"], state["activities"], state["restaurants"], ai_nodes)
    state["plan_nodes"] = nodes
    state["merchant_profiles"] = profiles
    state["tool_calls"].append(record)
    return state


def estimate_route_node(state: AgentState) -> AgentState:
    segments, record = estimate_routes(state["plan_nodes"])
    state["route_segments"] = segments
    state["tool_calls"].append(record)
    return state


def create_actions_node(state: AgentState) -> AgentState:
    actions, record = create_execution_actions(state["run_id"], state["plan_nodes"])
    state["execution_actions"] = actions
    state["tool_calls"].append(record)
    state["status"] = RunStatus.READY
    state["message"] = "方案已生成，可以执行预约、订位和发送计划。"
    return state


def _after_parse(state: AgentState) -> str:
    return "stop" if state["status"] == RunStatus.NEEDS_MORE_INFO else "find_activities"


def _after_availability(state: AgentState) -> str:
    return "handle_failure" if not state.get("activities") or not state.get("restaurants") else "compose_plan"


def _build_graph():
    if StateGraph is None:
        return None
    graph = StateGraph(dict)
    graph.add_node("parse_goal", parse_goal)
    graph.add_node("find_activities", find_activities)
    graph.add_node("find_restaurants", find_restaurants)
    graph.add_node("check_availability", check_tools_availability)
    graph.add_node("handle_failure", handle_failure)
    graph.add_node("compose_plan", compose_plan_node)
    graph.add_node("estimate_route", estimate_route_node)
    graph.add_node("create_actions", create_actions_node)

    graph.set_entry_point("parse_goal")
    graph.add_conditional_edges("parse_goal", _after_parse, {"find_activities": "find_activities", "stop": END})
    graph.add_edge("find_activities", "find_restaurants")
    graph.add_edge("find_restaurants", "check_availability")
    graph.add_conditional_edges(
        "check_availability",
        _after_availability,
        {"compose_plan": "compose_plan", "handle_failure": "handle_failure"},
    )
    graph.add_edge("handle_failure", END)
    graph.add_edge("compose_plan", "estimate_route")
    graph.add_edge("estimate_route", "create_actions")
    graph.add_edge("create_actions", END)
    return graph.compile()


AGENT_GRAPH = _build_graph()


def run_planning_agent(user_input: str) -> AgentRun:
    state = build_initial_state(user_input)
    if AGENT_GRAPH is not None:
        final_state: Dict[str, Any] = AGENT_GRAPH.invoke(state)
    else:
        final_state = parse_goal(state)
        if final_state["status"] != RunStatus.NEEDS_MORE_INFO:
            final_state = find_activities(final_state)
            final_state = find_restaurants(final_state)
            final_state = check_tools_availability(final_state)
            final_state = handle_failure(final_state)
            if final_state["status"] != RunStatus.FAILED:
                final_state = compose_plan_node(final_state)
                final_state = estimate_route_node(final_state)
                final_state = create_actions_node(final_state)
    if final_state.get("status") == RunStatus.NEEDS_MORE_INFO:
        final_state["message"] = "信息还不够，请补充出行时间、人数或场景。"
    return agent_run_from_state(final_state)


def build_initial_state(user_input: str) -> AgentState:
    return {
        "run_id": str(uuid4()),
        "input": user_input,
        "status": RunStatus.THINKING,
        "request": None,
        "activities": [],
        "restaurants": [],
        "plan_nodes": [],
        "merchant_profiles": {},
        "route_segments": [],
        "tool_calls": [],
        "execution_actions": [],
        "message": "",
    }


def agent_run_from_state(state: AgentState) -> AgentRun:
    return AgentRun(
        run_id=state["run_id"],
        status=state["status"],
        input=state["input"],
        request=state.get("request"),
        plan_nodes=state.get("plan_nodes", []),
        merchant_profiles=state.get("merchant_profiles", {}),
        route_segments=state.get("route_segments", []),
        tool_calls=state.get("tool_calls", []),
        execution_actions=state.get("execution_actions", []),
        message=state.get("message", ""),
    )
