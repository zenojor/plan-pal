package com.weekendplanner.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.weekendplanner.engine.tooling.ToolCatalog;
import com.weekendplanner.engine.tooling.ToolEffect;
import com.weekendplanner.engine.tooling.ToolInvocation;
import com.weekendplanner.engine.tooling.ToolResult;
import com.weekendplanner.engine.tooling.ToolRunner;
import com.weekendplanner.engine.tooling.ToolSpec;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ToolRunnerTest {

    @Test
    void readOnlyCallerCanRunReadOnlyTool() {
        ToolRunner runner = runnerWithTestTools();

        ToolResult<String> result = runner.run(new ToolInvocation<>(
                        "req-1", "U001", "plan-1", "qa", "searchNearby", Map.of("query", "coffee")),
                Set.of(ToolEffect.READ_ONLY));

        assertThat(result.success()).isTrue();
        assertThat(result.effect()).isEqualTo(ToolEffect.READ_ONLY);
        assertThat(result.traceId()).isNotBlank();
        assertThat(result.elapsedMs()).isGreaterThanOrEqualTo(0);
        assertThat(result.data()).contains("ok");
    }

    @Test
    void readOnlyCallerCannotRunExternalWriteTool() {
        ToolRunner runner = runnerWithTestTools();

        ToolResult<String> result = runner.run(new ToolInvocation<>(
                        "req-2", "U001", "plan-1", "qa", "reserveRestaurant", Map.of("poiId", "P001")),
                Set.of(ToolEffect.READ_ONLY));

        assertThat(result.success()).isFalse();
        assertThat(result.effect()).isEqualTo(ToolEffect.EXTERNAL_WRITE);
        assertThat(result.errorMessage()).contains("not allowed");
        assertThat(result.traceId()).isNotBlank();
    }

    @Test
    void confirmCallerCanRunExternalWriteTool() {
        ToolRunner runner = runnerWithTestTools();

        ToolResult<String> result = runner.runExternalWrite(
                "idem-1", "U001", "plan-1", "confirmPlan",
                "reserveRestaurant", Map.of("poiId", "P001"));

        assertThat(result.success()).isTrue();
        assertThat(result.effect()).isEqualTo(ToolEffect.EXTERNAL_WRITE);
        assertThat(result.data()).contains("reserved");
    }

    @Test
    void unknownToolReturnsStructuredFailure() {
        ToolRunner runner = runnerWithTestTools();

        ToolResult<String> result = runner.runReadOnly("qa", "missingTool", Map.of());

        assertThat(result.success()).isFalse();
        assertThat(result.toolName()).isEqualTo("missingTool");
        assertThat(result.errorMessage()).contains("Unknown tool");
        assertThat(result.traceId()).isNotBlank();
    }

    private ToolRunner runnerWithTestTools() {
        ToolCatalog catalog = new ToolCatalog();
        catalog.register(new ToolSpec("searchNearby", "Search nearby POIs", ToolEffect.READ_ONLY,
                        Map.class, Map.class, 1_000L, true),
                ignored -> "{\"status\":\"ok\"}");
        catalog.register(new ToolSpec("reserveRestaurant", "Reserve restaurant", ToolEffect.EXTERNAL_WRITE,
                        Map.class, Map.class, 1_000L, false),
                ignored -> "{\"status\":\"reserved\"}");
        return new ToolRunner(catalog, new ObjectMapper());
    }
}
