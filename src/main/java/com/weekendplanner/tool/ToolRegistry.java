package com.weekendplanner.tool;

import com.weekendplanner.dto.ToolCallResult;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具注册中心 - 管理所有工具并路由执行
 */
@Component
public class ToolRegistry {

    private final Map<String, ToolEntry> tools = new LinkedHashMap<>();

    public ToolRegistry(
            LocationExplorationTool locationTool,
            RestaurantReservationTool reservationTool,
            RestaurantBookingTool bookingTool,
            TicketingTool ticketingTool,
            ActionExecutionTool executionTool,
            MovieSearchTool movieSearchTool) {

        register(locationTool.getToolName(), locationTool::execute, locationTool.getDescription());
        register(reservationTool.getToolName(), reservationTool::execute, reservationTool.getDescription());
        register(bookingTool.getToolName(), bookingTool::execute, bookingTool.getDescription());
        register(ticketingTool.getToolName(), ticketingTool::execute, ticketingTool.getDescription());
        register(executionTool.getToolName(), executionTool::execute, executionTool.getDescription());
        register(movieSearchTool.getToolName(), movieSearchTool::execute, movieSearchTool.getDescription());
    }

    private void register(String name, ToolExecutor executor, String description) {
        tools.put(name, new ToolEntry(name, description, executor));
    }

    /**
     * 执行工具调用
     */
    public ToolCallResult execute(String toolName, String parametersJson) {
        ToolEntry entry = tools.get(toolName);
        if (entry == null) {
            return new ToolCallResult(toolName, false, null,
                    "未知工具: " + toolName + "。可用工具: " + getToolNames());
        }
        try {
            String result = entry.executor().execute(parametersJson);
            return new ToolCallResult(toolName, true, result, null);
        } catch (Exception e) {
            return new ToolCallResult(toolName, false, null, e.getMessage());
        }
    }

    /**
     * 获取所有工具定义（用于 System Prompt 注入）
     */
    public String getToolDefinitions() {
        StringBuilder sb = new StringBuilder();
        for (ToolEntry entry : tools.values()) {
            sb.append("- **").append(entry.name()).append("**: ")
                    .append(entry.description()).append("\n");
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
