package com.weekendplanner.provider;

import com.weekendplanner.mock.MockOrderSystem;
import org.springframework.stereotype.Component;

@Component
public class SandboxTicketProvider implements TicketProvider {

    private final MockOrderSystem orderSystem;

    public SandboxTicketProvider(MockOrderSystem orderSystem) {
        this.orderSystem = orderSystem;
    }

    @Override
    public TicketResult bookTickets(String poiId, int quantity, String sessionTime, String idempotencyKey) {
        String traceId = TraceIds.traceId("ticket");
        MockOrderSystem.TicketResult result = orderSystem.buyTicket(poiId, quantity, sessionTime);
        return new TicketResult(result.ticketId(), result.success(), result.totalPrice(), result.message(),
                "sandbox", traceId, "", result.ticketId(), idempotencyKey == null ? "" : idempotencyKey);
    }
}
