# 本地活动规划 Agent 设计说明

## 规划策略

Plan Pal Agent 面向“几分钟内把周末短时活动安排落地”的场景，不做搜索推荐页，而是把自然语言目标转成可执行方案。后端使用 FastAPI 暴露 API，使用 LangGraph 编排流程；如果设置了 `DEEPSEEK_API_KEY`，会通过 DeepSeek Chat Completions 生成更自然的方案节点，如果没有密钥或调用失败，会记录降级状态并回退到确定性 Mock planner。

LangGraph 节点顺序为：`parse_goal -> find_activities -> find_restaurants -> check_availability -> compose_plan -> estimate_route -> create_actions`。每个节点只读写统一 Agent State，并追加一条 `ToolCallRecord`，前端可展示完整工具调用链。流式接口 `POST /api/agent/runs/stream` 会先返回工具进度，再逐个推送 `PlanNode`，让拼图卡片按规划过程逐张出现。

## 工具链

Mock 工具包括活动搜索、餐厅搜索、可用性检查、路线估算和执行动作生成。活动与餐厅数据放在 `backend/app/mock_data`，每个地点包含坐标、预算、人群适配、排队和预约信息。DeepSeek 只负责在候选地点内组合 3-5 个中文计划节点，不直接调用外部服务；这样 Demo 既有 AI 参与，又不会产生真实下单、支付或预订副作用。

前端提交后优先调用流式接口，拼图列展示 `plan_nodes`，商家列展示 `merchant_profiles`，路线列展示 `route_segments`，状态面板展示 `tool_calls`，执行区展示 `execution_actions`。动作通过 `POST /api/actions/{action_id}/execute` 更新为已完成。

## 异常处理

输入过短或缺少关键字段时返回 `needs_more_info`，不伪造完整方案。餐厅或活动不可用时优先使用备选数据，并在工具调用中标记 `degraded`。DeepSeek 未配置、超时、返回格式错误或网络失败时，Agent 自动回退到 Mock planner，前端仍能展示完整可执行方案。所有预约、订位、排队和发送计划动作都是 Mock，不连接真实美团、高德、支付或消息接口。
