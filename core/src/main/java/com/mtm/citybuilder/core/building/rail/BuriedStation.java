package com.mtm.citybuilder.core.building.rail;

import com.mtm.citybuilder.core.building.temple.Model;

import java.util.List;

/**
 * A station underground: it writes air wherever it takes the ground away, and its stairs are flights laid after it
 * ({@link CurvedStation#underground} lays any of them on a curve).
 */
public interface BuriedStation {
    Model buildWithoutFlights();

    List<Flights.Flight> flights();

    /** The rails' level (the platforms' top block). */
    int level();

    CrossSection section();
}
