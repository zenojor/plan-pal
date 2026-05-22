# 前端接入文档 — Weekend Planner Agent

**仓库:** https://github.com/zenojor/plan-pal.git
**后端分支:** main
**技术栈:** Spring Boot 3.4.1 + Spring AI + DeepSeek V4 Pro

---

## 一、后端启动

```bash
# 1. 设置 API Key（不用改代码，环境变量即可）
# Windows PowerShell:
$env:DEEPSEEK_API_KEY = "sk-你的key"
# macOS / Linux:
export DEEPSEEK_API_KEY="sk-你的key"

# 2. 启动（默认8080端口）
mvn spring-boot:run
# 或者
./mvnw spring-boot:run
```

启动后验证：`curl http://localhost:8080/api/v1/agent/health` → `Agent is running`

---

## 二、核心接口

### 1. POST /api/v1/agent/plan（同步规划）

**推荐调试时使用**，返回完整 JSON。

```javascript
// 请求
const res = await fetch('http://localhost:8080/api/v1/agent/plan', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({
    userId: 'U001',
    prompt: '今天下午想带老婆孩子出去玩，别离家太远，帮我安排一下'
  })
});

const data = await res.json();
// → { planId, status, summary, timeline, trace, orderGroupId, notificationText, degradationNote }
```

**响应结构中前端最关注的字段：**

| 字段 | 类型 | 用途 |
|------|------|------|
| `status` | string | `SUCCESS` 正常渲染 / `DEGRADED` 带降级提示渲染 / `FAILED` 弹错误 |
| `summary` | string | 最终方案自然语言，可直接放主卡片 |
| `timeline` | array | 时间线步骤，渲染时间轴 UI |
| `trace` | array | ReAct 思考链，可折叠展示 Agent 推理过程 |
| `orderGroupId` | string | 订单号，展示用 |
| `notificationText` | string | 可复制的分享文案 |
| `degradationNote` | string\|null | 非空时弹 toast 提示用户 |

**timeline 子字段：**

| 字段 | 值示例 | 说明 |
|------|--------|------|
| `timeRange` | `"14:00-16:00"` | 时间段 |
| `phase` | `ACTIVITY` / `TRANSIT` / `DINING` / `EVENING` | 阶段类型 |
| `poiName` | `"星海儿童探索馆"` | 地点名 |
| `poiId` | `"P008"` | 地点ID |
| `bookingStatus` | `"已确认"` / `""` | 是否已预订 |

---

### 2. GET /api/v1/agent/plan/stream（SSE 流式）

**推荐生产环境使用**，实时推送每一步推理过程，用户体验更好。

```javascript
const url = 'http://localhost:8080/api/v1/agent/plan/stream'
  + '?userId=U001'
  + '&prompt=' + encodeURIComponent('今天下午想带老婆孩子出去玩');

const es = new EventSource(url);

// 每个事件 type 不同，data 是 JSON
es.addEventListener('START', e => {
  const { content } = JSON.parse(e.data);
  // 显示"开始规划..."加载态
});

es.addEventListener('THOUGHT', e => {
  const { step, content } = JSON.parse(e.data);
  // 渲染思考气泡："正在分析您的需求..."
});

es.addEventListener('ACTION', e => {
  const { step, content } = JSON.parse(e.data);
  // 渲染工具调用："正在搜索附近亲子活动..."
});

es.addEventListener('OBSERVATION', e => {
  const { step, content } = JSON.parse(e.data);
  // 渲染观察结果："找到3个场所..."
});

es.addEventListener('FINISH', e => {
  const { content, timeline } = JSON.parse(e.data);
  // 渲染最终方案时间线 + summary
  // content = 自然语言方案
  // timeline = 结构化时间轴
  es.close();
});

es.addEventListener('ERROR', e => {
  const { content } = JSON.parse(e.data);
  // 弹错误提示
  es.close();
});

// 超时兜底
es.onerror = () => {
  es.close();
  // 显示"规划超时，请重试"
};
```

---

## 三、SSE 事件流时序

```
START → THOUGHT → ACTION → OBSERVATION → THOUGHT → ACTION → ...
                                                        ... → FINISH(含timeline)
```

**每个事件的 data JSON：**

```json
{
  "type": "ACTION",
  "step": 2,
  "content": "Tool: searchNearby, Params: {...}",
  "timeline": null
}
```

只有 `FINISH` 事件的 `timeline` 有值。

---

## 四、两种场景参考

| 场景 | Prompt 示例 | 期望结果 |
|------|-----------|----------|
| 家庭 | `"今天下午想带老婆孩子出去玩，别离家太远"` | 亲子活动 + 轻食餐厅 |
| 朋友 | `"今天下午想和朋友出去玩，4个人，帮我安排一下"` | 社交展览 + 小吃街 + Citywalk |

---

## 五、错误处理

后端可能返回的 HTTP 状态码：

| 状态码 | 含义 | 前端处理 |
|--------|------|----------|
| 200 | 成功 | 正常渲染 |
| 400 | 参数校验失败 | 提示用户检查输入 |
| 500 | 规划熔断/LLM异常 | 提示"规划失败，请稍后重试" |

500 时 response body：
```json
{
  "status": 500,
  "message": "规划迭代超出最大步数上限(15)，触发安全熔断"
}
```

---

## 六、前端 UI 建议

1. **输入框** → 展示两个快捷场景按钮（"带家人出去玩"/"和朋友聚会"），点击填充 Prompt
2. **加载态** → SSE 模式下按事件流推进进度条
3. **思考链** → 可折叠区域，展示 Agent 每一步 Thought/Action/Observation
4. **时间轴** → 左侧时间线 + 右侧详情卡片，4 种 phase 用不同颜色
5. **分享按钮** → 点击复制 `notificationText` 到剪贴板
6. **降级提示** → 如果 `degradationNote` 非空，弹黄色 toast

---

有问题找后端，API 文档详见 [API.md](https://github.com/zenojor/plan-pal/blob/main/API.md)
