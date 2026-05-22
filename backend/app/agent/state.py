from enum import Enum
from typing import Any, Dict, List, Literal, Optional, Tuple

from pydantic import BaseModel, Field


class RunStatus(str, Enum):
    THINKING = "thinking"
    PLANNING = "planning"
    READY = "ready"
    EXECUTING = "executing"
    DONE = "done"
    FAILED = "failed"
    NEEDS_MORE_INFO = "needs_more_info"


class PlanningInput(BaseModel):
    input: str = Field(..., min_length=1)


class PlanningRequest(BaseModel):
    raw_input: str
    time_range: str = "下午 4-6 小时"
    party_size: int = 3
    scene: Literal["family", "friends"] = "family"
    child_age: Optional[int] = None
    preferences: List[str] = Field(default_factory=list)
    constraints: List[str] = Field(default_factory=list)
    missing_fields: List[str] = Field(default_factory=list)


class PlanNode(BaseModel):
    id: str
    time: str
    title: str
    place: str
    lnglat: Tuple[float, float]
    audience: str
    reason: str
    budget: str
    status: str
    details: str


class MerchantProfile(BaseModel):
    address: str
    queue: str
    booking: str
    hours: str
    contact: str
    tags: List[str]


class RouteSegment(BaseModel):
    from_place: str
    to_place: str
    walking_minutes: int
    transit_minutes: int
    driving_minutes: int
    distance_km: float
    summary: str


class ToolCallRecord(BaseModel):
    tool_name: str
    input_summary: str
    output_summary: str
    status: Literal["success", "failed", "degraded"] = "success"
    duration_ms: int = 0


class ExecutionAction(BaseModel):
    id: str
    run_id: str
    type: Literal["reserve_restaurant", "book_activity", "queue", "send_plan"]
    target: str
    label: str
    confirm_text: str
    status: Literal["pending", "completed", "failed"] = "pending"
    failure_reason: Optional[str] = None


class AgentRun(BaseModel):
    run_id: str
    status: RunStatus
    input: str
    request: Optional[PlanningRequest] = None
    plan_nodes: List[PlanNode] = Field(default_factory=list)
    merchant_profiles: Dict[str, MerchantProfile] = Field(default_factory=dict)
    route_segments: List[RouteSegment] = Field(default_factory=list)
    tool_calls: List[ToolCallRecord] = Field(default_factory=list)
    execution_actions: List[ExecutionAction] = Field(default_factory=list)
    message: str = ""


class StreamEvent(BaseModel):
    event: str
    data: Dict[str, Any]


AgentState = Dict[str, Any]
