package com.mtm.citybuilder.core.building.landmark;

/**
 * What a generated landmark is, for the building library: the city logic picks templates by this when it fills a
 * site (a cape or harbour mouth of a port town, a sports park).
 */
public enum Landmark {
    /** 灯台: a tall coastal lighthouse on a cape, a tapering white tower with a gallery and lantern room. */
    LIGHTHOUSE(Category.LIGHTHOUSE, "灯台"),
    /** 防波堤灯台: a short light at the end of a breakwater, red or white, marking a harbour mouth. */
    HARBOUR_LIGHT(Category.LIGHTHOUSE, "防波堤灯台"),
    /** 球技場: a football (or rugby) stadium, stands round a 105 × 68 m pitch, a roof over the main stand. */
    STADIUM(Category.SPORTS, "球技場"),
    /** 野球場: a baseball stadium, infield stands with a roof behind home plate, a grass bank in the outfield. */
    BALLPARK(Category.SPORTS, "野球場"),
    /** 陸上競技場: a municipal athletics ground, a 400 m track round a pitch, a small main stand and grass banks. */
    ATHLETICS_GROUND(Category.SPORTS, "陸上競技場");

    /** The library category, as in {@code tools/assets/plateau_voxel.py}'s labels. */
    public enum Category {
        LIGHTHOUSE("lighthouse"), SPORTS("sports");

        private final String label;

        Category(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private final Category category;
    private final String japanese;

    Landmark(Category category, String japanese) {
        this.category = category;
        this.japanese = japanese;
    }

    public Category category() {
        return category;
    }

    /** The Japanese name, for labels. */
    public String japanese() {
        return japanese;
    }
}
