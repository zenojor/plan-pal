from fastapi.testclient import TestClient

from app.main import app


client = TestClient(app)


def test_family_plan_contains_activity_and_actions():
    response = client.post(
        "/api/agent/runs",
        json={"input": "周六下午带 5 岁孩子和老婆出去 4-6 小时，别太远，吃清淡一点"},
    )
    assert response.status_code == 200
    data = response.json()
    assert data["status"] == "ready"
    assert len(data["plan_nodes"]) >= 4
    assert len(data["execution_actions"]) >= 3
    assert any(call["tool_name"] == "parse_goal" for call in data["tool_calls"])


def test_friend_plan_uses_friend_scene():
    response = client.post(
        "/api/agent/runs",
        json={"input": "四个朋友下午出去，2 男 2 女，想吃饭加一个轻活动"},
    )
    assert response.status_code == 200
    data = response.json()
    assert data["request"]["scene"] == "friends"
    assert data["request"]["party_size"] == 4


def test_stream_run_emits_tool_calls_and_plan_nodes():
    response = client.post(
        "/api/agent/runs/stream",
        json={"input": "一家三口周末下午出去玩，孩子 5 岁，安排吃饭和亲子活动"},
    )
    assert response.status_code == 200
    body = response.text
    assert "event: tool_call" in body
    assert "event: plan_node" in body
    assert "event: completed" in body


def test_execute_action_marks_completed():
    run_response = client.post(
        "/api/agent/runs",
        json={"input": "一家三口周末下午出去玩，孩子 5 岁"},
    )
    action = run_response.json()["execution_actions"][0]
    action_response = client.post("/api/actions/%s/execute" % action["id"])
    assert action_response.status_code == 200
    assert action_response.json()["status"] == "completed"


def test_short_input_needs_more_info():
    response = client.post("/api/agent/runs", json={"input": "安排"})
    assert response.status_code == 200
    data = response.json()
    assert data["status"] == "needs_more_info"
    assert data["plan_nodes"] == []
