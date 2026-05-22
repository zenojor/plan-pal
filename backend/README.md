# Plan Pal Agent Backend

FastAPI + LangGraph 后端服务，负责本地活动规划 Agent 的工具调用、AI 草拟方案、Mock 数据编排和执行动作状态。

## 环境

建议使用 Python 3.10+。当前依赖包括 FastAPI、Pydantic 2、LangGraph、httpx 和 pytest。

## 启动

```bash
python -m venv .venv
.venv\Scripts\activate
pip install -r requirements.txt
$env:DEEPSEEK_API_KEY="你的 DeepSeek API Key"
$env:DEEPSEEK_MODEL="deepseek-v4-flash"
uvicorn app.main:app --reload
```

`DEEPSEEK_API_KEY` 未设置时，Agent 会记录一次 `deepseek_plan_draft` 降级工具调用，并继续使用 Mock planner 输出完整方案。

## 测试

```bash
pytest
```

## 核心 API

- `POST /api/agent/runs/stream`：SSE 流式规划，返回工具调用、计划节点、路线和执行动作。
- `POST /api/agent/runs`：同步规划。
- `GET /api/agent/runs/{run_id}`：查询规划结果。
- `POST /api/actions/{action_id}/execute`：Mock 执行预约、订位、排队或发送计划。
- `GET /api/mock-data`：查看 Mock 数据摘要。
