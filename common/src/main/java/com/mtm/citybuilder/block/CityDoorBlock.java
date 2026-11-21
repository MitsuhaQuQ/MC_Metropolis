package com.mtm.citybuilder.block;

import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.properties.BlockSetType;

/**
 * This mod's doors: vanilla's door (two halves, hinged, opened by hand, its block states), in our own textures and
 * models (a lattice door, aluminium entrance doors, a shop's glass door). Vanilla keeps its constructor protected.
 */
public class CityDoorBlock extends DoorBlock {
    public CityDoorBlock(Properties properties, BlockSetType type) {
        super(properties, type);
    }
}
