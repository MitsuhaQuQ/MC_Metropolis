package com.mtm.citybuilder.core.building.rail;

import com.mtm.citybuilder.core.building.temple.Model;

import java.util.List;

/**
 * One storey of an underground station, or any piece of one, in its own frame: a concourse, a cut-and-cover platform
 * level, a shield-bored platform level, a transfer passage, or a whole station (any of them composed). Pieces are put
 * together by {@link UndergroundComplex}, each at a {@link Placement}; where they overlap, what lies inside one (air,
 * floors, platforms) wins over the other's shell, so two boxes that meet open into one.
 */
public interface Storey {
    /** Everything but the flights. Inside it everything is written (air where empty); outside, the ground stays. */
    Model body();

    /**
     * The flights laid after all the pieces are put together (stairs through the ground take what lies round them
     * then), each in a frame of this storey's. {@code ground} is the ground's top block in this storey's frame.
     */
    default List<Placed> flights(Ground ground) {
        return List.of();
    }

    /** A flight in a frame: {@link Placement#ORIGIN} for the storey's own. */
    record Placed(Flights.Flight flight, Placement at) {
        public Placed(Flights.Flight flight) {
            this(flight, Placement.ORIGIN);
        }

        Placed within(Placement outer) {
            return new Placed(flight, outer.after(at));
        }
    }
}
