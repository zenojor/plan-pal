# Plan Pal

本地周末活动规划 Demo：React 前端 + FastAPI 后端 + LangGraph Agent。用户输入一句自然语言目标后，后端会按“解析需求、搜索活动、搜索餐厅、检查可用性、AI 草拟方案、路线估算、生成执行动作”的链路生成可落地计划。

## 启动

前端：

```bash
pnpm install
pnpm dev
```

后端：

```bash
cd backend
python -m venv .venv
.venv\Scripts\activate
pip install -r requirements.txt
$env:DEEPSEEK_API_KEY="你的 DeepSeek API Key"
$env:DEEPSEEK_MODEL="deepseek-v4-flash"
uvicorn app.main:app --reload
```

前端开发服务器会把 `/api` 请求代理到 `http://127.0.0.1:8000`。没有设置 `DEEPSEEK_API_KEY` 时，后端会自动降级到 Mock 规划，方便离线演示。

## 验证

```bash
pnpm build
cd backend
pytest
```

## 说明

- `POST /api/agent/runs/stream` 使用 SSE 流式返回工具调用和计划节点，前端拼图卡片会逐张出现。
- `POST /api/agent/runs` 保留同步规划接口，便于测试和非流式调用。
- 所有美团、高德、支付、订位、排队、发送计划动作都是 Mock，不产生真实外部副作用。
- 设计说明见 `docs/agent-design.md`。
