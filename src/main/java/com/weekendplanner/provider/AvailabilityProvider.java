package com.weekendplanner.provider;

import com.weekendplanner.dto.CheckResponse;

public interface AvailabilityProvider {

    CheckResponse checkAvailability(String poiId, String targetTime, int headcount);
}
