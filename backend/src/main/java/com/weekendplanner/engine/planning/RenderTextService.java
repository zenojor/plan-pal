package com.weekendplanner.engine.planning;


import com.weekendplanner.dto.PoiDto;
import org.springframework.stereotype.Component;

@Component
public class RenderTextService {

    public String fastWorkflowStarted() {
        return "已启动快速规划流程";
    }

    public String planUpdated() {
        return "方案已更新";
    }

    public String pendingCancelled() {
        return "已取消这次选择。";
    }

    public String clarificationFallback() {
        return "再补充一个偏好，我就能继续帮你收窄。";
    }

    public String candidatePrompt() {
        return "先选一个候选，我再继续调整方案。";
    }

    public String autoRecommendationSuffix() {
        return "\n\n新空档里我找到了一些可选项，选一个我就更新到方案里。";
    }

    public String candidateCardTitle(boolean isAdd) {
        return isAdd ? "选择要加入的地点" : "选择替换候选";
    }

    public String candidateCardDescription(boolean isAdd) {
        return isAdd
                ? "选一个候选，我会把它加入拼图。"
                : "选一个候选，我会替换当前节点。";
    }

    public String chooseLabel(PoiDto poi, boolean isAdd) {
        String verb = isAdd ? "加入 " : "选择 ";
        return verb + (poi == null ? "候选" : poi.name());
    }

    public String candidateCardTitle(boolean isAdd, boolean isEmptySlot) {
        if (isEmptySlot) return "给这段空档选个活动";
        return candidateCardTitle(isAdd);
    }

    public String candidateCardDescription(boolean isAdd, boolean isEmptySlot) {
        if (isEmptySlot) return "选一个候选，我会把这段自由安排替换成真实活动。";
        return candidateCardDescription(isAdd);
    }

    public String chooseLabel(PoiDto poi, boolean isAdd, boolean isEmptySlot) {
        if (isEmptySlot) return "放入 " + (poi == null ? "候选" : poi.name());
        return chooseLabel(poi, isAdd);
    }

    public String candidateDescription(PoiDto poi) {
        if (poi == null) return "";
        String tags = poi.tags() == null || poi.tags().isEmpty() ? "" : " / " + String.join("/", poi.tags());
        return String.format(java.util.Locale.ROOT, "%.1fkm%s", poi.distanceKm(), tags);
    }
}
