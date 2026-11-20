package com.mtm.citybuilder.core.rail;

/**
 * A node of the high-speed spine: the middle of a large lowland, chosen from the continent's height alone.
 *
 * @param score how much of the land around is lowland, from 0 to 1
 */
public record SpineNode(long id, int cellX, int cellZ, double x, double z, double score) {
    boolean beats(SpineNode other) {
        return score > other.score || (score == other.score && id > other.id);
    }
}
