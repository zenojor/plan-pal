package com.weekendplanner.tool;

import com.weekendplanner.dto.ToolCallResult;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class ToolRegistry {

    private final Map<String, ToolEntry> tools = new LinkedHashMap<>();

    public ToolRegistry(LocationExplorationTool locationTool,
                        RestaurantReservationTool reservationTool,
                        RestaurantBookingTool bookingTool,
                        TicketingTool ticketingTool,
                        ActionExecutionTool executionTool) {
        registerCoreTools(locationTool, reservationTool, bookingTool, ticketingTool, executionTool);
    }

    public ToolRegistry(LocationExplorationTool locationTool,
                        RestaurantReservationTool reservationTool,
                        RestaurantBookingTool bookingTool,
                        TicketingTool ticketingTool,
                        ActionExecutionTool executionTool,
                        RideHailingTool rideHailingTool) {
        registerCoreTools(locationTool, reservationTool, bookingTool, ticketingTool, executionTool);
        register(rideHailingTool.getToolName(), rideHailingTool::execute, rideHailingTool.getDescription());
    }

    @Autowired
    public ToolRegistry(LocationExplorationTool locationTool,
                        RestaurantReservationTool reservationTool,
                        RestaurantBookingTool bookingTool,
                        TicketingTool ticketingTool,
                        ActionExecutionTool executionTool,
                        ObjectProvider<MovieSearchTool> movieSearchToolProvider,
                        ObjectProvider<RideHailingTool> rideHailingToolProvider) {
        registerCoreTools(locationTool, reservationTool, bookingTool, ticketingTool, executionTool);
        MovieSearchTool movieSearchTool = movieSearchToolProvider.getIfAvailable();
        if (movieSearchTool != null) {
            register(movieSearchTool.getToolName(), movieSearchTool::execute, movieSearchTool.getDescription());
        }
        RideHailingTool rideHailingTool = rideHailingToolProvider.getIfAvailable();
        if (rideHailingTool != null) {
            register(rideHailingTool.getToolName(), rideHailingTool::execute, rideHailingTool.getDescription());
        }
    }

    private void registerCoreTools(LocationExplorationTool locationTool,
                                   RestaurantReservationTool reservationTool,
                                   RestaurantBookingTool bookingTool,
                                   TicketingTool ticketingTool,
                                   ActionExecutionTool executionTool) {
        register(locationTool.getToolName(), locationTool::execute, locationTool.getDescription());
        register(reservationTool.getToolName(), reservationTool::execute, reservationTool.getDescription());
        register(bookingTool.getToolName(), bookingTool::execute, bookingTool.getDescription());
        register(ticketingTool.getToolName(), ticketingTool::execute, ticketingTool.getDescription());
        register(executionTool.getToolName(), executionTool::execute, executionTool.getDescription());
    }

    private void register(String name, ToolExecutor executor, String description) {
        tools.put(name, new ToolEntry(name, description, executor));
    }

    public ToolCallResult execute(String toolName, String parametersJson) {
        ToolEntry entry = tools.get(toolName);
        if (entry == null) {
            return new ToolCallResult(toolName, false, null, "Unknown tool: " + toolName + ". Available: " + getToolNames());
        }
        try {
            String result = entry.executor().execute(parametersJson);
            return new ToolCallResult(toolName, true, result, null);
        } catch (Exception e) {
            return new ToolCallResult(toolName, false, null, e.getMessage());
        }
    }

    public String getToolDefinitions() {
        StringBuilder sb = new StringBuilder();
        for (ToolEntry entry : tools.values()) {
            sb.append("- **").append(entry.name()).append("**: ").append(entry.description()).append("\n");
        }
        return sb.toString();
    }

    public String getToolNames() {
        return String.join(", ", tools.keySet());
    }

    public boolean hasTool(String name) {
        return tools.containsKey(name);
    }

    @FunctionalInterface
    public interface ToolExecutor {
        String execute(String parametersJson);
    }

    private record ToolEntry(String name, String description, ToolExecutor executor) {}
}
