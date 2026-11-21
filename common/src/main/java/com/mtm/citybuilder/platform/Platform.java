package com.mtm.citybuilder.platform;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.nio.file.Path;
import java.util.function.BiFunction;
import java.util.ServiceLoader;
import java.util.function.Supplier;

/**
 * Loader-specific services. Each loader module provides exactly one implementation, registered under
 * META-INF/services.
 */
public interface Platform {
    String loaderName();

    Path configDir();

    boolean isDevelopmentEnvironment();

    boolean isModLoaded(String modId);

    /**
     * Registers a block of this mod, with an item placing it, under {@code citybuilder:<name>}; the block once it is
     * registered, which on some loaders is only after the mod's construction.
     */
    Supplier<Block> registerBlock(String name, Supplier<Block> factory);

    /** Registers an item of this mod under {@code citybuilder:<name>}; the item once it is registered. */
    Supplier<net.minecraft.world.item.Item> registerItem(String name, Supplier<net.minecraft.world.item.Item> factory);

    /**
     * Registers a block entity type of {@code block} under {@code citybuilder:<name>} (built on the loader's side, where
     * the type's factory interface is open); the type once it is registered.
     */
    <T extends BlockEntity> Supplier<BlockEntityType<T>> registerBlockEntity(String name,
                                                                             BiFunction<BlockPos, BlockState, T> factory,
                                                                             Supplier<Block> block);

    static Platform get() {
        return Holder.INSTANCE;
    }

    final class Holder {
        private static final Platform INSTANCE = ServiceLoader.load(Platform.class, Platform.class.getClassLoader())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No Platform implementation found"));

        private Holder() {
        }
    }
}
