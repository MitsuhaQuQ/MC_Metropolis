package com.mtm.citybuilder.core.settlement;

import com.mtm.citybuilder.core.CitySettings;
import com.mtm.citybuilder.core.terrain.RiverSample;
import com.mtm.citybuilder.core.terrain.TerrainView;

/**
 * Where within good land a town or village prefers to stand, as real ones do: by a river but raised above it (a natural
 * levee, a terrace or fan edge), or at the foot of hills; not down on a flood plain. Reads the smooth ground and lakes
 * only: the detailed ground costs MTM_worldgen's erosion, milliseconds a point, far too much for planning.
 */
public final class SitePreference {
    private static final int RING = 8;

    /**
     * @param bank     distance to the nearest river's bank, infinite when none is near
     * @param rise     height above the lowest ground or water surface within the ring
     * @param climb    how much higher the highest ground at the foot reach is
     * @param value    the site value, from -1 to 1
     */
    public record Site(double bank, double rise, double climb, double value) {
    }

    private final CitySettings.Site config;
    private final TerrainView terrain;

    public SitePreference(CitySettings settings, TerrainView terrain) {
        this.config = settings.settlements.site;
        this.terrain = terrain;
    }

    public Site evaluate(double x, double z) {
        double here = ground(x, z);
        double lowest = here;
        double highest = here;
        for (int k = 0; k < RING; k++) {
            double angle = 2.0 * Math.PI * k / RING;
            double cos = Math.cos(angle);
            double sin = Math.sin(angle);
            lowest = Math.min(lowest, ground(x + cos * config.ringRadius, z + sin * config.ringRadius));
            highest = Math.max(highest, ground(x + cos * config.footReach, z + sin * config.footReach));
        }
        double rise = here - lowest;
        double climb = highest - here;
        RiverSample river = terrain.nearestRiver(x, z);
        double bank = river == null ? Double.POSITIVE_INFINITY : river.bankDistance();

        // A river draws settlements from riverNear to riverFar, less closer in and further out
        double draw;
        if (bank < config.riverNear) {
            draw = bank / config.riverNear;
        } else if (bank <= config.riverFar) {
            draw = 1.0;
        } else {
            draw = Math.max(0.0, 1.0 - (bank - config.riverFar) / (config.riverFade - config.riverFar));
        }
        double raised = clamp(rise / config.raisedRise);
        double flood = bank < config.riverFade ? clamp(1.0 - rise / config.floodRise) : 0.0;
        double foot = clamp(2.0 * climb / config.footRise - 1.0);
        double value = Math.max(draw * raised, foot) - config.floodPenalty * flood;
        return new Site(bank, rise, climb, Math.max(-1.0, Math.min(1.0, value)));
    }

    /** The smooth ground at a position, or the surface of a lake over it: what floods. */
    private double ground(double x, double z) {
        double ground = Math.max(terrain.smoothHeight(x, z), terrain.seaLevel());
        double lake = terrain.lakeLevel(x, z);
        return Double.isNaN(lake) ? ground : Math.max(ground, lake);
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
