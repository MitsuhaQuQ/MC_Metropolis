package com.mtm.citybuilder.fabric;

import com.mtm.citybuilder.CityBuilder;
import com.mtm.citybuilder.platform.Platform;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;

import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.nio.file.Path;
import java.util.function.BiFunction;
import java.util.function.Supplier;

public final class FabricPlatform implements Platform {
    @Override
    public String loaderName() {
        return "Fabric";
    }

    @Override
    public Path configDir() {
        return FabricLoader.getInstance().getConfigDir();
    }

    @Override
    public boolean isDevelopmentEnvironment() {
        return FabricLoader.getInstance().isDevelopmentEnvironment();
    }

    @Override
    public boolean isModLoaded(String modId) {
        return FabricLoader.getInstance().isModLoaded(modId);
    }

    @Override
    public Supplier<Block> registerBlock(String name, Supplier<Block> factory) {
        ResourceLocation id = new ResourceLocation(CityBuilder.MOD_ID, name);
        Block block = Registry.register(BuiltInRegistries.BLOCK, id, factory.get());
        Registry.register(BuiltInRegistries.ITEM, id, new BlockItem(block, new Item.Properties()));
        return () -> block;
    }

    @Override
    public Supplier<Item> registerItem(String name, Supplier<Item> factory) {
        Item item = Registry.register(BuiltInRegistries.ITEM, new ResourceLocation(CityBuilder.MOD_ID, name), factory.get());
        return () -> item;
    }

    @Override
    public <T extends BlockEntity> Supplier<BlockEntityType<T>> registerBlockEntity(String name,
                                                                                    BiFunction<BlockPos, BlockState, T> factory,
                                                                                    Supplier<Block> block) {
        BlockEntityType<T> type = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE,
                new ResourceLocation(CityBuilder.MOD_ID, name),
                FabricBlockEntityTypeBuilder.create(factory::apply, block.get()).build());
        return () -> type;
    }
}
