package com.weekendplanner.engine.tooling;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.weekendplanner.engine.runtime.BackendNoticeSink;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Component
public class ToolRunner {

    private final ToolCatalog catalog;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    public ToolRunner(ToolCatalog catalog, ObjectMapper objectMapper) {
        this.catalog = catalog;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
    }

    public ToolResult<String> run(ToolInvocation<?> invocation, Set<ToolEffect> allowedEffects) {
        long startedAt = System.nanoTime();
        String traceId = UUID.randomUUID().toString();
        if (invocation == null || invocation.toolName() == null || invocation.toolName().isBlank()) {
            return failure("", "Tool name is required", ToolEffect.READ_ONLY, traceId, startedAt);
        }
        ToolCatalog.ToolEntry entry = catalog.find(invocation.toolName()).orElse(null);
        if (entry == null) {
            return failure(invocation.toolName(), "Unknown tool: " + invocation.toolName()
                    + ". Available: " + catalog.names(), ToolEffect.READ_ONLY, traceId, startedAt);
        }
        ToolSpec spec = entry.spec();
        if (allowedEffects != null && !allowedEffects.contains(spec.effect())) {
            return failure(spec.name(), "Tool effect " + spec.effect() + " is not allowed for caller "
                    + invocation.caller(), spec.effect(), traceId, startedAt);
        }
        try {
            String json = invocation.input() instanceof String raw
                    ? raw
                    : objectMapper.writeValueAsString(invocation.input() == null ? java.util.Map.of() : invocation.input());
            CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> entry.executor().execute(json), executor);
            String result = future.get(spec.timeoutMs(), TimeUnit.MILLISECONDS);
            return new ToolResult<>(spec.name(), true, result, null, spec.effect(), traceId, elapsedMs(startedAt));
        } catch (java.util.concurrent.TimeoutException e) {
            BackendNoticeSink.warn("ToolRunner", spec.name() + " timed out after " + spec.timeoutMs() + "ms");
            return failure(spec.name(), "Tool timed out after " + spec.timeoutMs() + "ms", spec.effect(), traceId, startedAt);
        } catch (Exception e) {
            BackendNoticeSink.warn("ToolRunner", spec.name() + " failed: " + e.getMessage());
            return failure(spec.name(), e.getMessage(), spec.effect(), traceId, startedAt);
        }
    }

    public ToolResult<String> runReadOnly(String caller, String toolName, Object input) {
        return run(new ToolInvocation<>(UUID.randomUUID().toString(), null, null, caller, toolName, input),
                Set.of(ToolEffect.READ_ONLY));
    }

    public ToolResult<String> runExternalWrite(String requestId, String userId, String planId, String caller,
                                               String toolName, Object input) {
        return run(new ToolInvocation<>(requestId, userId, planId, caller, toolName, input),
                Set.of(ToolEffect.EXTERNAL_WRITE));
    }

    private ToolResult<String> failure(String toolName, String message, ToolEffect effect, String traceId, long startedAt) {
        return new ToolResult<>(toolName, false, null, message, effect, traceId, elapsedMs(startedAt));
    }

    private long elapsedMs(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }
}
