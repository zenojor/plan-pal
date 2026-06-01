package com.weekendplanner.engine.planning;


import com.weekendplanner.dto.Conflict;
import com.weekendplanner.dto.PlanIntent;
import com.weekendplanner.dto.PlanStep;
import com.weekendplanner.dto.WeatherSnapshot;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class PlanNarrativeBuilder {

    public Narrative buildInitial(PlanIntent intent, WeatherSnapshot weather) {
        return new Narrative(intentBrief(intent, weather), planningBrief(intent, weather));
    }

    public String finalBrief(PlanIntent intent,
                             WeatherSnapshot weather,
                             List<PlanStep> timeline,
                             boolean degraded,
                             List<Conflict> conflicts) {
        long businessSteps = timeline == null ? 0 : timeline.stream().filter(step -> !step.isTransit()).count();
        String first = firstBusinessName(timeline);
        String last = lastBusinessName(timeline);
        StringBuilder sb = new StringBuilder("这版我给你排成了一个 ")
                .append(hoursLabel(intent.totalMinutes()))
                .append(" 的")
                .append(sceneLabel(intent))
                .append("方案");
        if (!first.isBlank() && !last.isBlank()) {
            sb.append("：从 ").append(first).append(" 开始，后面接到 ").append(last);
        } else {
            sb.append("，一共放了 ").append(businessSteps).append(" 个主要节点");
        }
        sb.append("。");
        if (hasWeatherRisk(weather)) {
            sb.append("天气我也一起看过了，已尽量把户外风险压低，优先选室内或不太折腾的点。");
        }
        if (degraded || (conflicts != null && !conflicts.isEmpty())) {
            sb.append("有些约束我已经做了可执行优先的取舍，后面还可以继续替换或微调。");
        }
        sb.append("你可以直接拖动右侧拼图，或者告诉我“餐厅别换，活动换近点”。");
        return sb.toString();
    }

    private String intentBrief(PlanIntent intent, WeatherSnapshot weather) {
        StringBuilder sb = new StringBuilder("我先按 ")
                .append(intent.startTime()).append("-").append(intent.endTime())
                .append("、").append(Math.max(1, intent.headcount())).append(" 人来排");
        if (intent.hasChildren()) {
            sb.append("，会照顾孩子的节奏");
            if (intent.childAge() != null) sb.append("（").append(intent.childAge()).append(" 岁）");
        }
        sb.append("，重点放在").append(priorityLabel(intent)).append("。");
        if (hasWeatherRisk(weather)) {
            sb.append("另外今天").append(weather.summary()).append("，我会把天气也算进取舍里。");
        }
        return sb.toString();
    }

    private String planningBrief(PlanIntent intent, WeatherSnapshot weather) {
        StringBuilder sb = new StringBuilder("我会先锁定")
                .append(scenePlanningFocus(intent))
                .append("，再把用餐和交通时间接顺，中间留出真实路程，避免赶场。");
        if (hasWeatherRisk(weather)) {
            sb.append("天气有风险时，我会优先找 indoor、museum、mall、cafe 这类更稳的点。");
        }
        if (!intent.dietaryConstraints().isEmpty()) {
            sb.append("饮食限制我会一起避开，不让餐厅选择和你的要求打架。");
        }
        return sb.toString();
    }

    private String priorityLabel(PlanIntent intent) {
        if (intent.hasChildren()) return "近、轻松、孩子友好";
        if ("DATE".equalsIgnoreCase(intent.sceneType())) return "好聊、舒服、有氛围";
        if ("SOCIAL".equalsIgnoreCase(intent.sceneType())) return "好集合、好吃好玩、少折腾";
        if ("SOLO".equalsIgnoreCase(intent.sceneType())) return "自由、顺路、低压力";
        return "顺路、轻松、可执行";
    }

    private String scenePlanningFocus(PlanIntent intent) {
        if (intent.hasChildren()) return "适合孩子停留的活动";
        if ("DATE".equalsIgnoreCase(intent.sceneType())) return "适合聊天和停留的场所";
        if ("SOCIAL".equalsIgnoreCase(intent.sceneType())) return "朋友一起参与感比较强的节点";
        if ("SOLO".equalsIgnoreCase(intent.sceneType())) return "一个人也舒服的节点";
        return "最稳的活动节点";
    }

    private String sceneLabel(PlanIntent intent) {
        if (intent.hasChildren()) return "轻松亲子";
        return switch (intent.sceneType() == null ? "" : intent.sceneType().toUpperCase()) {
            case "DATE" -> "约会";
            case "SOCIAL" -> "朋友出行";
            case "SOLO" -> "个人放松";
            case "FAMILY" -> "家庭";
            default -> "出行";
        };
    }

    private String hoursLabel(int totalMinutes) {
        int minutes = Math.max(0, totalMinutes);
        if (minutes == 0) return "半天";
        if (minutes % 60 == 0) return (minutes / 60) + " 小时";
        return String.format(java.util.Locale.ROOT, "%.1f 小时", minutes / 60.0);
    }

    private boolean hasWeatherRisk(WeatherSnapshot weather) {
        return weather != null
                && ("MEDIUM".equalsIgnoreCase(weather.outdoorRiskLevel())
                || "HIGH".equalsIgnoreCase(weather.outdoorRiskLevel()));
    }

    private String firstBusinessName(List<PlanStep> timeline) {
        if (timeline == null) return "";
        return timeline.stream()
                .filter(step -> !step.isTransit())
                .map(PlanStep::poiName)
                .filter(name -> name != null && !name.isBlank())
                .findFirst()
                .orElse("");
    }

    private String lastBusinessName(List<PlanStep> timeline) {
        if (timeline == null) return "";
        List<String> names = timeline.stream()
                .filter(step -> !step.isTransit())
                .map(PlanStep::poiName)
                .filter(name -> name != null && !name.isBlank())
                .toList();
        return names.isEmpty() ? "" : names.get(names.size() - 1);
    }

    public record Narrative(String intentBrief, String planningBrief) {
    }
}
