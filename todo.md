# Plan-pal TODO

## 有想法但不明朗的点
- [ ] 自然语言需求解析（人数/时间/风格/**预算**/偏好）
- [ ] 酌情考虑天气/疲惫感因素，可以考虑加入天气API，FastEngine实际上没有考虑这么多因素，我还在想用什么架构处理
- [ ] LLM输出文本过于废话，也跟推荐的商家没有关系
- [ ] MockDatabase可能得再大一点详尽一些，考虑再加一个商家的商品列表和商家图片（可以用两个占位测试图片）

## 明朗推进的点
- [ ] FastEngine主要就是让LLM取一个json抽取器的功能，现在拟加入以下字段
```
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

## 很好解决的bug
- [ ] 前端层提醒用户输入人数时间的文本错误渲染到了顶栏，应渲染在对话框内

## 世界模型（World Model）

- 时间状态
- 地理状态
- 用户状态
- 天气状态
- 疲劳状态
- 预算状态
- 情绪状态