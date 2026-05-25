package com.weekendplanner.provider;

import com.weekendplanner.mock.MockOrderSystem;
import org.springframework.stereotype.Component;

@Component
public class SandboxReservationProvider implements ReservationProvider {

    private final MockOrderSystem orderSystem;

    public SandboxReservationProvider(MockOrderSystem orderSystem) {
        this.orderSystem = orderSystem;
    }

    @Override
    public ReservationResult reserve(String poiId, int headcount, String targetTime, String idempotencyKey) {
        String traceId = TraceIds.traceId("reserve");
        MockOrderSystem.ReservationResult result = orderSystem.reserve(poiId, headcount, targetTime);
        return new ReservationResult(result.reservationId(), result.success(), result.message(), "sandbox", traceId,
                "", result.reservationId(), idempotencyKey == null ? "" : idempotencyKey);
    }
}
