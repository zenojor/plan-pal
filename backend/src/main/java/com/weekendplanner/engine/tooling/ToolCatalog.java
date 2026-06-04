package com.weekendplanner.engine.tooling;

import com.weekendplanner.dto.BookTicketRequest;
import com.weekendplanner.dto.CheckRequest;
import com.weekendplanner.dto.CheckResponse;
import com.weekendplanner.dto.ExecuteOrderRequest;
import com.weekendplanner.dto.ExecuteOrderResponse;
import com.weekendplanner.dto.ReserveRestaurantRequest;
import com.weekendplanner.dto.ReserveRestaurantResponse;
import com.weekendplanner.dto.SearchRequest;
import com.weekendplanner.dto.SearchResponse;
import com.weekendplanner.dto.TicketResponse;
import com.weekendplanner.tool.ActionExecutionTool;
import com.weekendplanner.tool.LocationExplorationTool;
import com.weekendplanner.tool.MovieSearchTool;
import com.weekendplanner.tool.RestaurantBookingTool;
import com.weekendplanner.tool.RestaurantReservationTool;
import com.weekendplanner.tool.RideHailingTool;
import com.weekendplanner.tool.TicketingTool;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class ToolCatalog {

    private final Map<String, ToolEntry> tools = new LinkedHashMap<>();

    public ToolCatalog() {
    }

    public ToolCatalog(LocationExplorationTool locationTool,
                       RestaurantReservationTool reservationTool,
                       RestaurantBookingTool bookingTool,
                       TicketingTool ticketingTool,
                       ActionExecutionTool executionTool) {
        registerCoreTools(locationTool, reservationTool, bookingTool, ticketingTool, executionTool);
    }

    public ToolCatalog(LocationExplorationTool locationTool,
                       RestaurantReservationTool reservationTool,
                       RestaurantBookingTool bookingTool,
                       TicketingTool ticketingTool,
                       ActionExecutionTool executionTool,
                       RideHailingTool rideHailingTool) {
        registerCoreTools(locationTool, reservationTool, bookingTool, ticketingTool, executionTool);
        registerRideTool(rideHailingTool);
    }

    @Autowired
    public ToolCatalog(LocationExplorationTool locationTool,
                       RestaurantReservationTool reservationTool,
                       RestaurantBookingTool bookingTool,
                       TicketingTool ticketingTool,
                       ActionExecutionTool executionTool,
                       ObjectProvider<MovieSearchTool> movieSearchToolProvider,
                       ObjectProvider<RideHailingTool> rideHailingToolProvider) {
        registerCoreTools(locationTool, reservationTool, bookingTool, ticketingTool, executionTool);
        MovieSearchTool movieSearchTool = movieSearchToolProvider.getIfAvailable();
        if (movieSearchTool != null) {
            register(new ToolSpec(movieSearchTool.getToolName(), movieSearchTool.getDescription(),
                    ToolEffect.READ_ONLY, Map.class, Map.class, 3_000L, true), movieSearchTool::execute);
        }
        RideHailingTool rideHailingTool = rideHailingToolProvider.getIfAvailable();
        if (rideHailingTool != null) {
            registerRideTool(rideHailingTool);
        }
    }

    public void register(ToolSpec spec, ToolExecutor executor) {
        tools.put(spec.name(), new ToolEntry(spec, executor));
    }

    public Optional<ToolEntry> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    public List<ToolSpec> tools() {
        return tools.values().stream().map(ToolEntry::spec).toList();
    }

    public List<ToolSpec> toolsByEffect(ToolEffect effect) {
        return tools().stream().filter(spec -> spec.effect() == effect).toList();
    }

    public String names() {
        return String.join(", ", tools.keySet());
    }

    public boolean hasTool(String name) {
        return tools.containsKey(name);
    }

    private void registerCoreTools(LocationExplorationTool locationTool,
                                   RestaurantReservationTool reservationTool,
                                   RestaurantBookingTool bookingTool,
                                   TicketingTool ticketingTool,
                                   ActionExecutionTool executionTool) {
        register(new ToolSpec(locationTool.getToolName(), locationTool.getDescription(),
                ToolEffect.READ_ONLY, SearchRequest.class, SearchResponse.class, 3_000L, true), locationTool::execute);
        register(new ToolSpec(reservationTool.getToolName(), reservationTool.getDescription(),
                ToolEffect.READ_ONLY, CheckRequest.class, CheckResponse.class, 3_000L, true), reservationTool::execute);
        register(new ToolSpec(bookingTool.getToolName(), bookingTool.getDescription(),
                ToolEffect.EXTERNAL_WRITE, ReserveRestaurantRequest.class, ReserveRestaurantResponse.class, 5_000L, false),
                bookingTool::execute);
        register(new ToolSpec(ticketingTool.getToolName(), ticketingTool.getDescription(),
                ToolEffect.EXTERNAL_WRITE, BookTicketRequest.class, TicketResponse.class, 5_000L, false),
                ticketingTool::execute);
        register(new ToolSpec(executionTool.getToolName(), executionTool.getDescription(),
                ToolEffect.EXTERNAL_WRITE, ExecuteOrderRequest.class, ExecuteOrderResponse.class, 5_000L, false),
                executionTool::execute);
    }

    private void registerRideTool(RideHailingTool rideHailingTool) {
        register(new ToolSpec(rideHailingTool.getToolName(), rideHailingTool.getDescription(),
                ToolEffect.EXTERNAL_WRITE, Map.class, Map.class, 5_000L, false), rideHailingTool::execute);
    }

    public record ToolEntry(ToolSpec spec, ToolExecutor executor) {
    }
}
