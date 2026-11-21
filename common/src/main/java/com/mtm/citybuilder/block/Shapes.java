package com.mtm.citybuilder.block;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Block shapes from boxes in sixteenths, turned about the block's vertical axis. */
final class Shapes {
    private Shapes() {
    }

    /** The boxes {x0, y0, z0, x1, y1, z1} given for facing north, turned to face {@code d}; a full block if none. */
    static VoxelShape turned(double[][] boxes, Direction d) {
        if (boxes.length == 0) {
            return net.minecraft.world.phys.shapes.Shapes.block();
        }
        VoxelShape out = net.minecraft.world.phys.shapes.Shapes.empty();
        // Quarter turns clockwise (seen from above) from north: north 0, east 1, south 2, west 3.
        int turns = (d.get2DDataValue() + 2) % 4;
        for (double[] b : boxes) {
            double x0 = b[0], z0 = b[2], x1 = b[3], z1 = b[5];
            for (int i = 0; i < turns; i++) { // (x, z) -> (16 - z, x)
                double nx0 = 16 - z1, nx1 = 16 - z0;
                z0 = x0;
                z1 = x1;
                x0 = nx0;
                x1 = nx1;
            }
            out = net.minecraft.world.phys.shapes.Shapes.or(out, Block.box(x0, b[1], z0, x1, b[4], z1));
        }
        return out;
    }
}
