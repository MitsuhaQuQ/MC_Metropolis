package com.mtm.citybuilder.core.transit;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StationInfoTest {
    @Test
    void areaBoundsAreInclusive() {
        StationInfo.Area area = new StationInfo.Area(0, 0, 10, 20, 60, 70);
        assertTrue(area.contains(0, 0));
        assertTrue(area.contains(10, 20));
        assertFalse(area.contains(11, 20));
        assertTrue(area.contains(5, 60, 5));
        assertFalse(area.contains(5, 71, 5));
    }

    @Test
    void areasAreCopied() {
        List<StationInfo.Area> areas = new ArrayList<>();
        areas.add(new StationInfo.Area(0, 0, 1, 1, Integer.MIN_VALUE, Integer.MAX_VALUE));
        StationInfo station = new StationInfo(1L, "Test|测试", 0xFF0000, areas);
        areas.clear();
        assertEquals(1, station.areas().size());
    }
}
