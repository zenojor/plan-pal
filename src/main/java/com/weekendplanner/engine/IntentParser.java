package com.weekendplanner.engine;

import com.weekendplanner.dto.UserProfile;
import org.springframework.stereotype.Component;

/**
 * 意图解析器 - 从自然语言中提取结构化约束
 *
 * 基于关键词匹配做快速意图分类，为 LLM 提供预标注的上下文。
 */
@Component
public class IntentParser {

    /**
     * 从用户输入中提取用户画像
     */
    public UserProfile parse(String prompt) {
        String lower = prompt.toLowerCase();

        // 人群检测
        boolean hasChild = containsAny(lower, "孩子", "小孩", "宝宝", "儿子", "女儿", "娃", "亲子", "儿童");
        boolean hasWife = containsAny(lower, "老婆", "妻子", "太太");
        boolean hasFriend = containsAny(lower, "朋友", "哥们", "闺蜜", "兄弟", "姐妹", "同事");
        boolean hasDiet = containsAny(lower, "减肥", "轻食", "低脂", "低卡", "健康", "沙拉", "素食");

        // 人数估算
        int headcount = 0;
        int childCount = 0;
        if (hasWife && hasChild) {
            headcount = 3;  // 默认三口之家
            childCount = 1;
        } else if (hasFriend) {
            headcount = 4;  // 默认4人朋友组 (PRD: 2男2女)
        }

        // 场景判断
        boolean isSocialScene = hasFriend && !hasChild;

        // 饮食偏好
        boolean hasDietConstraint = hasDiet || (hasChild && hasWife);
        String dietaryType = hasDietConstraint ? "light/healthy" : "normal";

        // 距离约束
        int maxRadiusKm = 3;  // 默认3km
        if (containsAny(lower, "远一点", "远些", "10km", "10公里", "全城")) {
            maxRadiusKm = 5;
        } else if (containsAny(lower, "很近", "附近", "周边", "就近", "不超过")) {
            maxRadiusKm = 3;
        }

        // 时间
        String startTime = "14:00";  // 默认下午2点
        int preferredHours = 5;       // 默认5小时

        if (containsAny(lower, "上午", "早上", "9点", "10点")) {
            startTime = "10:00";
        }

        return new UserProfile(
                headcount, childCount, hasDietConstraint, dietaryType,
                isSocialScene, startTime, preferredHours, maxRadiusKm, prompt);
    }

    private boolean containsAny(String text, String... keywords) {
        for (String kw : keywords) {
            if (text.contains(kw)) return true;
        }
        return false;
    }
}
