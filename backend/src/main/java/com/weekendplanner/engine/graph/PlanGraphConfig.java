package com.weekendplanner.engine.graph;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class PlanGraphConfig {

    @Value("${agent.graph.enabled:true}")
    private boolean enabled;

    @Value("${agent.graph.max-model-calls:5}")
    private int maxModelCalls;

    @Value("${agent.graph.tool-timeout-ms:3000}")
    private long toolTimeoutMs;

    public boolean enabled() {
        return enabled;
    }

    public int maxModelCalls() {
        return maxModelCalls;
    }

    public long toolTimeoutMs() {
        return toolTimeoutMs;
    }
}
