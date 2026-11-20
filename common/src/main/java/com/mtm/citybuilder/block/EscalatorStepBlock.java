package com.mtm.citybuilder.block;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;

/** An escalator's steps: stairs of grooved aluminium with a yellow nose. */
public class EscalatorStepBlock extends StairBlock {
    @SuppressWarnings("deprecation")
    public EscalatorStepBlock(Properties properties) {
        super(Blocks.IRON_BLOCK.defaultBlockState(), properties);
    }
}
