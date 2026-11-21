package com.mtm.citybuilder.block;

import com.mtm.citybuilder.platform.Platform;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.GlassBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * The airport terminals' blocks (placed by core's building.airport): curtain walls, standing-seam roofs, ceiling
 * lights, terrazzo and tile carpet, beam seating, hanging signs and flight boards, boarding bridges' cladding and
 * bellows, baggage belts and escalator steps. Textures, models and states are drawn by
 * {@code tools/textures/airport_textures.py} for this mod.
 */
public final class AirportBlocks {
    /** The curtain walls, drawn translucent (Forge reads that from their models; Fabric from this list). */
    public static final List<Supplier<Block>> TRANSLUCENT = new ArrayList<>();

    private AirportBlocks() {
    }

    public static void register() {
        Platform p = Platform.get();
        for (String tint : new String[]{"gray", "blue", "clear"}) {
            TRANSLUCENT.add(p.registerBlock("curtain_wall_" + tint,
                    () -> new GlassBlock(BlockBehaviour.Properties.copy(Blocks.GLASS))));
        }
        for (String roof : new String[]{"metal_roof", "metal_roof_white"}) {
            p.registerBlock(roof, () -> new RotatedPillarBlock(copy(Blocks.IRON_BLOCK)));
            p.registerBlock(roof + "_slab", () -> new AxisSlabBlock(copy(Blocks.IRON_BLOCK)));
        }
        p.registerBlock("ceiling_light", () -> new CeilingLightBlock(copy(Blocks.WHITE_CONCRETE)
                .lightLevel(s -> 15).noOcclusion()));
        p.registerBlock("terminal_tile", () -> new Block(copy(Blocks.POLISHED_DIORITE)));
        for (String c : new String[]{"blue", "orange", "green"}) {
            p.registerBlock("carpet_tile_" + c, () -> new Block(copy(Blocks.WHITE_WOOL)));
        }
        p.registerBlock("airport_seat", () -> new BeamSeatBlock(copy(Blocks.IRON_BLOCK).noOcclusion()));
        p.registerBlock("wayfinding_sign", () -> new SignBlock(copy(Blocks.IRON_BLOCK).noOcclusion()));
        p.registerBlock("flight_board", () -> new PanelBlock(copy(Blocks.IRON_BLOCK).noOcclusion()
                .lightLevel(s -> 6)));
        p.registerBlock("bridge_cladding", () -> new Block(copy(Blocks.IRON_BLOCK)));
        p.registerBlock("bridge_bellows", () -> new Block(copy(Blocks.BLACK_WOOL)));
        p.registerBlock("baggage_belt", () -> new AxisSlabBlock(copy(Blocks.BLACK_CONCRETE)));
        p.registerBlock("escalator_step", () -> new EscalatorStepBlock(copy(Blocks.IRON_BLOCK)));
    }

    private static BlockBehaviour.Properties copy(Block block) {
        return BlockBehaviour.Properties.copy(block);
    }
}
