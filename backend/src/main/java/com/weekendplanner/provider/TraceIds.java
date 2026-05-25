package com.weekendplanner.provider;

import java.util.UUID;

final class TraceIds {

    private TraceIds() {}

    static String traceId(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
