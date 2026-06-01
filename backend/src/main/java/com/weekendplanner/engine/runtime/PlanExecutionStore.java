package com.weekendplanner.engine.runtime;


import com.weekendplanner.dto.OrderIntent;
import com.weekendplanner.dto.PlanIntent;
import com.weekendplanner.dto.PlanStatus;
import com.weekendplanner.dto.PlanStep;
import org.springframework.stereotype.Component;

import java.time.Instant;
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

    public DraftPlan updateStatus(String planId, PlanStatus status, String idempotencyKey) {
        return plans.computeIfPresent(planId, (ignored, draft) -> draft.withStatus(status, idempotencyKey));
    }

    public record DraftPlan(
            String planId,
            String userId,
            PlanIntent intent,
            List<PlanStep> timeline,
            List<OrderIntent> orderIntents,
            String notificationText,
            int version,
            String previousVersionId,
            PlanStatus status,
            Integer lastConfirmedVersion,
            String idempotencyKey,
            Instant updatedAt
    ) {
        public DraftPlan(String planId,
                         String userId,
                         PlanIntent intent,
                         List<PlanStep> timeline,
                         List<OrderIntent> orderIntents,
                         String notificationText) {
            this(planId, userId, intent, timeline, orderIntents, notificationText, 1, null,
                    PlanStatus.PENDING_CONFIRMATION, null, null, Instant.now());
        }

        public DraftPlan(String planId,
                         String userId,
                         PlanIntent intent,
                         List<PlanStep> timeline,
                         List<OrderIntent> orderIntents,
                         String notificationText,
                         int version,
                         String previousVersionId) {
            this(planId, userId, intent, timeline, orderIntents, notificationText, version, previousVersionId,
                    PlanStatus.MODIFIED, null, null, Instant.now());
        }

        public DraftPlan {
            timeline = timeline == null ? List.of() : List.copyOf(timeline);
            orderIntents = orderIntents == null ? List.of() : List.copyOf(orderIntents);
            version = version <= 0 ? 1 : version;
            status = status == null ? PlanStatus.PENDING_CONFIRMATION : status;
            updatedAt = updatedAt == null ? Instant.now() : updatedAt;
        }

        public String versionId() {
            return planId + ":v" + version;
        }

        public DraftPlan nextVersion(PlanIntent nextIntent,
                                     List<PlanStep> nextTimeline,
                                     List<OrderIntent> nextOrderIntents,
                                     String nextNotificationText) {
            return new DraftPlan(planId, userId, nextIntent, nextTimeline, nextOrderIntents, nextNotificationText,
                    version + 1, versionId(), PlanStatus.MODIFIED, lastConfirmedVersion, idempotencyKey, Instant.now());
        }

        public DraftPlan withStatus(PlanStatus nextStatus, String nextIdempotencyKey) {
            Integer confirmedVersion = nextStatus == PlanStatus.CONFIRMED || nextStatus == PlanStatus.PARTIALLY_BOOKED
                    ? Integer.valueOf(version) : lastConfirmedVersion;
            return new DraftPlan(planId, userId, intent, timeline, orderIntents, notificationText, version,
                    previousVersionId, nextStatus, confirmedVersion,
                    nextIdempotencyKey == null || nextIdempotencyKey.isBlank() ? idempotencyKey : nextIdempotencyKey,
                    Instant.now());
        }
    }
}
