package com.mtm.citybuilder.block;

import com.mtm.citybuilder.platform.Platform;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.GlassBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * The buildings' own blocks (placed by core's building layer, picked by its {@code Palette}): windows, a whole block of
 * glazing with its frame along the top and bottom and a mullion down one edge, so windows side by side are one band of
 * glass and turn a corner unbroken (panes, set in the middle of their blocks, cannot: two meeting corner to corner on
 * a wall at an angle do not join). Glass in every other way: light passes, and two windows side by side hide the faces
 * between them. Textures, models and states are drawn by {@code tools/textures/window_textures.py}.
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
                        () -> new GlassBlock(BlockBehaviour.Properties.copy(Blocks.GLASS))));
            }
        }
    }
}
