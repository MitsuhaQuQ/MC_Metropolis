package com.mtm.citybuilder.forge;

import com.mtm.citybuilder.CityBuilder;
import com.mtm.citybuilder.platform.Platform;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraftforge.fml.loading.FMLPaths;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.nio.file.Path;
import java.util.function.BiFunction;
import java.util.function.Supplier;

public final class ForgePlatform implements Platform {
    static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, CityBuilder.MOD_ID);
    static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, CityBuilder.MOD_ID);
    static final DeferredRegister<net.minecraft.world.item.CreativeModeTab> TABS =
            DeferredRegister.create(net.minecraft.core.registries.Registries.CREATIVE_MODE_TAB, CityBuilder.MOD_ID);
    static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, CityBuilder.MOD_ID);

    @Override
    public String loaderName() {
        return "Forge";
    }

    @Override
    public Path configDir() {
        return FMLPaths.CONFIGDIR.get();
    }

    @Override
    public boolean isDevelopmentEnvironment() {
        return !FMLLoader.isProduction();
    }

    @Override
    public boolean isModLoaded(String modId) {
        return ModList.get().isLoaded(modId);
    }

    @Override
    public Supplier<Block> registerBlock(String name, Supplier<Block> factory) {
        RegistryObject<Block> block = BLOCKS.register(name, factory);
        ITEMS.register(name, () -> new BlockItem(block.get(), new Item.Properties()));
        return block;
    }

    @Override
    public void registerTab(String name, net.minecraft.network.chat.Component title,
                            Supplier<net.minecraft.world.item.ItemStack> icon, java.util.function.Predicate<String> items) {
        TABS.register(name, () -> net.minecraft.world.item.CreativeModeTab.builder().title(title).icon(icon)
                .displayItems((parameters, output) -> output.acceptAll(com.mtm.citybuilder.item.CityTabs.items(items)))
                .build());
    }

    @Override
    public Supplier<Item> registerItem(String name, Supplier<Item> factory) {
        return ITEMS.register(name, factory);
    }

    @Override
    public <T extends BlockEntity> Supplier<BlockEntityType<T>> registerBlockEntity(String name,
                                                                                    BiFunction<BlockPos, BlockState, T> factory,
                                                                                    Supplier<Block> block) {
        return BLOCK_ENTITIES.register(name, () -> BlockEntityType.Builder.of(factory::apply, block.get()).build(null));
    }
}
