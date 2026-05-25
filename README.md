# Plan Pal

周末活动规划助手：输入需求，系统生成可编辑的时间线计划，支持地点推荐、交通衔接、天气感知与订单执行。

## 项目结构

```text
plan-pal/
├── backend/                 ← Spring Boot 后端
│   ├── pom.xml
│   └── src/
│       ├── main/java/       ← Java 源码
│       ├── main/resources/  ← 配置 & prompt
│       └── test/            ← 测试
├── frontend/                ← React 前端
│   ├── package.json
│   ├── vite.config.ts
│   └── src/
├── docs/                    ← 文档 & 比赛资料
├── HANDOFF.md               ← 前后端联调文档
├── DESIGN.md                ← 设计说明
└── README.md
```

## 技术栈

| 层 | 技术 |
|----|------|
| 前端 | React 19 / TypeScript 6 / Vite 8 / Tailwind CSS 4 / 高德地图 |
| 后端 | Java 17 / Spring Boot 3.4 / Spring AI 1.0.0-M4 / Maven |
| AI | DeepSeek / 和风天气 API |

## 环境要求

- Node.js 22+
- pnpm 9+
- Java 17+
- Maven 3.9+

## 快速开始

```bash
# 1. 后端
cd backend
$env:DEEPSEEK_API_KEY = "sk-你的key"
mvn spring-boot:run
# → http://localhost:8081

# 2. 前端
cd frontend
pnpm install
pnpm dev
# → http://localhost:5173
```

## 验证

```bash
curl http://localhost:8081/api/v1/agent/health
# → "Agent is running"
```

## 常用命令

```bash
# 后端测试
cd backend && mvn test

# 前端构建
cd frontend && pnpm build
```

## 环境变量

| 变量 | 说明 | 默认值 |
|------|------|--------|
| `DEEPSEEK_API_KEY` | DeepSeek API Key | 必填 |
| `DEEPSEEK_BASE_URL` | API 地址 | `https://api.deepseek.com` |
| `DEEPSEEK_MODEL` | 模型 | `deepseek-v4-flash` |
| `QWEATHER_API_KEY` | 和风天气 Key | 留空用 mock |

## 功能特性

- 自然语言生成周末出行草案
- 天气自动感知：遇雨天/高温自动优先室内方案
- 计划包含业务节点与交通节点（TRANSIT）
- 支持流式规划事件（SSE）
- 支持"确认后执行"下单链路

## 文档

- 前后端联调：[HANDOFF.md](HANDOFF.md)
- 接口说明：[API.md](API.md)
- 设计说明：[DESIGN.md](DESIGN.md)
- 后端更新：[docs/updatebackend.md](docs/updatebackend.md)
