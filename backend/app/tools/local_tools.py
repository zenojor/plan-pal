import json
import time
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

from app.agent.state import (
    ExecutionAction,
    MerchantProfile,
    PlanNode,
    PlanningRequest,
    RouteSegment,
    ToolCallRecord,
)

DATA_DIR = Path(__file__).resolve().parents[1] / "mock_data"


def _load_json(name: str) -> List[Dict[str, Any]]:
    with open(DATA_DIR / name, "r", encoding="utf-8") as file:
        return json.load(file)


def _tool_record(
    name: str,
    started: float,
    input_summary: str,
    output_summary: str,
    status: str = "success",
) -> ToolCallRecord:
    return ToolCallRecord(
        tool_name=name,
        input_summary=input_summary,
        output_summary=output_summary,
        status=status,
        duration_ms=max(1, int((time.perf_counter() - started) * 1000)),
    )


def parse_user_goal(raw_input: str) -> Tuple[PlanningRequest, ToolCallRecord]:
    started = time.perf_counter()
    text = raw_input.strip()
    missing_fields: List[str] = []
    if len(text) < 8:
        missing_fields.append("请补充出行时间、人数或场景")

    scene = "family" if any(token in text for token in ["孩子", "小孩", "娃", "老婆", "一家", "亲子"]) else "friends"
    child_age = 5 if "5" in text and scene == "family" else None
    party_size = 4 if any(token in text for token in ["4", "四"]) else (3 if scene == "family" else 4)
    time_range = "下午 4-6 小时" if any(token in text for token in ["下午", "4-6", "几个小时"]) else "今天半天"

    preferences: List[str] = []
    constraints: List[str] = []
    if any(token in text for token in ["减肥", "控脂", "清淡"]):
        preferences.append("清淡/控脂餐厅")
    if any(token in text for token in ["别太远", "不远", "近"]):
        constraints.append("距离不要太远")
    if any(token in text for token in ["朋友", "2个男", "2个女", "四个人"]):
        preferences.append("适合朋友聊天")
    if scene == "family":
        preferences.append("孩子友好")

    request = PlanningRequest(
        raw_input=text,
        time_range=time_range,
        party_size=party_size,
        scene=scene,
        child_age=child_age,
        preferences=preferences,
        constraints=constraints,
        missing_fields=missing_fields,
    )
    return request, _tool_record(
        "parse_goal",
        started,
        text[:80],
        "识别为%s场景，%d人，偏好：%s"
        % ("家庭" if scene == "family" else "朋友", party_size, "、".join(preferences) or "无"),
        "success" if not missing_fields else "degraded",
    )


def search_activities(request: PlanningRequest) -> Tuple[List[Dict[str, Any]], ToolCallRecord]:
    started = time.perf_counter()
    activities = _load_json("activities.json")
    selected = [
        item for item in activities
        if request.scene in item["tags"] and item.get("available", True)
    ]
    if not selected:
        selected = [item for item in activities if item.get("available", True)]
    return selected[:2], _tool_record(
        "find_activities",
        started,
        request.scene,
        "找到 %d 个可用活动候选" % len(selected[:2]),
    )


def search_restaurants(request: PlanningRequest) -> Tuple[List[Dict[str, Any]], ToolCallRecord]:
    started = time.perf_counter()
    restaurants = _load_json("restaurants.json")
    preferred = [
        item for item in restaurants
        if request.scene in item["tags"] or "snack" in item["tags"]
    ]
    return preferred, _tool_record(
        "find_restaurants",
        started,
        "scene=%s preferences=%s" % (request.scene, ",".join(request.preferences)),
        "找到 %d 个餐饮候选" % len(preferred),
    )


def check_availability(
    activities: List[Dict[str, Any]],
    restaurants: List[Dict[str, Any]],
) -> Tuple[List[Dict[str, Any]], List[Dict[str, Any]], ToolCallRecord]:
    started = time.perf_counter()
    available_activities = [item for item in activities if item.get("available", True)]
    available_restaurants = [item for item in restaurants if item.get("available", True)]
    status = "success" if len(available_restaurants) == len(restaurants) else "degraded"
    return available_activities, available_restaurants, _tool_record(
        "check_availability",
        started,
        "activities=%d restaurants=%d" % (len(activities), len(restaurants)),
        "活动可用 %d 个，餐厅可用 %d 个" % (len(available_activities), len(available_restaurants)),
        status,
    )


def compose_plan(
    request: PlanningRequest,
    activities: List[Dict[str, Any]],
    restaurants: List[Dict[str, Any]],
    ai_nodes: Optional[List[PlanNode]] = None,
) -> Tuple[List[PlanNode], Dict[str, MerchantProfile], ToolCallRecord]:
    started = time.perf_counter()
    activity = activities[0]
    snack = next((item for item in restaurants if "snack" in item["tags"]), restaurants[0])
    dinner = next((item for item in restaurants if "snack" not in item["tags"]), restaurants[0])

    nodes = ai_nodes if ai_nodes else _compose_mock_nodes(request, activity, snack, dinner)
    profiles = _compose_profiles(restaurants, activities)
    for node in nodes:
        if node.place not in profiles:
            profiles[node.place] = MerchantProfile(
                address="AI 规划候选点位，待接入真实 POI",
                queue="暂未接入实时排队，建议出发前确认。",
                booking="可作为待执行动作处理。",
                hours="以当天营业为准",
                contact="AI 推荐",
                tags=[node.audience, node.status],
            )

    return nodes, profiles, _tool_record(
        "compose_plan",
        started,
        "nodes",
        "生成 %d 个时间节点" % len(nodes),
    )


def _compose_mock_nodes(
    request: PlanningRequest,
    activity: Dict[str, Any],
    snack: Dict[str, Any],
    dinner: Dict[str, Any],
) -> List[PlanNode]:
    nodes = [
        PlanNode(
            id="start",
            time="14:00",
            title="集合出发",
            place="附近地铁站",
            lnglat=(121.4737, 31.2304),
            audience="全员",
            reason="先用熟悉的交通点集合，减少临时沟通成本。",
            budget="交通约 CNY 20-40",
            status="轻松开场",
            details="提前 10 分钟发送定位，并确认孩子用品、充电宝和雨具。",
        ),
        PlanNode(
            id="activity",
            time="14:40",
            title=activity["title"],
            place=activity["place"],
            lnglat=tuple(activity["lnglat"]),
            audience=activity["audience"],
            reason="匹配%s场景，并控制在短距离移动内。" % ("家庭" if request.scene == "family" else "朋友"),
            budget=activity["budget"],
            status=activity["status"],
            details=activity["details"],
        ),
        PlanNode(
            id="snack",
            time="16:15",
            title=snack["title"],
            place=snack["place"],
            lnglat=tuple(snack["lnglat"]),
            audience=snack["audience"],
            reason="作为活动和正餐之间的缓冲，避免节奏太赶。",
            budget=snack["budget"],
            status=snack["status"],
            details=snack["details"],
        ),
        PlanNode(
            id="dinner",
            time="18:00",
            title=dinner["title"],
            place=dinner["place"],
            lnglat=tuple(dinner["lnglat"]),
            audience=dinner["audience"],
            reason="结合排队和饮食偏好，选择更容易落地的餐厅。",
            budget=dinner["budget"],
            status=dinner["status"],
            details=dinner["details"],
        ),
    ]
    if request.scene == "family":
        nodes.insert(
            3,
            PlanNode(
                id="buffer",
                time="17:00",
                title="小公园放电",
                place="月芽社区公园",
                lnglat=(121.4985, 31.2195),
                audience="孩子优先",
                reason="晚饭前给孩子留一段活动余量，大人也可以休息。",
                budget="免费",
                status="可伸缩",
                details="如果孩子累了可直接跳过，提前去餐厅。",
            ),
        )
    return nodes


def _compose_profiles(
    restaurants: List[Dict[str, Any]],
    activities: List[Dict[str, Any]],
) -> Dict[str, MerchantProfile]:
    profiles: Dict[str, MerchantProfile] = {}
    for item in restaurants:
        profiles[item["place"]] = MerchantProfile(
            address=item["address"],
            queue=item["queue"],
            booking=item["booking"],
            hours=item["hours"],
            contact=item["contact"],
            tags=item["tags"],
        )
    for item in activities:
        profiles[item["place"]] = MerchantProfile(
            address="本地精选活动点位",
            queue="按场次入场，现场等待较少。",
            booking="建议提前在线预约。",
            hours="10:00-20:00",
            contact="Demo Mock",
            tags=item["tags"],
        )
    profiles["附近地铁站"] = MerchantProfile(
        address="用户附近交通集合点",
        queue="无需排队。",
        booking="无需预约。",
        hours="全天",
        contact="公共交通",
        tags=["集合", "交通"],
    )
    return profiles


def estimate_routes(nodes: List[PlanNode]) -> Tuple[List[RouteSegment], ToolCallRecord]:
    started = time.perf_counter()
    segments: List[RouteSegment] = []
    for index in range(len(nodes) - 1):
        origin = nodes[index]
        target = nodes[index + 1]
        distance = round(0.7 + index * 0.45, 1)
        segments.append(
            RouteSegment(
                from_place=origin.place,
                to_place=target.place,
                walking_minutes=8 + index * 4,
                transit_minutes=12 + index * 3,
                driving_minutes=6 + index * 2,
                distance_km=distance,
                summary="%s 到 %s 约 %.1f km，步行可控。" % (origin.place, target.place, distance),
            )
        )
    return segments, _tool_record(
        "estimate_route",
        started,
        "stops=%d" % len(nodes),
        "估算 %d 段路线" % len(segments),
    )


def create_execution_actions(run_id: str, nodes: List[PlanNode]) -> Tuple[List[ExecutionAction], ToolCallRecord]:
    started = time.perf_counter()
    dinner = nodes[-1]
    activity = next((node for node in nodes if node.id == "activity"), nodes[1])
    actions = [
        ExecutionAction(
            id="%s-book-activity" % run_id,
            run_id=run_id,
            type="book_activity",
            target=activity.place,
            label="预约活动",
            confirm_text="已为 %s 生成 Mock 预约。" % activity.place,
        ),
        ExecutionAction(
            id="%s-reserve-dinner" % run_id,
            run_id=run_id,
            type="reserve_restaurant",
            target=dinner.place,
            label="预订餐厅",
            confirm_text="已为 %s 生成 Mock 留位。" % dinner.place,
        ),
        ExecutionAction(
            id="%s-send-plan" % run_id,
            run_id=run_id,
            type="send_plan",
            target="微信/短信",
            label="发送计划",
            confirm_text="计划已 Mock 发送给同行人。",
        ),
    ]
    return actions, _tool_record(
        "create_actions",
        started,
        "run_id=%s" % run_id,
        "生成 %d 个可执行动作" % len(actions),
    )


def execute_mock_action(action: ExecutionAction) -> ExecutionAction:
    action.status = "completed"
    action.failure_reason = None
    return action


def get_mock_data_summary() -> Dict[str, Any]:
    return {
        "activities": _load_json("activities.json"),
        "restaurants": _load_json("restaurants.json"),
    }
