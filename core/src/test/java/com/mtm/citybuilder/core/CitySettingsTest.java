package com.mtm.citybuilder.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CitySettingsTest {
    @Test
    void buildingLayoutStaysWithTheSavedPlans() {
        for (boolean existingPlans : new boolean[]{false, true}) {
            var store = new com.mtm.citybuilder.core.plan.MemoryPlanStore();
            CitySettings first = CitySettings.forBuildingLayout(store, existingPlans);
            assertEquals(existingPlans ? 1.6 : 2.0, first.plots.frontageScale);
            assertEquals(existingPlans ? 1.2 : 1.45, first.plots.depthScale);
            assertEquals(existingPlans ? 40 : 64, first.transit.mainWidth);
            assertEquals(existingPlans ? 0 : 1, first.buildings.residentialGroundExtra);
            assertEquals(!existingPlans, first.plazas.entranceApproach);
            assertEquals(!existingPlans, first.plazas.lowerEntrance);
            assertEquals(!existingPlans, first.buildings.plateauCastleKeep);
            assertEquals(!existingPlans, first.plots.followStreetEdges);
            assertEquals(!existingPlans, first.buildings.terracedBaileys);
            assertEquals(!existingPlans, first.buildings.batteredKeep);
            assertEquals(!existingPlans, first.buildings.fillDowntown);
            assertEquals(!existingPlans, first.buildings.facilityAtStreetLevel);
            assertEquals(!existingPlans, first.transit.mezzanineExits);
            assertEquals(!existingPlans, first.transit.portalOverRails);
            assertEquals(!existingPlans, first.buildings.turretsOnGround);
            assertEquals(!existingPlans, first.buildings.castleRamps);
            assertEquals(!existingPlans, first.buildings.clearOfStreets);
            assertEquals(!existingPlans, first.streets.teeEnds);
            assertEquals(!existingPlans, first.streets.dryEnds);
            assertEquals(!existingPlans, first.streets.bridgeEnds);
            assertEquals(!existingPlans, first.transit.smoothStations);
            assertEquals(existingPlans ? 0.0 : 40.0, first.plots.parcelFrontage);
            assertEquals(existingPlans ? 0 : 1, first.buildings.retailGroundExtra);
            assertEquals(first.toJson(), CitySettings.forBuildingLayout(store, !existingPlans).toJson());
        }
        var previousFrontage = new com.mtm.citybuilder.core.plan.MemoryPlanStore();
        previousFrontage.save("layout/building_scale", new byte[]{8});
        assertFalse(CitySettings.forBuildingLayout(previousFrontage,true).plots.followStreetEdges);
        var flatBaileys = new com.mtm.citybuilder.core.plan.MemoryPlanStore();
        flatBaileys.save("layout/building_scale", new byte[]{9});
        assertFalse(CitySettings.forBuildingLayout(flatBaileys,true).buildings.terracedBaileys);
        var sparseDowntown = new com.mtm.citybuilder.core.plan.MemoryPlanStore();
        sparseDowntown.save("layout/building_scale", new byte[]{10});
        assertFalse(CitySettings.forBuildingLayout(sparseDowntown,true).buildings.fillDowntown);
        assertEquals(0.0, CitySettings.forBuildingLayout(sparseDowntown,true).plots.parcelFrontage);
        var raisedFacilities = new com.mtm.citybuilder.core.plan.MemoryPlanStore();
        raisedFacilities.save("layout/building_scale", new byte[]{11});
        assertFalse(CitySettings.forBuildingLayout(raisedFacilities,true).buildings.facilityAtStreetLevel);
        assertFalse(CitySettings.forBuildingLayout(raisedFacilities,true).transit.mezzanineExits);
        assertTrue(CitySettings.forBuildingLayout(raisedFacilities,true).buildings.fillDowntown);
        var steppedWays = new com.mtm.citybuilder.core.plan.MemoryPlanStore();
        steppedWays.save("layout/building_scale", new byte[]{12});
        assertFalse(CitySettings.forBuildingLayout(steppedWays,true).buildings.castleRamps);
        assertFalse(CitySettings.forBuildingLayout(steppedWays,true).buildings.clearOfStreets);
        var squareTees = new com.mtm.citybuilder.core.plan.MemoryPlanStore();
        squareTees.save("layout/building_scale", new byte[]{13});
        assertFalse(CitySettings.forBuildingLayout(squareTees,true).streets.teeEnds);
        assertFalse(CitySettings.forBuildingLayout(squareTees,true).streets.dryEnds);
        assertFalse(CitySettings.forBuildingLayout(squareTees,true).streets.bridgeEnds);
        assertFalse(CitySettings.forBuildingLayout(squareTees,true).transit.smoothStations);
        assertTrue(CitySettings.forBuildingLayout(squareTees,true).buildings.clearOfStreets);
        assertTrue(CitySettings.forBuildingLayout(steppedWays,true).buildings.turretsOnGround);
        assertTrue(CitySettings.forBuildingLayout(sparseDowntown,true).buildings.batteredKeep);
        var firstKeep = new com.mtm.citybuilder.core.plan.MemoryPlanStore();
        firstKeep.save("layout/building_scale", new byte[]{6});
        CitySettings olderKeep = CitySettings.forBuildingLayout(firstKeep, true);
        assertTrue(olderKeep.buildings.plateauCastleKeep);
        assertFalse(olderKeep.buildings.largeCastleGate);
        var previous = new com.mtm.citybuilder.core.plan.MemoryPlanStore();
        previous.save("layout/building_scale", new byte[]{2});
        CitySettings second = CitySettings.forBuildingLayout(previous, true);
        assertEquals(2.0, second.plots.frontageScale);
        assertEquals(0, second.buildings.residentialGroundExtra);
        assertFalse(second.plazas.entranceApproach);
        var third = new com.mtm.citybuilder.core.plan.MemoryPlanStore();
        third.save("layout/building_scale", new byte[]{3});
        CitySettings residential = CitySettings.forBuildingLayout(third, true);
        assertEquals(1, residential.buildings.residentialGroundExtra);
        assertFalse(residential.plazas.entranceApproach);
        assertEquals(0, residential.buildings.retailGroundExtra);
        var fourth = new com.mtm.citybuilder.core.plan.MemoryPlanStore();
        fourth.save("layout/building_scale", new byte[]{4});
        CitySettings retail = CitySettings.forBuildingLayout(fourth, true);
        assertTrue(retail.plazas.entranceApproach);
        assertFalse(retail.plazas.lowerEntrance);
        assertEquals(1, retail.buildings.retailGroundExtra);
    }

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
