package com.weekendplanner.engine;

import com.weekendplanner.dto.OrderIntent;
import com.weekendplanner.dto.PlanIntent;
import com.weekendplanner.dto.PlanStep;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 保存规划草案，供用户点击确认后执行 mock 下单。
 */
@Component
public class PlanExecutionStore {

    private final ConcurrentMap<String, DraftPlan> plans = new ConcurrentHashMap<>();

    public void save(DraftPlan plan) {
        plans.put(plan.planId(), plan);
    }

    public Optional<DraftPlan> find(String planId) {
        return Optional.ofNullable(plans.get(planId));
    }

    public record DraftPlan(
            String planId,
            String userId,
            PlanIntent intent,
            List<PlanStep> timeline,
            List<OrderIntent> orderIntents,
            String notificationText,
            int version,
            String previousVersionId
    ) {
        public DraftPlan(String planId,
                         String userId,
                         PlanIntent intent,
                         List<PlanStep> timeline,
                         List<OrderIntent> orderIntents,
                         String notificationText) {
            this(planId, userId, intent, timeline, orderIntents, notificationText, 1, null);
        }

        public String versionId() {
            return planId + ":v" + version;
        }
    }
}
