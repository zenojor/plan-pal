package com.weekendplanner.engine.planning;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TimeUtils {

    private static final Pattern TIME_RANGE_PATTERN = Pattern.compile("(\\d{1,2}):(\\d{2})\\s*-\\s*(\\d{1,2}):(\\d{2})");

    public static boolean isOpen(String businessHours, String targetTime) {
        if (businessHours == null || businessHours.isBlank() || targetTime == null || targetTime.isBlank()) {
            return true;
        }

        String normalizedHours = businessHours.trim().toLowerCase(Locale.ROOT);
        if (normalizedHours.contains("全天") || normalizedHours.contains("24小时") || normalizedHours.contains("24h") || normalizedHours.contains("open 24")) {
            return true;
        }

        Matcher matcher = TIME_RANGE_PATTERN.matcher(businessHours);
        if (matcher.find()) {
            try {
                int startHour = Integer.parseInt(matcher.group(1));
                int startMin = Integer.parseInt(matcher.group(2));
                int endHour = Integer.parseInt(matcher.group(3));
                int endMin = Integer.parseInt(matcher.group(4));

                int start = startHour * 60 + startMin;
                int end = endHour * 60 + endMin;

                // Handle targetTime. Clean it to handle formats like "20:00"
                String cleanTargetTime = targetTime.trim();
                if (cleanTargetTime.contains(" ")) {
                    // split just in case of date time
                    cleanTargetTime = cleanTargetTime.split("\\s+")[1];
                }
                String[] targetParts = cleanTargetTime.split(":");
                if (targetParts.length < 2) {
                    return true;
                }
                int targetHour = Integer.parseInt(targetParts[0].trim());
                int targetMin = Integer.parseInt(targetParts[1].trim());
                int target = targetHour * 60 + targetMin;

                if (start <= end) {
                    return target >= start && target <= end;
                } else {
                    // Crosses midnight, e.g. 18:00 - 02:00
                    return target >= start || target <= end;
                }
            } catch (Exception e) {
                // Fallback to true on parsing error
                return true;
            }
        }

        return true;
    }

    public static boolean isOpenDuringWindow(String businessHours, String startTime, String endTime) {
        if (businessHours == null || businessHours.isBlank() || startTime == null || startTime.isBlank() || endTime == null || endTime.isBlank()) {
            return true;
        }
        int start = toMinutes(startTime);
        int end = toMinutes(endTime);
        if (end < start) {
            end += 24 * 60;
        }
        for (int m = start; m <= end; m += 30) {
            String targetTime = String.format(Locale.ROOT, "%02d:%02d", (m / 60) % 24, m % 60);
            if (isOpen(businessHours, targetTime)) {
                return true;
            }
        }
        return false;
    }

    private static int toMinutes(String time) {
        if (time == null || !time.contains(":")) return 0;
        String[] parts = time.trim().split(":");
        try {
            return Integer.parseInt(parts[0].trim()) * 60 + Integer.parseInt(parts[1].trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
