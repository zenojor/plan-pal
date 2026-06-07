package com.weekendplanner.provider;

import com.weekendplanner.dto.CheckResponse;
import com.weekendplanner.dto.PoiDto;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

@Component
public class SandboxAvailabilityProvider implements AvailabilityProvider {

    private final PoiProvider poiProvider;

    public SandboxAvailabilityProvider(PoiProvider poiProvider) {
        this.poiProvider = poiProvider;
    }

    @Override
    public CheckResponse checkAvailability(String poiId, String targetTime, int headcount) {
        String traceId = TraceIds.traceId("avail");
        if ("P002".equalsIgnoreCase(poiId)) {
            return new CheckResponse(poiId, "QUEUED", 90, true, "sandbox", traceId, "", "High queue time", poiId);
        }
        if ("P008".equalsIgnoreCase(poiId)) {
            return new CheckResponse(poiId, "AVAILABLE", 0, false, "sandbox", traceId, "", "Available", poiId);
        }
        if (List.of("P005", "P013", "P014", "P021", "P022", "P024", "P025", "P028").contains(poiId)) {
            return new CheckResponse(poiId, "AVAILABLE", 5, false, "sandbox", traceId, "", "Available", poiId);
        }
        if (List.of("P023", "P026", "P027", "P029").contains(poiId)) {
            return new CheckResponse(poiId, "AVAILABLE", 15, true, "sandbox", traceId, "", "Available", poiId);
        }
        if (List.of("P003", "P004", "P007", "P018").contains(poiId)) {
            return new CheckResponse(poiId, "AVAILABLE", 8, false, "sandbox", traceId, "", "Available", poiId);
        }
        if (List.of("P030", "P031", "H001", "H002", "H003", "S001", "S002", "S003").contains(poiId)) {
            return new CheckResponse(poiId, "AVAILABLE", 5, false, "sandbox", traceId, "", "Available", poiId);
        }

        Optional<PoiDto> poiOpt = poiProvider.findById(poiId);
        if (poiOpt.isEmpty()) {
            return new CheckResponse(poiId, "UNKNOWN", 0, false, "sandbox", traceId, "RESOURCE_UNAVAILABLE",
                    "POI not found", poiId);
        }
        if (isOpenPublicSpace(poiOpt.get())) {
            return new CheckResponse(poiId, "AVAILABLE", 0, false, "sandbox", traceId, "",
                    "Open public space", poiId);
        }

        int hash = Math.abs((poiId + targetTime + headcount).hashCode());
        int queueTime = hash % 60;
        String status = queueTime > 30 ? "QUEUED" : "AVAILABLE";
        return new CheckResponse(poiId, status, queueTime, queueTime > 20, "sandbox", traceId, "",
                status.equals("AVAILABLE") ? "Available" : "Queued", poiId);
    }

    private boolean isOpenPublicSpace(PoiDto poi) {
        if (poi == null || poi.tags() == null) return false;
        Set<String> tags = new HashSet<>();
        for (String tag : poi.tags()) {
            if (tag != null) tags.add(tag.toLowerCase(Locale.ROOT));
        }
        return tags.contains("free")
                && (tags.contains("outdoor")
                || tags.contains("citywalk")
                || tags.contains("park")
                || tags.contains("nature"));
    }
}
