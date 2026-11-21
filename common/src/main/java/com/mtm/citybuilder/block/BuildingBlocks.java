package com.mtm.citybuilder.block;

import com.mtm.citybuilder.platform.Platform;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * The buildings' own blocks (placed by core's building layer, picked by its {@code Palette}): windows, joined to the
 * windows beside them as panes are, but framed and set back: the glass two pixels thick in the middle of the block, a
 * head along its top, a sill a pixel proud of it along its foot, and a mullion at the middle, so a wall keeps its
 * depth round its windows. The building layer joins them along their walls, round corners and across the steps of a
 * wall at an angle to the blocks ({@code world.CityBuildings}). Textures, models and states are drawn by
 * {@code tools/textures/window_textures.py}.
 */
public final class BuildingBlocks {
    /** The frames and the glass the windows come in. */
    public static final String[] FRAMES = {"aluminum", "dark", "wood", "white"};
    public static final String[] GLASS = {"clear", "blue", "gray"};
    /** The windows, drawn translucent (Forge reads that from their models; Fabric from this list). */
    public static final List<Supplier<Block>> TRANSLUCENT = new ArrayList<>();

    private BuildingBlocks() {
    }

    public static void register() {
        Platform p = Platform.get();
        for (String frame : FRAMES) {
            for (String glass : GLASS) {
                TRANSLUCENT.add(p.registerBlock("window_" + frame + "_" + glass,
                        () -> new WindowBlock(BlockBehaviour.Properties.copy(Blocks.GLASS_PANE))));
            }
        }
    }
}
