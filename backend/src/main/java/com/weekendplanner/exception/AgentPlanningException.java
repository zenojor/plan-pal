package com.weekendplanner.exception;

/**
 * Agent 规划异常
 */
public class AgentPlanningException extends RuntimeException {

    public AgentPlanningException(String message) {
        super(message);
    }

    public AgentPlanningException(String message, Throwable cause) {
        super(message, cause);
    }
}
