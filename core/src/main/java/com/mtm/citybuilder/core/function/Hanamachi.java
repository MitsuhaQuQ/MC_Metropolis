package com.mtm.citybuilder.core.function;

import java.util.List;

/**
 * A historic pleasure quarter (hanamachi, chaya-gai) of a town's old town: a small area kept as it was, laid along one
 * street. Its buildings (L8) face the spine: a lot's frontage runs along {@code spine} from {@link
 * com.mtm.citybuilder.core.plot.TownPlots#chainPoint} at 0 to the chain's length, both sides; the short lanes that
 * leave it ({@code branches}) are lined with the quarter's buildings too, facing those lanes.
 *
 * @param id       stable identity from the town's hash
 * @param style    whose teahouses line it, choosing the buildings (L8)
 * @param spine    the street chain the quarter is laid along
 * @param branches side lanes that leave the spine and belong to the quarter
 * @param x        the spine's middle
 * @param z        the spine's middle
 * @param length   the spine's length
 */
public record Hanamachi(long id, Style style, int spine, List<Integer> branches, double x, double z, double length) {
    public Hanamachi {
        branches = List.copyOf(branches);
    }

    /** The quarter's look. */
    public enum Style {
        /**
         * Kyoto's (Gion, Kamishichiken, Pontocho): machiya teahouses with bengara lattices and inuyarai, near the
         * middle of the old town, at a great shrine's or temple's gate or along a canal.
         */
        KYOTO,
        /**
         * Kanazawa's (Higashi Chaya, Nishi Chaya, Kazuemachi): two-storey chaya with fine kimusuko lattices and an
         * open upper floor, a castle town's quarters at the old town's outer edge, along the river across from the
         * castle.
         */
        KANAZAWA
    }
}
