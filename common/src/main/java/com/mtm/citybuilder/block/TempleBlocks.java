package com.mtm.citybuilder.block;

import com.mtm.citybuilder.platform.Platform;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockBehaviour.Properties;

import java.util.function.Supplier;

/**
 * Decoration for temples and shrines: their grounds (stone paving, white gravel, shrine pebbles), gardens (moss,
 * raked gravel and its rings round rocks, stepping stones, garden rocks, split-bamboo fences) and interiors (tatami,
 * fusuma, shoji, carved transoms, coffered ceilings, cushions, incense burners, paper lanterns, sacred ropes, offering
 * boxes). Textures and models are drawn by {@code tools/textures/temple_blocks.py}; the generators in
 * {@code core.building.temple} name them by id ({@code core.building.temple.Decor}).
 */
public final class TempleBlocks {
    public static Supplier<Block> ISHIDATAMI, SHIRASU, TAMAJARI, KOKE, RAKED_GRAVEL, RAKED_GRAVEL_RING, TOBIISHI,
            NIWAISHI, BAMBOO_FENCE, TATAMI, FUSUMA, FUSUMA_GOLD, SHOJI, RANMA, COFFERED_CEILING, ZABUTON, KORO, CHOCHIN,
            SHIMENAWA, SAISEN_BAKO;

    private TempleBlocks() {
    }

    public static void register() {
        Platform p = Platform.get();
        ISHIDATAMI = p.registerBlock("ishidatami", () -> new Block(Properties.copy(Blocks.STONE_BRICKS)));
        SHIRASU = p.registerBlock("shirasu", () -> new Block(Properties.copy(Blocks.GRAVEL)));
        TAMAJARI = p.registerBlock("tamajari", () -> new Block(Properties.copy(Blocks.GRAVEL)));
        KOKE = p.registerBlock("koke", () -> new Block(Properties.copy(Blocks.MOSS_BLOCK)));
        RAKED_GRAVEL = p.registerBlock("raked_gravel", () -> new RotatedPillarBlock(Properties.copy(Blocks.GRAVEL)));
        RAKED_GRAVEL_RING = p.registerBlock("raked_gravel_ring", () -> new RakedRingBlock(Properties.copy(Blocks.GRAVEL)));
        TOBIISHI = p.registerBlock("tobiishi", () -> new Block(Properties.copy(Blocks.MOSS_BLOCK)));
        NIWAISHI = p.registerBlock("niwaishi", () -> new Block(Properties.copy(Blocks.STONE)));
        BAMBOO_FENCE = p.registerBlock("bamboo_fence", () -> new PaneBlock(Properties.copy(Blocks.BAMBOO_PLANKS)
                .noOcclusion()));
        TATAMI = p.registerBlock("tatami", () -> new DecorBlock(Properties.copy(Blocks.HAY_BLOCK)));
        FUSUMA = p.registerBlock("fusuma", () -> new TallPaneBlock(paper()));
        FUSUMA_GOLD = p.registerBlock("fusuma_gold", () -> new TallPaneBlock(paper()));
        SHOJI = p.registerBlock("shoji", () -> new PaneBlock(paper()));
        RANMA = p.registerBlock("ranma", () -> new PaneBlock(Properties.copy(Blocks.DARK_OAK_PLANKS).noOcclusion()));
        COFFERED_CEILING = p.registerBlock("coffered_ceiling", () -> new Block(Properties.copy(Blocks.DARK_OAK_PLANKS)));
        ZABUTON = p.registerBlock("zabuton", () -> new ShapedBlock(Properties.copy(Blocks.PURPLE_WOOL).noOcclusion(),
                new double[]{2, 0, 2, 14, 3, 14}));
        KORO = p.registerBlock("koro", () -> new ShapedBlock(Properties.copy(Blocks.COPPER_BLOCK).noOcclusion(),
                new double[]{2, 0, 2, 14, 9, 14}));
        CHOCHIN = p.registerBlock("chochin", () -> new ShapedBlock(Properties.copy(Blocks.WHITE_WOOL).noOcclusion()
                .lightLevel(s -> 13), new double[]{3, 0, 3, 13, 16, 13}));
        SHIMENAWA = p.registerBlock("shimenawa", () -> new RopeBlock(Properties.copy(Blocks.HAY_BLOCK).noOcclusion()));
        SAISEN_BAKO = p.registerBlock("saisen_bako", () -> new DecorBlock(Properties.copy(Blocks.DARK_OAK_PLANKS)
                .noOcclusion(), new double[]{1, 0, 3, 15, 10, 13}));
    }

    private static Properties paper() {
        return Properties.copy(Blocks.BIRCH_PLANKS).strength(0.5f).noOcclusion();
    }
}
