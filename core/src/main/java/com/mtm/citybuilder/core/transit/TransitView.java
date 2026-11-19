package com.mtm.citybuilder.core.transit;

import java.util.List;

/**
 * The transit network the city logic plans around. Answers are copies taken when asked: they do not change as the
 * network does, and can be kept and read on any thread.
 */
public interface TransitView {
    /** Every station, in no particular order. */
    List<StationInfo> stations();
}
