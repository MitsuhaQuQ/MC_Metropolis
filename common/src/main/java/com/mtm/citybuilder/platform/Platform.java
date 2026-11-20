package com.mtm.citybuilder.platform;

import net.minecraft.world.level.block.Block;

import java.nio.file.Path;
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
