# Plan-pal TODO

## 有想法但还不明确的点
- [ ] 自然语言需求解析（人数 / 时间 / 风格 / 预算 / 偏好）
- [ ] 节奏考虑天气 / 疲劳感因素，可接入天气 API；FastEngine 目前还没有把这些因素纳入稳定排程
- [ ] LLM 输出文本有时太空泛，和推荐商家本身的关系不够紧
- [ ] MockDatabase 还可以再丰富一些，补更多商家商品列表和图片素材

## 明确推进的点
- [x] FastEngine 继续扩充结构化 intent 字段
```json
{
  "pace": "RELAXED|NORMAL|COMPACT",
  "budgetLevel": "LOW|MEDIUM|HIGH",
  "hasChildren": true,
  "childAge": null,
  "transportMode": "WALK|DRIVE|PUBLIC_TRANSIT",
  "avoid": [],
  "mustHave": [],
  "weatherSensitive": true
}
```

## 很好解决的 bug
- [x] 前端层提醒用户输入人数时间的文本错误渲染到了顶栏，应该渲染在对话框内

## 世界模型（World Model）
- [ ] 时间状态
- [ ] 地理状态
- [ ] 用户状态
- [ ] 天气状态
- [ ] 疲劳状态
- [ ] 预算状态
- [ ] 情绪状态

## 弹性时间窗口 / Flexible Scheduling

### Summary
- [ ] 不再把所有活动都当成固定 `startTime/endTime` 块处理
- [ ] 把时间表达升级为“当前排定时间 + 可调整窗口 + 弹性等级”
- [ ] 让前端明确区分：哪些节点是锁定的，哪些是可平移、可压缩、可拉长的
- [ ] 让后端重排优先移动弹性节点，而不是一上来就删除活动或整段重算

### Data Model
- [ ] 为 `PlanStep` / 前端 `PlanNode` 增加时间弹性字段：
  - `earliestStart`
  - `latestEnd`
  - `minDuration`
  - `recommendedDuration`
  - `timeFlexibility: FIXED | SOFT | FLEXIBLE`
  - `preferredTimeOfDay: MORNING | AFTERNOON | EVENING | ANY`
  - `hardConstraints`
  - `softConstraints`
- [ ] 区分业务含义：
  - `FIXED`: 预约 / 场次 / 必须卡点的节点
  - `SOFT`: 有推荐时段，但允许平移
  - `FLEXIBLE`: 可平移，也可缩短或拉长
- [ ] 为餐饮、酒吧、散步、自由活动、展览等常见 phase 定义默认弹性策略

### Backend / Scheduling
- [ ] 在 `TimelineAssembler` / `PlanEditorEngine` 中引入窗口排程，而不是只依赖固定时长串接
- [ ] 重排优先级调整为：
  1. 保留 `FIXED` 节点
  2. 平移 `SOFT` 节点
  3. 压缩或拉长 `FLEXIBLE` 节点
  4. 仍冲突时才触发删除、替换或延长总时段
- [ ] `PlanPatch` 支持更细的时间弹性操作：
  - `LOCK_SEGMENT`
  - `UNLOCK_SEGMENT`
  - `ADJUST_DURATION`
  - `SHIFT_WITHIN_WINDOW`
- [ ] 当用户说“往后放一点 / 缩短一点 / 晚上更合适”时，优先命中窗口调整，而不是整段重规划
- [ ] 为 summary / explanation 增加可解释描述：
  - “保留预约时间，向后平移 2 个弹性节点”
  - “压缩自由活动 20 分钟，为晚间小酌腾出时间”

### Frontend / Timeline UI
- [ ] 时间卡片区分两种视觉语言：
  - 固定节点：显示“已锁定 / 预约时间”
  - 弹性节点：显示“可前后浮动 / 建议 45-75 分钟 / 适合傍晚”
- [ ] 在节点卡片中增加可调整范围展示：
  - `当前: 17:30-18:45`
  - `可调整: 17:00-20:00`
  - `建议停留: 45-75 分钟`
- [ ] 为节点增加锁定 / 解锁操作，允许用户明确告诉系统“这个保留但时间可调”
- [ ] 为节点增加轻量时长控制：
  - `缩短一点`
  - `正常`
  - `放松一点`
- [ ] 拖拽排序时，前端展示被拖动节点是否可移动、是否会压缩相邻弹性节点
- [ ] 保持默认界面简洁，只在 hover / 展开 / 编辑态显示完整窗口信息

### Interaction Design
- [ ] 当用户点击“描述修改”时，把当前节点上下文一并传给后端：
  - `segmentId`
  - `timeFlexibility`
  - `preferredTimeOfDay`
  - 当前 `earliestStart/latestEnd`
- [ ] 当用户说“这个往后一点”“这个太赶了”时，优先显示窗口内调整结果，而不是替换 POI
- [ ] 当用户触发冲突卡时，候选方案应能区分：
  - 延长总时段
  - 平移弹性节点
  - 压缩弹性节点
  - 替换或删除节点
- [ ] 在聊天说明中避免把结果说成“重排完成”而实际只是文案变化；要明确说明是“延长”“平移”“压缩”还是“替换”

### V1 Rollout
- [ ] V1 先覆盖高频可弹性节点：
  - `FREE_TIME`
  - `WALK`
  - `DRINKS`
  - `ACTIVITY` 中的展览 / 公园 / 轻体验类
- [ ] V1 先做只读展示 + 后端重排支持，不必一次上完整可视化滑杆
- [ ] V1 保持旧字段兼容，前端没有窗口字段时继续按固定时间渲染
- [ ] V1 测试重点：
  - 晚上增加喝酒时优先挪动弹性节点
  - 保留餐厅不动，只压缩自由活动
  - “这个活动往后一点”命中目标 `segmentId` 并在窗口内平移
  - 固定预约节点不会被误压缩或误平移
