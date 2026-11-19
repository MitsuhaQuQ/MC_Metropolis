package com.mtm.citybuilder.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Every setting of the city logic, grouped as in the config file. A world keeps the settings it was created with, and
 * {@link #planVersion} with them: plans never change under a world already generated (see the design document,
 * "planning algorithm version lock").
 * <p>
 * Gson is only touched inside {@link Json}, so this class loads where Gson is absent.
 */
public final class CitySettings {
    /** The planning algorithm this build writes into new worlds. */
    public static final int CURRENT_PLAN_VERSION = 1;

    public static final CitySettings DEFAULT = new CitySettings();

    /** Version of the planning algorithm a world plans with, fixed when the world is created. */
    public int planVersion = CURRENT_PLAN_VERSION;
    public General general = new General();
    public Suitability suitability = new Suitability();

    public static final class General {
        /** Edge of a logic region in blocks: the unit results are cached in, never a boundary of any rule. */
        public int regionSize = 4096;
        /** Edge of a cell of the coarse grid in blocks, which the region-scale layers sample terrain on. */
        public int gridSize = 64;
    }

    /** L1: how well suited a place is for a city, from 0 to 1. */
    public static final class Suitability {
        /** Grade at or under which land counts as flat (1 = 100%). */
        public double flatGrade = 0.02;
        /** Grade at or over which land is unsuited. */
        public double steepGrade = 0.10;
        /** Score of each kind of land. */
        public double plains = 1.0;
        public double hills = 0.6;
        public double plateau = 0.75;
        public double mountains = 0.1;
        /**
         * Radius in blocks over which the kind of land is averaged. Kinds of land are classes, so their scores step at
         * the borders between them; averaging turns the step into a gradual change.
         */
        public double landformBlend = 192.0;
        /** Height above the sea up to which height costs nothing. */
        public double highLand = 300.0;
        /** Height above {@link #highLand} over which the score falls to {@link #highLandFloor}. */
        public double highLandFade = 500.0;
        /** Lowest share of the score high land keeps: basins up in the mountains are still places for towns. */
        public double highLandFloor = 0.5;
        /** How far water raises the score: rivers, lakes and the sea within this many blocks of the bank. */
        public double waterReach = 384.0;
        /** Width at which a river counts in full; narrower ones count less. */
        public double riverFullWidth = 48.0;
        /** Weight of water in the score: 0.25 lets a place by the water score a quarter higher than one without. */
        public double waterWeight = 0.25;
    }

    /** Problems with the settings, empty when they are usable. */
    public List<String> validate() {
        List<String> problems = new ArrayList<>();
        if (general.gridSize <= 0 || general.regionSize <= 0 || general.regionSize % general.gridSize != 0) {
            problems.add("general.regionSize must be a positive multiple of general.gridSize");
        }
        if (suitability.steepGrade <= suitability.flatGrade) {
            problems.add("suitability.steepGrade must be above suitability.flatGrade");
        }
        if (suitability.waterReach < 0 || suitability.highLandFade <= 0) {
            problems.add("suitability.waterReach must not be negative, suitability.highLandFade must be positive");
        }
        return problems;
    }

    /** The settings as JSON, which also tells apart worlds created with different settings. */
    public String toJson() {
        return Json.write(this);
    }

    public static CitySettings fromJson(String json) {
        return Json.read(json);
    }

    /** Gson lives here only: the class is loaded when JSON is read or written, not with the settings. */
    private static final class Json {
        private static final com.google.gson.Gson GSON = new com.google.gson.GsonBuilder().setPrettyPrinting().create();

        static String write(CitySettings settings) {
            return GSON.toJson(settings);
        }

        static CitySettings read(String json) {
            CitySettings settings = GSON.fromJson(json, CitySettings.class);
            return settings == null ? new CitySettings() : settings;
        }
    }
}
