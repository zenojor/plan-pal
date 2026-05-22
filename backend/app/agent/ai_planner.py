import json
import os
import re
import time
from typing import Any, Dict, List, Tuple

import httpx
from pydantic import BaseModel, Field, ValidationError

from app.agent.state import PlanNode, PlanningRequest, ToolCallRecord


class AIPlanNode(BaseModel):
    id: str = Field(description="Short stable id such as activity, snack, dinner")
    time: str
    title: str
    place: str
    audience: str
    reason: str
    budget: str
    status: str
    details: str


class AIPlanDraft(BaseModel):
    nodes: List[AIPlanNode] = Field(description="3 to 5 plan nodes in chronological order")
    summary: str


def _record(started: float, output: str, status: str = "success") -> ToolCallRecord:
    return ToolCallRecord(
        tool_name="deepseek_plan_draft",
        input_summary="DeepSeek Chat Completions JSON planner",
        output_summary=output,
        status=status,
        duration_ms=max(1, int((time.perf_counter() - started) * 1000)),
    )


def draft_plan_with_deepseek(
    request: PlanningRequest,
    activities: List[Dict[str, Any]],
    restaurants: List[Dict[str, Any]],
) -> Tuple[List[PlanNode], ToolCallRecord]:
    started = time.perf_counter()
    api_key = os.getenv("DEEPSEEK_API_KEY")
    if not api_key:
        return [], _record(started, "未设置 DEEPSEEK_API_KEY，已降级使用 Mock 规划。", "degraded")

    endpoint = os.getenv("DEEPSEEK_BASE_URL", "https://api.deepseek.com").rstrip("/") + "/chat/completions"
    model = os.getenv("DEEPSEEK_MODEL", "deepseek-v4-flash")
    candidate_map = {item["place"]: item for item in [*activities, *restaurants]}
    payload = {
        "model": model,
        "messages": [
            {
                "role": "system",
                "content": (
                    "你是一个本地短时活动规划 Agent。只输出 JSON，不要 markdown。"
                    "JSON 结构必须是 {\"summary\": string, \"nodes\": PlanNode[]}。"
                    "每个 PlanNode 必须包含 id,time,title,place,audience,reason,budget,status,details。"
                    "只能使用候选地点里的 place，输出 3-5 个按时间排序、能直接执行的中文计划节点。"
                ),
            },
            {
                "role": "user",
                "content": (
                    "用户需求：%s\n结构化解析：%s\n候选活动：%s\n候选餐厅：%s"
                    % (
                        request.raw_input,
                        request.model_dump_json(),
                        json.dumps(activities, ensure_ascii=False),
                        json.dumps(restaurants, ensure_ascii=False),
                    )
                ),
            },
        ],
        "temperature": 0.4,
        "max_tokens": 1800,
        "response_format": {"type": "json_object"},
    }

    try:
        with httpx.Client(timeout=30) as client:
            response = client.post(
                endpoint,
                headers={
                    "Authorization": "Bearer %s" % api_key,
                    "Content-Type": "application/json",
                },
                json=payload,
            )
            response.raise_for_status()
        content = response.json()["choices"][0]["message"]["content"]
        draft = AIPlanDraft.model_validate(_loads_json_content(content))
        nodes = [_to_plan_node(node, candidate_map, index) for index, node in enumerate(draft.nodes)]
        return nodes, _record(started, "DeepSeek 已生成 %d 个计划节点。" % len(nodes))
    except (httpx.HTTPError, KeyError, IndexError, json.JSONDecodeError, ValidationError) as exc:
        return [], _record(started, "DeepSeek 调用失败，已降级：%s" % str(exc), "degraded")


def _loads_json_content(content: str) -> Dict[str, Any]:
    cleaned = content.strip()
    fenced = re.search(r"```(?:json)?\s*(.*?)```", cleaned, flags=re.S)
    if fenced:
        cleaned = fenced.group(1).strip()
    return json.loads(cleaned)


def _to_plan_node(
    node: AIPlanNode,
    candidate_map: Dict[str, Dict[str, Any]],
    index: int,
) -> PlanNode:
    candidate = candidate_map.get(node.place)
    lnglat = tuple(candidate["lnglat"]) if candidate else (121.4737 + index * 0.005, 31.2304 - index * 0.003)
    return PlanNode(
        id=node.id or "node-%d" % index,
        time=node.time,
        title=node.title,
        place=node.place,
        lnglat=lnglat,
        audience=node.audience,
        reason=node.reason,
        budget=node.budget,
        status=node.status,
        details=node.details,
    )
