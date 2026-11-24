package com.mtm.citybuilder.core.building;

import com.mtm.citybuilder.core.building.temple.Model;
import java.util.BitSet;

/** The occupied columns of a placed model in its bounding rectangle. */
public record ModelFootprint(String template, int width, int depth, BitSet columns) {
    public boolean contains(int x, int z) {
        return x >= 0 && z >= 0 && x < width && z < depth && columns.get(x * depth + z);
    }

    public static ModelFootprint of(Model model) {
        int depth = model.depth();
        BitSet bits = new BitSet(model.width() * depth);
        model.forEach((x, y, z, s) -> bits.set((x - model.minX()) * depth + z - model.minZ()));
        return new ModelFootprint(null, model.width(), depth, bits);
    }
}
