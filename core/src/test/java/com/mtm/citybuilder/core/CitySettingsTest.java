package com.mtm.citybuilder.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CitySettingsTest {
    @Test
    void defaultsAreValid() {
        assertTrue(CitySettings.DEFAULT.validate().isEmpty(), CitySettings.DEFAULT.validate().toString());
    }

    @Test
    void jsonRoundTrips() {
        CitySettings settings = new CitySettings();
        settings.suitability.waterReach = 500;
        settings.planVersion = 7;
        CitySettings read = CitySettings.fromJson(settings.toJson());
        assertEquals(500, read.suitability.waterReach);
        assertEquals(7, read.planVersion);
        assertEquals(settings.toJson(), read.toJson());
    }

    @Test
    void missingFieldsKeepTheirDefaults() {
        CitySettings read = CitySettings.fromJson("{\"suitability\": {\"plains\": 0.9}}");
        assertEquals(0.9, read.suitability.plains);
        assertEquals(CitySettings.DEFAULT.suitability.hills, read.suitability.hills);
        assertEquals(CitySettings.CURRENT_PLAN_VERSION, read.planVersion);
    }

    @Test
    void invalidSettingsAreRefused() {
        CitySettings settings = new CitySettings();
        settings.general.gridSize = 100;
        assertFalse(settings.validate().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new CityLogic(0L, settings, null));
    }
}
