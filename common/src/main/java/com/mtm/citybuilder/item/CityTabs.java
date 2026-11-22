package com.mtm.citybuilder.item;

import com.mtm.citybuilder.CityBuilder;
import com.mtm.citybuilder.platform.Platform;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.function.Predicate;

/**
 * This mod's pages in the creative inventory: roads (paving, lines, kerbs, bridges, the overhead line), buildings
 * (windows, curtain glass, thin walls, roofing, doors and lanterns, the adjuster) and landmarks (everything else: the
 * airport's, the stadiums', the temples' and shrines'). Each is filled when opened from this mod's items by name, so a
 * block added later lands on a page without being listed here; what no page claims goes to landmarks.
 */
public final class CityTabs {
    private static final String[] ROADS = {"asphalt", "sidewalk", "curb", "manhole", "drain_grate", "tactile",
            "road_", "concrete_parapet", "_layer", "_slope", "bridge_", "sound_barrier", "steel_girder", "expansion_joint", "deck_drain",
            "catenary_", "curve_marker", "paving", "marking", "street_light", "side_ditch"};
    private static final String[] BUILDINGS = {"window_", "curtain_", "thin_", "roof_", "adjuster", "door", "lantern",
            "andon", "chochin", "ac_unit", "vending", "water_heater", "postbox", "downpipe", "solar_panel",
            "tv_antenna", "louvre", "meter", "water_tank", "tank_leg", "sign_blade", "fascia_", "konbini_band"};

    private CityTabs() {
    }

    public static void register() {
        Platform p = Platform.get();
        p.registerTab("roads", Component.translatable("itemGroup.citybuilder.roads"), () -> icon("asphalt_line_white"),
                CityTabs::road);
        p.registerTab("buildings", Component.translatable("itemGroup.citybuilder.buildings"),
                () -> icon("window_aluminum_clear"), CityTabs::building);
        p.registerTab("landmarks", Component.translatable("itemGroup.citybuilder.landmarks"), () -> icon("turf"),
                id -> !road(id) && !building(id));
    }

    private static boolean road(String id) {
        return matches(id, ROADS);
    }

    private static boolean building(String id) {
        return matches(id, BUILDINGS);
    }

    private static boolean matches(String id, String[] keys) {
        for (String key : keys) {
            if (id.startsWith(key) || id.contains("_" + key)) {
                return true;
            }
        }
        return false;
    }

    private static ItemStack icon(String name) {
        Item item = BuiltInRegistries.ITEM.get(new ResourceLocation(CityBuilder.MOD_ID, name));
        return new ItemStack(item == Items.AIR ? Items.BRICKS : item);
    }

    /** This mod's items whose names a page takes, in the order they were registered. */
    public static java.util.List<ItemStack> items(Predicate<String> page) {
        java.util.List<ItemStack> stacks = new java.util.ArrayList<>();
        for (Item item : BuiltInRegistries.ITEM) {
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
            if (id.getNamespace().equals(CityBuilder.MOD_ID) && page.test(id.getPath())) {
                stacks.add(new ItemStack(item));
            }
        }
        return stacks;
    }
}
