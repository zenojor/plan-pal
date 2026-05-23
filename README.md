# Plan Pal

Plan Pal 是一个周末活动规划助手：输入你的需求后，系统会生成可编辑的时间线计划，并支持地点、交通与执行确认流程。

项目当前是一个前后端同仓库（monorepo）结构：
- 前端：React + Vite + TypeScript
- 后端：Spring Boot + Spring AI

## 功能概览

- 通过自然语言生成周末出行草案
- 计划包含业务节点与交通节点（`TRANSIT`）
- 支持流式规划事件（SSE）
- 支持“确认后执行”下单链路（如预订/通知）
- 前端使用 Animal Island 风格 UI

## 技术栈

### Frontend
- React 19
- TypeScript 6
- Vite 8
- Tailwind CSS 4
- animal-island-ui
- AMap (高德地图 JS API)

### Backend
- Java 17
- Spring Boot 3.4
- Spring AI 1.0.0-M4
- Maven

## 目录结构

```text
.
├─ src/                        # 前端源码
│  ├─ api/
│  ├─ components/
│  ├─ config/
│  ├─ types/
│  └─ App.tsx
├─ src/main/java/              # 后端源码（Spring Boot）
├─ src/main/resources/         # 后端配置（application.yml）
├─ API.md                      # API 说明
├─ DESIGN.md                   # 设计说明
├─ HANDOFF.md                  # 交接说明
├─ package.json                # 前端依赖与脚本
└─ pom.xml                     # 后端 Maven 配置
```

## 环境要求

- Node.js 20+
- pnpm 9+
- Java 17+
- Maven 3.9+

## 快速开始

### 1) 安装前端依赖

```bash
pnpm install
```

### 2) 启动后端（默认端口 8081）

```bash
mvn spring-boot:run
```

### 3) 启动前端（默认端口 5173）

```bash
pnpm dev
```

## 常用命令

### Frontend

```bash
pnpm dev       # 启动开发环境
pnpm build     # 构建前端产物
pnpm preview   # 预览构建结果
pnpm lint      # 代码检查
```

### Backend

```bash
mvn test                # 运行后端测试
mvn spring-boot:run     # 本地运行后端
mvn package             # 打包后端
```

## 配置说明

### 后端 LLM / Agent 配置

后端配置文件位于：`src/main/resources/application.yml`

关键环境变量：
- `DEEPSEEK_API_KEY`
- `DEEPSEEK_BASE_URL`（默认 `https://api.deepseek.com`）
- `DEEPSEEK_MODEL`（默认 `deepseek-v4-flash`）

### 地图配置（AMap）

前端使用高德地图 JS API。请将地图 Key 与安全密钥配置为你自己的值。

建议：
- 不要把真实密钥硬编码到仓库
- 使用 `.env.local` 管理本地敏感配置

## API 与设计文档

- 接口说明：`API.md`
- 设计说明：`DESIGN.md`
- 交接信息：`HANDOFF.md`

默认后端地址：`http://localhost:8081`

## 当前状态说明

仓库中部分历史文档曾出现编码问题（乱码），本 README 已更新为 UTF-8 可读版本。
如果你发现其他文档仍有乱码，建议统一转为 UTF-8 编码。
