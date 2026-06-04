package com.weekendplanner.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.weekendplanner.engine.tooling.ToolCatalog;
import com.weekendplanner.engine.tooling.ToolEffect;
import com.weekendplanner.engine.tooling.ToolInvocation;
import com.weekendplanner.engine.tooling.ToolResult;
import com.weekendplanner.engine.tooling.ToolRunner;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

@Component
public class ToolRegistry {

    private final ToolCatalog catalog;
    private final ToolRunner runner;

    public ToolRegistry(LocationExplorationTool locationTool,
                        RestaurantReservationTool reservationTool,
                        RestaurantBookingTool bookingTool,
                        TicketingTool ticketingTool,
                        ActionExecutionTool executionTool) {
        this.catalog = new ToolCatalog(locationTool, reservationTool, bookingTool, ticketingTool, executionTool);
        this.runner = new ToolRunner(catalog, new ObjectMapper());
    }

    public ToolRegistry(LocationExplorationTool locationTool,
                        RestaurantReservationTool reservationTool,
                        RestaurantBookingTool bookingTool,
                        TicketingTool ticketingTool,
                        ActionExecutionTool executionTool,
                        RideHailingTool rideHailingTool) {
        this.catalog = new ToolCatalog(locationTool, reservationTool, bookingTool, ticketingTool, executionTool,
                rideHailingTool);
        this.runner = new ToolRunner(catalog, new ObjectMapper());
    }

    @Autowired
    public ToolRegistry(ToolCatalog catalog, ToolRunner runner) {
        this.catalog = catalog;
        this.runner = runner;
    }

    public ToolResult<String> execute(String toolName, String parametersJson) {
        return runner.run(new ToolInvocation<>(UUID.randomUUID().toString(), null, null,
                "legacy-tool-registry", toolName, parametersJson), Set.of(ToolEffect.READ_ONLY, ToolEffect.EXTERNAL_WRITE));
    }

    public ToolResult<String> executeExternalWrite(String requestId,
                                                   String userId,
                                                   String planId,
                                                   String caller,
                                                   String toolName,
                                                   String parametersJson) {
        return runner.run(new ToolInvocation<>(
                requestId == null || requestId.isBlank() ? UUID.randomUUID().toString() : requestId,
                userId,
                planId,
                caller,
                toolName,
                parametersJson), Set.of(ToolEffect.EXTERNAL_WRITE));
    }

    public String getToolDefinitions() {
        StringBuilder sb = new StringBuilder();
        for (var spec : catalog.tools()) {
            sb.append("- **").append(spec.name()).append("**: ").append(spec.description())
                    .append(" [").append(spec.effect()).append("]\n");
        }
        return sb.toString();
    }

    public String getToolNames() {
        return catalog.names();
    }

    public boolean hasTool(String name) {
        return catalog.hasTool(name);
    }
}
