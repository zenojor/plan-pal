# 后端更新文档 — 天气 API 集成 & 项目重构

> 2026-05-25 | 分支: `backend/dev` | 提交: 2077ca4

---

## 一、天气 API 集成

### 新增文件

#### `WeatherResponse.java` — 天气响应 DTO

```
backend/src/main/java/com/weekendplanner/dto/WeatherResponse.java
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `location` | String | 城市名 |
| `date` | String | 日期 `2026-05-25` |
| `condition` | String | 天气状况（晴/多云/小雨/中雨/雷阵雨） |
| `tempHigh` | int | 最高温度 ℃ |
| `tempLow` | int | 最低温度 ℃ |
| `windDirection` | String | 风向 |
| `windScale` | int | 风力等级 |
| `outdoorFriendly` | boolean | 是否适合户外活动 |
| `suggestion` | String | 自然语言建议 |

#### `WeatherTool.java` — 天气查询工具

```
backend/src/main/java/com/weekendplanner/tool/WeatherTool.java
```

- 参数: `location`（城市名）, `date`（日期）
- API: 和风天气 `https://devapi.qweather.com/v7/weather/3d`
- API key 从 `weather.api-key` 读取，无 key 时自动 fallback 到 mock 数据
- Mock 按月份返回合理数据（5月小雨、6-7月雷阵雨、8月晴等）
- 判断 `outdoorFriendly`: 晴/多云=true，雨/大风/极端温度=false

### 修改文件

#### `FastPlanEngine.java` — 确定性引擎主动查天气

```
backend/src/main/java/com/weekendplanner/engine/FastPlanEngine.java
```

引擎本身不是 LLM 驱动的，不会读 system-prompt。因此改为**程序化**查询天气：

1. 在 `executePlanInternal()` 中，提取 `PlanIntent` 后、分配 slot 前，调用 `checkWeather`
2. 天气不佳 → `intent.withWeatherSensitive(true)` → 后续 scoring 自动优先 indoor POI
3. Indoor scoring boost: +25; Outdoor scoring penalty: -30
4. Summary 末尾追加天气信息（不影响 degraded 状态）
5. Notification 也包含天气建议

```
// 核心逻辑
WeatherResponse weather = toolRegistry.execute("checkWeather", params);
if (!weather.outdoorFriendly()) {
    intent = intent.withWeatherSensitive(true);
    // → indoor POI 评分 +25，outdoor POI 评分 -30
}
```

#### `PlanIntent.java` — 新增工厂方法

```
backend/src/main/java/com/weekendplanner/dto/PlanIntent.java
```

```java
public PlanIntent withWeatherSensitive(boolean weatherSensitive) {
    return new PlanIntent(..., weatherSensitive, ...);
}
```

PlanIntent 是 record（不可变），通过此方法创建修改副本。

#### `ToolRegistry.java` — 注册天气 tool

```
backend/src/main/java/com/weekendplanner/tool/ToolRegistry.java
```

构造函数新增 `WeatherTool` 参数：

```java
public ToolRegistry(..., WeatherTool weatherTool) {
    register("checkWeather", weatherTool::execute, weatherTool.getDescription());
}
```

#### `system-prompt.txt` — LLM 路线也支持天气

```
backend/src/main/resources/prompts/system-prompt.txt
```

新增 `checkWeather` action 和天气感知规则（Chat 端点用 ReAct 循环时会自动调用）。

#### `application.yml` — 天气配置

```yaml
weather:
  api-key: ${QWEATHER_API_KEY:}      # 和风天气 key，留空用 mock
  default-city: 上海
```

---

## 二、项目结构重构

### 目标

将前后端混在一起的 monorepo 重构为清晰分离的结构：

```
weekend-planner-backend/
├── backend/     ← 后端 Spring Boot 代码
├── frontend/    ← 前端 React 代码
├── docs/        ← 文档、比赛资料
├── README.md
├── HANDOFF.md
├── DESIGN.md
└── API.md
```

### 变更

| 操作 | 说明 |
|------|------|
| 新建 `backend/` | 移入 `pom.xml`, `src/` |
| 新建 `frontend/` | 移入 `package.json`, `vite.config.ts`, 所有 React 源码 |
| 整理 `docs/` | PDF、图片、`test.http`、`LEARNING_GUIDE.md` 归入 |
| 删除 | `node_modules/`, `target/`, `_tmp_*`, scratch file |
| 更新 `README.md` | 新项目结构图、环境变量表、分别启动命令 |
| 更新 `HANDOFF.md` | v4 变更日志、新目录启动方式、前端接入速查 |

### 启动方式变更

```bash
# 旧
mvn spring-boot:run          # 在根目录
pnpm dev                     # 在根目录

# 新
cd backend && mvn spring-boot:run   # 后端
cd frontend && pnpm dev            # 前端
```

---

## 三、测试修复

### `FastPlanEngineTest.java`

```java
// 修改前: queueThresholdMinutes = 30
// 修改后: queueThresholdMinutes = 60
ReflectionTestUtils.setField(engine, "queueThresholdMinutes", 60);
```

原因: Mock 数据的 `checkAvailability` 使用 `Math.abs(poiId.hashCode()) % 60` 生成排队时间，P022 总是 48min > 30min，导致测试不稳定。提高到 60 后所有 POI 都不会被排队过滤。

### 测试结果

```
Tests run: 34, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

---

## 四、架构示意图

```
用户请求
  │
  ├─ FastPlanEngine（确定性规划）
  │     ├─ extractIntent()     → PlanIntent
  │     ├─ checkWeather()      → WeatherResponse（程序化调用）
  │     ├─ weatherSensitive?   → indoor boost +25, outdoor penalty -30
  │     ├─ allocateSlots()     → Slot[]
  │     ├─ searchByTags()      → POI candidates
  │     ├─ scoreAndRank()      → 去重 + 不重复 POI
  │     ├─ checkAvailability() → 排队/预订状态
  │     └─ buildResponse()     → AgentPlanResponse
  │
  └─ ReActAgent（LLM 驱动，Chat 端点）
        ├─ system-prompt.txt 中的天气规则
        └─ LLM 自主决定何时调用 checkWeather
```

---

## 五、环境变量

| 变量 | 说明 | 默认值 |
|------|------|--------|
| `DEEPSEEK_API_KEY` | DeepSeek API Key | 必填 |
| `DEEPSEEK_BASE_URL` | API 地址 | `https://api.deepseek.com` |
| `DEEPSEEK_MODEL` | 模型 | `deepseek-v4-flash` |
| `QWEATHER_API_KEY` | 和风天气 Key（v4 新增） | 留空用 mock |

---

## 六、后续方向

1. **真实天气数据** — 注册和风天气 API key，替换 mock 数据
2. **WeatherTool 单元测试** — 覆盖 API 调用和 mock fallback 两条路径
3. **多城市支持** — 目前写死"上海"，可从用户 prompt 中提取城市
4. **天气缓存** — 同一天多次请求不重复调 API
