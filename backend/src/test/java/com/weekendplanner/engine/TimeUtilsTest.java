package com.weekendplanner.engine;

import com.weekendplanner.engine.planning.TimeUtils;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TimeUtilsTest {

    @Test
    void isOpenCorrectlyParsesStandardHours() {
        String hours = "09:00-17:00 (周一闭馆)";
        
        assertThat(TimeUtils.isOpen(hours, "10:00")).isTrue();
        assertThat(TimeUtils.isOpen(hours, "09:00")).isTrue();
        assertThat(TimeUtils.isOpen(hours, "17:00")).isTrue();
        
        assertThat(TimeUtils.isOpen(hours, "08:00")).isFalse();
        assertThat(TimeUtils.isOpen(hours, "18:00")).isFalse();
        assertThat(TimeUtils.isOpen(hours, "20:00")).isFalse();
    }

    @Test
    void isOpenCorrectlyParsesMidnightCrossingHours() {
        String hours = "18:00-02:00";
        
        assertThat(TimeUtils.isOpen(hours, "20:00")).isTrue();
        assertThat(TimeUtils.isOpen(hours, "18:00")).isTrue();
        assertThat(TimeUtils.isOpen(hours, "01:00")).isTrue();
        assertThat(TimeUtils.isOpen(hours, "02:00")).isTrue();
        
        assertThat(TimeUtils.isOpen(hours, "17:00")).isFalse();
        assertThat(TimeUtils.isOpen(hours, "12:00")).isFalse();
        assertThat(TimeUtils.isOpen(hours, "03:00")).isFalse();
    }

    @Test
    void isOpenCorrectlyHandles24HoursFallback() {
        assertThat(TimeUtils.isOpen("全天开放", "03:00")).isTrue();
        assertThat(TimeUtils.isOpen("24小时营业", "12:00")).isTrue();
        assertThat(TimeUtils.isOpen("", "12:00")).isTrue();
        assertThat(TimeUtils.isOpen(null, "12:00")).isTrue();
    }

    @Test
    void isOpenDuringWindowCorrectlyChecksOverlaps() {
        String hours = "09:00-17:00";
        // Fully outside
        assertThat(TimeUtils.isOpenDuringWindow(hours, "20:00", "24:00")).isFalse();
        // Fully inside
        assertThat(TimeUtils.isOpenDuringWindow(hours, "10:00", "15:00")).isTrue();
        // Overlapping start
        assertThat(TimeUtils.isOpenDuringWindow(hours, "08:00", "10:00")).isTrue();
        // Overlapping end
        assertThat(TimeUtils.isOpenDuringWindow(hours, "16:00", "20:00")).isTrue();
        // Midnight crossing shop and evening window
        String nightHours = "18:00-02:00";
        assertThat(TimeUtils.isOpenDuringWindow(nightHours, "20:00", "23:00")).isTrue();
        assertThat(TimeUtils.isOpenDuringWindow(nightHours, "12:00", "15:00")).isFalse();
    }
}
