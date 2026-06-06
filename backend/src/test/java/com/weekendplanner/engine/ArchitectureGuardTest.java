package com.weekendplanner.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.weekendplanner.engine.graph.PlanGraphNodes;
import com.weekendplanner.engine.tooling.ToolCatalog;
import com.weekendplanner.engine.tooling.ToolRunner;
import com.weekendplanner.engine.tooling.ToolResult;
import com.weekendplanner.mock.MockOrderSystem;
import com.weekendplanner.mock.MockPoiDatabase;
import com.weekendplanner.tool.ActionExecutionTool;
import com.weekendplanner.tool.LocationExplorationTool;
import com.weekendplanner.tool.RestaurantBookingTool;
import com.weekendplanner.tool.RestaurantReservationTool;
import com.weekendplanner.tool.RideHailingTool;
import com.weekendplanner.tool.TicketingTool;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ArchitectureGuardTest {

    @Test
    void graphNodeListOnlyContainsWiredNodes() {
        PlanGraphNodes nodes = new PlanGraphNodes(null);

        assertThat(nodes.nodeNames())
                .doesNotContain("patch_extract", "patch_apply", "candidate_search");
    }

    @Test
    void toolRunnerReadOnlyDoesNotAllowExternalWrite() {
        ObjectMapper mapper = new ObjectMapper();
        MockPoiDatabase poiDatabase = new MockPoiDatabase();
        MockOrderSystem orderSystem = new MockOrderSystem();
        ToolCatalog catalog = new ToolCatalog(
                new LocationExplorationTool(poiDatabase, mapper),
                new RestaurantReservationTool(poiDatabase, mapper),
                new RestaurantBookingTool(orderSystem, mapper),
                new TicketingTool(orderSystem, mapper),
                new ActionExecutionTool(orderSystem, mapper),
                new RideHailingTool(orderSystem, mapper));
        ToolRunner runner = new ToolRunner(catalog, mapper);

        ToolResult<String> result = runner.runReadOnly("test", "reserveRestaurant",
                "{\"poiId\":\"P001\",\"headcount\":2,\"targetTime\":\"18:00\"}");

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("not allowed");
    }
}
