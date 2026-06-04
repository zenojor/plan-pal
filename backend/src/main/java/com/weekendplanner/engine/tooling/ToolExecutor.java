package com.weekendplanner.engine.tooling;

@FunctionalInterface
public interface ToolExecutor {
    String execute(String parametersJson);
}
