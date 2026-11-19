package com.mtm.citybuilder.compat.mtr;

import com.mtm.citybuilder.core.transit.StationInfo;
import com.mtm.citybuilder.core.transit.TransitView;
import mtr.data.RailwayData;
import mtr.data.Station;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.List;

/**
 * The transit network of a level as MTR keeps it. Loading this class needs MTR: reach it only through
 * {@link com.mtm.citybuilder.transit.Transits}.
 */
public final class MtrTransit implements TransitView {
    private final RailwayData data;

    private MtrTransit(RailwayData data) {
        this.data = data;
    }

    /** The network of a level, or null if MTR keeps none for it. */
    public static TransitView of(ServerLevel level) {
        RailwayData data = RailwayData.getInstance(level);
        return data == null ? null : new MtrTransit(data);
    }

    /** Reads MTR's data, which the server thread changes: call it on the server thread. */
    @Override
    public List<StationInfo> stations() {
        List<StationInfo> stations = new ArrayList<>();
        for (Station station : data.stations) {
            List<StationInfo.Area> areas = new ArrayList<>();
            for (int[] rectangle : station.getRectangles()) {
                int[] height = station.getHeight(rectangle);
                areas.add(new StationInfo.Area(
                        Math.min(rectangle[0], rectangle[2]), Math.min(rectangle[1], rectangle[3]),
                        Math.max(rectangle[0], rectangle[2]), Math.max(rectangle[1], rectangle[3]),
                        height == null ? Integer.MIN_VALUE : height[0], height == null ? Integer.MAX_VALUE : height[1]));
            }
            stations.add(new StationInfo(station.id, station.name, station.color, areas));
        }
        return stations;
    }
}
