package com.weekendplanner.engine.planning;


import com.weekendplanner.dto.OrderIntent;
import com.weekendplanner.dto.PlanIntent;
import com.weekendplanner.dto.PlanStep;
import com.weekendplanner.mock.GeoUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
public class TimelineAssembler {

    public Result assemble(String planId,
                           PlanIntent intent,
                           List<PlanStep> businessSteps,
                           boolean includeBuffer,
                           int startingOrderIndex) {
        List<PlanStep> timeline = new ArrayList<>();
        List<OrderIntent> orderIntents = new ArrayList<>();
        int cursor = toMinutes(intent.startTime());
        int planEnd = toMinutes(intent.endTime());
        PlanStep previous = null;
        int businessIndex = 1;

        for (PlanStep step : businessSteps) {
            if (previous != null) {
                PlanStep transit = buildTransitStep(planId, previous, step, cursor, intent, businessIndex);
                if (transit != null) {
                    timeline.add(transit);
                    cursor += transit.durationMinutes();
                }
            }

            int duration = Math.max(20, step.durationMinutes());
            String segmentId = stableBusinessSegmentId(planId, step, businessIndex);
            OrderIntent orderIntent = buildOrderIntent(planId, startingOrderIndex + orderIntents.size() + 1, step, cursor, intent);
            if (orderIntent != null) {
                orderIntents.add(orderIntent);
            }

            PlanStep timed = withTimingAndOrder(step, cursor, duration, intent, orderIntent, segmentId);
            timeline.add(timed);
            previous = timed;
            cursor += duration;
            businessIndex++;
        }

        if (includeBuffer && previous != null && cursor < planEnd) {
            int buffer = planEnd - cursor;
            if (buffer >= 20) {
                timeline.add(buildBufferStep(planId, intent, cursor, planEnd, businessIndex));
            }
        }

        List<PlanStep> finalTimeline = ensureSegmentIds(planId, timeline);
        return new Result(finalTimeline, List.copyOf(orderIntents));
    }

    public List<PlanStep> ensureSegmentIds(String planId, List<PlanStep> timeline) {
        if (timeline == null || timeline.isEmpty()) return List.of();
        List<PlanStep> result = new ArrayList<>();
        int businessIndex = 1;
        int transitIndex = 1;
        int bufferIndex = 1;
        for (PlanStep step : timeline) {
            if (step == null) continue;
            String segmentId = step.segmentId();
            if (segmentId == null || segmentId.isBlank()) {
                if (step.isTransit() || "TRANSIT".equalsIgnoreCase(step.phase())) {
                    segmentId = "SEG-" + planId + "-T" + transitIndex++;
                } else if (step.poiId() == null || step.poiId().isBlank()) {
                    segmentId = "SEG-" + planId + "-B" + bufferIndex++;
                } else {
                    segmentId = "SEG-" + planId + "-" + businessIndex++;
                }
            } else if (!step.isTransit() && step.poiId() != null && !step.poiId().isBlank()) {
                businessIndex++;
            }
            result.add(withSegmentId(step, segmentId));
        }
        return List.copyOf(result);
    }

    public PlanStep withSegmentId(PlanStep step, String segmentId) {
        return new PlanStep(step.durationMinutes(), step.startTime(), step.endTime(), step.phase(), step.action(),
                step.poiId(), step.poiName(), step.bookingStatus(), step.note(), step.lnglat(), step.audience(),
                step.reason(), step.budget(), step.headcount(), step.constraints(), step.executionStatus(),
                step.orderIntentId(), step.isTransit(), step.transportMode(), step.distanceKm(), step.fromPoiName(),
                step.toPoiName(), step.source(), step.address(), step.telephone(), step.businessHours(),
                step.typeCode(), segmentId);
    }

    private String stableBusinessSegmentId(String planId, PlanStep step, int index) {
        if (step.segmentId() != null && !step.segmentId().isBlank()) return step.segmentId();
        return "SEG-" + planId + "-" + index;
    }

    private PlanStep withTimingAndOrder(PlanStep step, int start, int duration, PlanIntent intent,
                                        OrderIntent orderIntent, String segmentId) {
        return new PlanStep(duration, formatMinutes(start), formatMinutes(start + duration), step.phase(), step.action(),
                step.poiId(), step.poiName(), orderIntent == null ? "无需预约" : "待确认", step.note(), step.lnglat(),
                audience(intent), step.reason(), step.budget(), safeHeadcount(intent),
                String.join("、", intent.dietaryConstraints()), "PENDING_CONFIRMATION",
                orderIntent == null ? "" : orderIntent.orderIntentId(), step.source(), step.address(),
                step.telephone(), step.businessHours(), step.typeCode(), segmentId);
    }

    private PlanStep buildTransitStep(String planId, PlanStep previousStep, PlanStep nextStep,
                                      int startMinutes, PlanIntent intent, int index) {
        if (previousStep.lnglat() == null || previousStep.lnglat().length < 2
                || nextStep.lnglat() == null || nextStep.lnglat().length < 2) return null;
        double distanceKm = GeoUtils.distanceKm(previousStep.lnglat()[0], previousStep.lnglat()[1],
                nextStep.lnglat()[0], nextStep.lnglat()[1]);
        int duration = estimateTransitMinutes(distanceKm, intent);
        String mode = transportMode(distanceKm, duration, intent);
        String segmentId = "SEG-" + planId + "-T" + index;
        return new PlanStep(duration, formatMinutes(startMinutes), formatMinutes(startMinutes + duration),
                "TRANSIT", mode + " " + duration + " 分钟", "", previousStep.poiName() + " -> " + nextStep.poiName(),
                "路上", String.format(Locale.ROOT, "%s约%.1fkm，预计%d分钟。", mode, distanceKm, duration),
                nextStep.lnglat(), "路线衔接",
                String.format(Locale.ROOT, "从%s到%s。", previousStep.poiName(), nextStep.poiName()),
                "交通约 CNY 0-8", previousStep.headcount(), "", "TRANSIT", "",
                true, mode, distanceKm, previousStep.poiName(), nextStep.poiName(), nextStep.source(),
                nextStep.address(), nextStep.telephone(), nextStep.businessHours(), nextStep.typeCode(), segmentId);
    }

    private PlanStep buildBufferStep(String planId, PlanIntent intent, int start, int end, int index) {
        return new PlanStep(end - start, formatMinutes(start), formatMinutes(end), "LEISURE",
                "自由缓冲 / 散步返程", "", "就近自由安排", "灵活收尾",
                "修改后保留缓冲时间，方便排队、步行或返程。", null, audience(intent),
                "不强行拉满行程，保留真实节奏。", "可免费", safeHeadcount(intent),
                String.join("、", intent.dietaryConstraints()), "PENDING_CONFIRMATION", "",
                "system", "", "", "", "", "SEG-" + planId + "-B" + index);
    }

    private OrderIntent buildOrderIntent(String planId, int index, PlanStep step, int startMinutes, PlanIntent intent) {
        String type = switch (step.phase()) {
            case "DINING", "DRINKS" -> "RESERVE_TABLE";
            case "ACTIVITY" -> "BOOK_TICKET";
            default -> "";
        };
        if (type.isBlank()) return null;
        return new OrderIntent("OI-" + planId + "-" + index, type, step.poiId(), step.poiName(),
                safeHeadcount(intent), formatMinutes(startMinutes), "PENDING");
    }

    private int estimateTransitMinutes(double distanceKm, PlanIntent intent) {
        if ("DRIVE".equalsIgnoreCase(intent.preferredTransportMode())) {
            return Math.max(8, (int) Math.round(distanceKm / 28.0 * 60) + 6);
        }
        if ("WALK".equalsIgnoreCase(intent.preferredTransportMode())) {
            return Math.max(6, (int) Math.round(distanceKm / 4.5 * 60));
        }
        if (distanceKm <= 0.8) return Math.max(6, (int) Math.round(distanceKm / 4.5 * 60));
        if (distanceKm <= 2.2) return Math.max(12, (int) Math.round(distanceKm / 18.0 * 60) + 8);
        return Math.max(18, (int) Math.round(distanceKm / 24.0 * 60) + 10);
    }

    private String transportMode(double distanceKm, int durationMinutes, PlanIntent intent) {
        if ("DRIVE".equalsIgnoreCase(intent.preferredTransportMode())) return "打车/自驾";
        if ("WALK".equalsIgnoreCase(intent.preferredTransportMode()) && distanceKm <= 1.8) return "步行";
        if (distanceKm <= 0.8 && durationMinutes <= 14) return "步行";
        if (distanceKm <= 2.2) return "公交/地铁";
        return "地铁";
    }

    private String audience(PlanIntent intent) {
        if ("SOLO".equalsIgnoreCase(intent.sceneType())) return "一个人";
        if ("SOCIAL".equalsIgnoreCase(intent.sceneType())) return "朋友小组";
        return "家庭 / 同行人";
    }

    private int safeHeadcount(PlanIntent intent) {
        return intent.headcount() > 0 ? intent.headcount() : 1;
    }

    private int toMinutes(String time) {
        String[] parts = time.split(":");
        return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
    }

    private String formatMinutes(int minutes) {
        return String.format(Locale.ROOT, "%02d:%02d", minutes / 60, minutes % 60);
    }

    public record Result(List<PlanStep> timeline, List<OrderIntent> orderIntents) {}
}
