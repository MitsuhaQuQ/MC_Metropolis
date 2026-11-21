package com.mtm.citybuilder.item;

import com.mtm.citybuilder.block.KerbEdgeBlock;
import com.mtm.citybuilder.block.MarkingBlock;
import com.mtm.citybuilder.block.ThinWallBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;

/**
 * The adjuster: sets this mod's shaped blocks by hand to any of their states, so whatever the city built can be built
 * again, or built anew, without commands. Used on a thin wall it turns it through its shapes, the next one round each
 * time and, sneaking, the one before; on a road line or an angled kerb the direction steps round, and sneaking the
 * line's position across the block. A state set stays: nothing round it changes it again.
 */
public final class AdjusterItem extends Item {
    public AdjusterItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        BlockState state = level.getBlockState(pos);
        boolean back = context.isSecondaryUseActive();
        BlockState next;
        if (state.getBlock() instanceof ThinWallBlock wall) {
            int code = ThinWallBlock.shape(state).code();
            next = wall.state(com.mtm.citybuilder.core.building.ThinWall.Shape.of(code + (back ? -1 : 1)));
        } else if (state.getBlock() instanceof MarkingBlock) {
            next = step(state, back ? MarkingBlock.OFFSET : MarkingBlock.ANGLE);
        } else if (state.getBlock() instanceof KerbEdgeBlock) {
            next = step(state, back ? KerbEdgeBlock.OFFSET : KerbEdgeBlock.ANGLE);
        } else {
            return InteractionResult.PASS;
        }
        if (!level.isClientSide) {
            // No neighbour updates: nothing round it should change on its account
            level.setBlock(pos, next, 2 | 16);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    private static BlockState step(BlockState state, IntegerProperty property) {
        int max = property.getPossibleValues().stream().mapToInt(Integer::intValue).max().orElse(0);
        return state.setValue(property, (state.getValue(property) + 1) % (max + 1));
    }
}
