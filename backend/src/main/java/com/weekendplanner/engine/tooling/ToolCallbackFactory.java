package com.weekendplanner.engine.tooling;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;

@Component
public class ToolCallbackFactory {

    private final ToolCatalog catalog;
    private final ToolRunner runner;

    public ToolCallbackFactory(ToolCatalog catalog, ToolRunner runner) {
        this.catalog = catalog;
        this.runner = runner;
    }

    public List<ToolCallback> callbacksFor(Set<ToolEffect> allowedEffects, String caller) {
        return catalog.tools().stream()
                .filter(spec -> allowedEffects == null || allowedEffects.contains(spec.effect()))
                .map(spec -> callback(spec, allowedEffects, caller))
                .toList();
    }

    private ToolCallback callback(ToolSpec spec, Set<ToolEffect> allowedEffects, String caller) {
        BiFunction<Object, ToolContext, String> function = (input, context) -> {
            String userId = metadata(context, "userId");
            String planId = metadata(context, "planId");
            String requestId = metadata(context, "requestId");
            ToolResult<String> result = runner.run(new ToolInvocation<>(
                    requestId, userId, planId, caller, spec.name(), input), allowedEffects);
            return result.success() ? result.data() : "{\"success\":false,\"error\":\"" + safe(result.errorMessage()) + "\"}";
        };
        return FunctionToolCallback.builder(spec.name(), function)
                .description(spec.description())
                .inputType(spec.inputType())
                .build();
    }

    private String metadata(ToolContext context, String key) {
        if (context == null || context.getContext() == null || !context.getContext().containsKey(key)) return null;
        Object value = context.getContext().get(key);
        return value == null ? null : String.valueOf(value);
    }

    private String safe(String value) {
        return value == null ? "" : value.replace("\"", "'");
    }
}
