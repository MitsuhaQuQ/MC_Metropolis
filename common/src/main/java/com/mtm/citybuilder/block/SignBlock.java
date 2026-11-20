package com.mtm.citybuilder.block;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;

/**
 * A hanging wayfinding sign, a yellow pictogram on black ({@link #ICON}: departures, arrivals, baggage, toilets, left,
 * right, up, information, restaurant, shopping, lift, escalator, gates, bus, train, parking; core's
 * building.airport.Sign in the same order).
 */
public class SignBlock extends PanelBlock {
    public static final IntegerProperty ICON = IntegerProperty.create("icon", 0, 15);

    public SignBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(ICON, 0));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(ICON);
    }
}
