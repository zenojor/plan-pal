# Plan Pal 🐾

一个帮助你轻松规划周末出行的工具 —— 输入你的想法，自动生成带时间线、地点和路线地图的计划卡片。

## 技术栈

- **React 19** + **TypeScript 6** + **Vite 8**
- [animal-island-ui](https://www.npmjs.com/package/animal-island-ui) — 动森风格 UI 组件库
- **Tailwind CSS 4** — 样式
- **高德地图 (AMap)** — 路线规划与地图展示
- **pnpm** — 包管理

## 快速开始

```bash
# 安装依赖
pnpm install

# 启动开发服务器
pnpm dev

# 构建生产版本
pnpm build

# 预览构建产物
pnpm preview
```

## 项目结构

```
src/
├── main.tsx       # 入口
├── App.tsx        # 主应用组件（规划流程、拖拽卡片、地图）
├── App.css        # 组件样式
├── index.css      # 全局样式 & Tailwind 主题配置
├── amap.d.ts      # 高德地图类型声明
└── assets/        # 静态资源
```

## 地图配置

项目使用高德地图 JS API。地图密钥配置在 `index.html` 中。使用时请替换为你自己的 Key 和安全密钥。

> ⚠️ 请勿将真实 Key 提交到仓库。建议使用 `.env` 文件管理敏感配置。
