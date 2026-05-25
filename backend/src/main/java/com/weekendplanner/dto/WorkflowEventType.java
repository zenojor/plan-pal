package com.weekendplanner.dto;

public final class WorkflowEventType {
    public static final String PLAN_STARTED = "PLAN_STARTED";
    public static final String INTENT_EXTRACTED = "INTENT_EXTRACTED";
    public static final String PLAN_NARRATIVE = "PLAN_NARRATIVE";
    public static final String WEATHER_CHECKED = "WEATHER_CHECKED";
    public static final String CANDIDATES_SEARCHING = "CANDIDATES_SEARCHING";
    public static final String CANDIDATES_FOUND = "CANDIDATES_FOUND";
    public static final String AVAILABILITY_CHECKED = "AVAILABILITY_CHECKED";
    public static final String SEGMENT_PLANNED = "SEGMENT_PLANNED";
    public static final String CONFLICT_DETECTED = "CONFLICT_DETECTED";
    public static final String REPAIR_OPTIONS_READY = "REPAIR_OPTIONS_READY";
    public static final String PLAN_ASSEMBLED = "PLAN_ASSEMBLED";
    public static final String PLAN_FINISHED = "PLAN_FINISHED";
    public static final String PLAN_FAILED = "PLAN_FAILED";

    private WorkflowEventType() {
    }
}
