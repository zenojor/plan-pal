package com.weekendplanner.engine;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "agent.runtime")
public class AgentRuntimeProperties {

    private long sseTimeoutMs = 300_000L;
    private int candidateLimit = 3;
    private int candidateSetRetention = 5;
    private int recentEventRetention = 10;
    private int autoRecommendMinMinutes = 60;
    private int dinnerStartMinutes = 17 * 60 + 30;
    private int drinksStartMinutes = 21 * 60;
    private String selectedPoiPrefix = "SELECTED_POI:";
    private String candidateIdPrefix = "candidate-";

    public long getSseTimeoutMs() {
        return sseTimeoutMs;
    }

    public void setSseTimeoutMs(long sseTimeoutMs) {
        this.sseTimeoutMs = sseTimeoutMs;
    }

    public int getCandidateLimit() {
        return candidateLimit;
    }

    public void setCandidateLimit(int candidateLimit) {
        this.candidateLimit = candidateLimit;
    }

    public int getCandidateSetRetention() {
        return candidateSetRetention;
    }

    public void setCandidateSetRetention(int candidateSetRetention) {
        this.candidateSetRetention = candidateSetRetention;
    }

    public int getRecentEventRetention() {
        return recentEventRetention;
    }

    public void setRecentEventRetention(int recentEventRetention) {
        this.recentEventRetention = recentEventRetention;
    }

    public int getAutoRecommendMinMinutes() {
        return autoRecommendMinMinutes;
    }

    public void setAutoRecommendMinMinutes(int autoRecommendMinMinutes) {
        this.autoRecommendMinMinutes = autoRecommendMinMinutes;
    }

    public int getDinnerStartMinutes() {
        return dinnerStartMinutes;
    }

    public void setDinnerStartMinutes(int dinnerStartMinutes) {
        this.dinnerStartMinutes = dinnerStartMinutes;
    }

    public int getDrinksStartMinutes() {
        return drinksStartMinutes;
    }

    public void setDrinksStartMinutes(int drinksStartMinutes) {
        this.drinksStartMinutes = drinksStartMinutes;
    }

    public String getSelectedPoiPrefix() {
        return selectedPoiPrefix;
    }

    public void setSelectedPoiPrefix(String selectedPoiPrefix) {
        this.selectedPoiPrefix = selectedPoiPrefix;
    }

    public String getCandidateIdPrefix() {
        return candidateIdPrefix;
    }

    public void setCandidateIdPrefix(String candidateIdPrefix) {
        this.candidateIdPrefix = candidateIdPrefix;
    }
}
