package com.mtm.citybuilder.block;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;

/**
 * A shop's sign: a projecting sign's blade (袖看板), a fascia board, a lit fascia. Faces {@link #FACING} (out of the
 * wall; a blade stands out along it, lettered on its sides) and carries one character a block ({@link #GLYPH}: 0 a plain
 * panel, 1-8 the characters drawn by {@code tools/textures/sign_textures.py}, no script's), so a row or a stack of
 * them reads as a name. Placed by hand it shows the first character; buildings pick them by position.
 */
public class ShopSignBlock extends LampBlock {
    public static final int GLYPHS = 8;
    public static final IntegerProperty GLYPH = IntegerProperty.create("glyph", 0, GLYPHS);

    public ShopSignBlock(Properties properties, double[]... boxes) {
        super(properties, boxes);
        registerDefaultState(defaultBlockState().setValue(GLYPH, 1));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(GLYPH);
    }
}
