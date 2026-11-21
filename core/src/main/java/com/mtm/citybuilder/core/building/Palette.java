package com.mtm.citybuilder.core.building;

import java.util.List;

/**
 * The materials of a building, by the role a block plays, as block ids (the Minecraft side looks them up; the city
 * logic knows no Minecraft classes). Vanilla blocks only, of 1.20.1. The palettes, like the materials of the smaller
 * parts, are listed in the catalogue ({@link Catalog}).
 *
 * @param wall       the walls
 * @param accent     upper walls where they differ from the ground floor (a machiya's plastered upper storey), bands
 * @param trim       corners, pillars and the base course
 * @param window     windows
 * @param shopfront  a shop's ground floor front
 * @param lattice    a machiya's front lattice (格子): fence posts, unconnected
 * @param door       a door (a door block)
 * @param floor      floors
 * @param roofSlab   a pitched roof's steps (a slab block)
 * @param roofBlock  a pitched roof's ridge
 * @param flat       a flat roof
 * @param parapet    a flat roof's parapet
 * @param foundation what fills between the ground and the floor on a slope
 */
public record Palette(String wall, String accent, String trim, String window, String shopfront, String lattice,
                      String door, String floor, String roofSlab, String roofBlock, String flat, String parapet,
                      String foundation) {
    /**
     * A window's glazing as this mod's window blocks (a whole block of glass, its frame along the top and bottom and a
     * mullion down one edge, joining round corners and on walls at an angle to the blocks as panes cannot): the glass's
     * tint from the palette's glass, the frame from the building (an office's dark, timber trim's wood, a white house's
     * white, else the usual aluminium sash); bars and anything else as they are.
     */
    private String glazing(String id, Building building) {
        if (!id.endsWith("glass")) {
            return id;
        }
        String glass = tint(id);
        String frame;
        if (building.type() == Building.Type.OFFICE) {
            frame = "dark";
        } else if (trim.endsWith("_planks") || trim.endsWith("_log") || trim.endsWith("_wood")
                || building.type() == Building.Type.MACHIYA) {
            frame = "wood";
        } else if (building.type() == Building.Type.HOUSE
                && (wall.contains("white") || wall.contains("calcite") || wall.contains("quartz"))) {
            frame = "white";
        } else {
            frame = "aluminum";
        }
        return "citybuilder:window_" + frame + "_" + glass;
    }

    /** The tint of this mod's glass for a vanilla glass block's id: blue, grey or clear. */
    private static String tint(String id) {
        return id.contains("blue") || id.contains("cyan") ? "blue"
                : id.contains("gray") || id.contains("brown") || id.contains("black") ? "gray" : "clear";
    }

    /** Whether a building's upper storeys are a curtain wall (an office's glass from floor to floor). */
    private static boolean curtain(Building building) {
        return building.type() == Building.Type.OFFICE && building.composition().is(Slot.FACADE, "curtain");
    }

    /**
     * The block id of a part of a building with this palette and composition; {@code variant} is the building's,
     * {@code position} a hash of the block's column (for what varies from block to block, as pot plants). Null for a
     * part that leaves the world as it is. Slabs, doors and leaves come back as their blocks' ids; the caller sets
     * their states.
     */
    public String id(BuildingLayer.Part part, Building building, long position) {
        return id(part, building, position, building.base());
    }

    /**
     * The block id of a part at a height {@code y}, aged by the building's {@link Building#age()}: grime at the foot of
     * its walls, streaks down them, stains here and there, moss and cracks in stone, oxidised copper, rust on steel
     * posts. A block ages only into what the catalogue lists for it, of the same shape.
     */
    public String id(BuildingLayer.Part part, Building building, long position, int y) {
        if (part == BuildingLayer.Part.WINDOW && curtain(building) && y - building.base() >= building.ground()) {
            // A curtain wall, glass from floor to floor between its mullions: whole blocks of glass, one sheet
            return "citybuilder:curtain_" + tint(window);
        }
        String id = fresh(part, building, position);
        double age = building.age();
        if (id == null || age <= 0.0) {
            return id;
        }
        Catalog c = Catalog.get();
        int dy = y - building.base();
        long at = com.mtm.citybuilder.core.noise.Hash.of(position, y, 0xA6E);
        double here = com.mtm.citybuilder.core.noise.Hash.unit(at);
        switch (part) {
            case ARCADE_POST -> {
                // Rust from the foot up, in streaks down the rest
                boolean streak = com.mtm.citybuilder.core.noise.Hash.unit(com.mtm.citybuilder.core.noise.Hash.of(position, 0x57)) < 0.5 * age;
                if (here < (dy <= 1 ? 0.9 * age : streak ? 0.45 * age : 0.08 * age)) {
                    return c.material("rust", at);
                }
                return id;
            }
            case WALL, ACCENT, TRIM, WALL_ALT, PARAPET, FOUNDATION, PAVING, BANK, PLASTER, ARCADE_ROOF -> {
                List<String> aged = c.weathered(id);
                if (aged.isEmpty()) {
                    return id;
                }
                // A streak down a wall from where water runs off, grime at its foot, stains here and there
                boolean streakColumn = com.mtm.citybuilder.core.noise.Hash.unit(com.mtm.citybuilder.core.noise.Hash.of(position, 0x58)) < 0.3 * age;
                boolean streak = streakColumn && com.mtm.citybuilder.core.noise.Hash.unit(com.mtm.citybuilder.core.noise.Hash.of(position, 0x59, Math.floorDiv(dy, 3))) < 0.75;
                boolean grime = dy <= 1 && here < 0.6 * age;
                boolean stain = here < 0.1 * age;
                return streak || grime || stain ? aged.get((int) Math.floorMod(com.mtm.citybuilder.core.noise.Hash.mix(at), (long) aged.size())) : id;
            }
            case ROOF_LOW, ROOF_HIGH, RIDGE, HOOD -> {
                // Moss and oxidised patches on a roof
                List<String> aged = c.weathered(id);
                return !aged.isEmpty() && here < 0.25 * age ? aged.get((int) Math.floorMod(com.mtm.citybuilder.core.noise.Hash.mix(at), (long) aged.size())) : id;
            }
            default -> {
                return id;
            }
        }
    }

    private String fresh(BuildingLayer.Part part, Building building, long position) {
        Catalog c = Catalog.get();
        Composition composition = building.composition();
        long variant = building.variant();
        return switch (part) {
            case NONE -> null;
            case AIR -> "minecraft:air";
            case LIGHT -> "minecraft:light";
            case FOUNDATION, PAVING -> foundation;
            case FLOOR -> floor;
            case WALL -> wall;
            case ACCENT -> accent;
            case TRIM -> trim;
            case WINDOW -> glazing(window, building);
            case SHOPFRONT -> glazing(shopfront, building);
            case LATTICE -> switch (composition.get(Slot.LATTICE_WOOD)) {
                case "spruce" -> c.material("latticeSpruce", variant);
                case "mangrove" -> c.material("latticeMangrove", variant);
                default -> lattice;
            };
            case DOOR_LOWER, DOOR_UPPER -> door;
            case ROOF_LOW, ROOF_HIGH, HOOD -> roofSlab;
            case RIDGE -> roofBlock;
            case FLAT -> flat;
            case PARAPET -> parapet;
            case EQUIPMENT -> c.material("equipment", variant);
            case AWNING -> c.material("awning", variant ^ 0x11);
            case SIGN -> c.material("sign", variant ^ 0x22);
            case SHUTTER -> c.material("shutter", variant);
            case VENDING -> c.material("vending", variant ^ 0x33);
            case FENCE -> c.material("fence", variant ^ 0x44);
            case HEDGE -> c.material("hedge", variant);
            case BAMBOO -> c.material("bamboo", variant);
            case PLANT -> c.material("plant", position);
            case LOG -> c.material("log", variant ^ 0x55);
            case LEAVES -> c.material("leaves", variant ^ 0x55);
            case SHRUB -> c.material("shrub", position);
            case SHED -> c.material("shed", variant ^ 0x66);
            case POST -> c.material("post", variant);
            case CARPORT -> c.material("carport", variant);
            case GUARD -> c.material("guard", variant);
            case RAIL -> c.material("rail", variant ^ 0x77);
            case DOWNPIPE -> c.material("downpipe", variant);
            case NOREN -> c.material("curtain", variant ^ 0x88);
            case NOREN_RED -> c.material("curtainRed", variant ^ 0xAA);
            case LANTERN -> c.material("lantern", variant);
            case SUDARE -> c.material("sudare", variant);
            case KOMAYOSE -> c.material("komayose", variant);
            case ENGAWA -> c.material("engawa", variant);
            case TORO -> c.material("toro", variant);
            case TORO_LIGHT -> c.material("toroLight", variant);
            case TORO_CAP -> c.material("toroCap", variant);
            case GRAVEL -> c.material("gravel", variant);
            case BOARD -> c.material("board", variant);
            case PLASTER -> c.material("plaster", variant);
            case SPIKE -> c.material("spike", variant);
            case FASCIA_LIT -> c.material("fasciaLit", variant ^ 0xBB);
            case DISPLAY -> c.material("display", position);
            case STAND -> c.material("stand", position);
            case BRAND -> building.program() == null ? accent : c.material(c.brand(building.program()), variant ^ 0xCC);
            case AD -> c.material("ad", variant ^ 0xDD);
            case LINE -> c.material("line", variant);
            case POSTBOX -> c.material("postbox", variant);
            case POLE -> c.material("pole", variant);
            case WALL_ALT -> c.material(composition.is(Slot.TWO_TONE, "base") ? "baseWall" : "upperWall", variant ^ 0xEE);
            case STEP -> c.material("step", variant);
            case TENANT_A -> c.material("tenantSign", variant ^ 0x101);
            case TENANT_B -> c.material("tenantSign", variant ^ 0x202);
            case TENANT_C -> c.material("tenantSign", variant ^ 0x303);
            case SHOKI -> c.material("shoki", variant);
            case BANK -> c.material("bank", position);
            case WATER -> c.material("water", variant);
            case BLACK_BOARD -> c.material("blackBoard", variant);
            case ARCADE_POST -> c.materialAt("arcadePost", Slot.ARCADE_COLOR.options().indexOf(composition.get(Slot.ARCADE_COLOR)));
            case ARCADE_ROOF -> c.materialAt("arcadeRoof", Slot.ARCADE_COLOR.options().indexOf(composition.get(Slot.ARCADE_COLOR)));
            case ARCADE_PAVING -> c.material("arcadePaving", position);
            case TACTILE -> c.material("tactile", variant);
            case ARCADE_GLASS -> c.material("arcadeGlass", variant);
            case LOUVRE -> c.material("louvre", variant);
            case ANDON -> c.material("andon", variant ^ 0x606);
            case TOBUKURO -> c.material("tobukuro", variant);
            case GATEPOST -> c.material("gatepost", variant);
            case GATE_LIGHT -> c.material("gateLight", variant);
            case PORCH_LIGHT -> c.material("porchLight", variant);
            case FUTON -> c.material("futon", position);
            case SOLAR -> c.material("solar", variant);
            case ANTENNA -> c.material("antenna", variant);
            case HEATER -> c.material("heater", variant);
            case METER -> c.material("meter", variant);
            case GAS_METER -> c.material("gasMeter", variant);
            case WATER_TANK -> c.material("waterTank", variant);
            case TANK_LEG -> c.material("tankLeg", variant);
            case ONI -> c.material("oni", variant);
            case YAGO -> c.material("yago", variant);
            case TATAMI -> c.material("tatami", variant);
            case EARTH -> c.material("earth", variant);
            case PARTITION -> c.material(building.type() == Building.Type.MACHIYA ? "fusuma" : "partition", variant);
            case STAIRS -> c.material("stairs", variant);
            case STAIRS_FRONT -> c.material("outerStairs", variant);
            case MESH -> c.material("mesh", variant);
            case CHIMNEY -> c.material("chimney", variant);
            case CHIMNEY_TOP -> c.material("chimneyTop", variant);
            case DANCHI_NO -> c.material("danchiNumber", variant);
            case CREST -> c.material("crest", variant);
            case TANK_SHELL -> c.material("tankShell", variant ^ 0x707);
            case PIPE -> c.material("pipe", position);
            case DECK -> c.material("deck", variant);
            case STACK -> c.material("stack", variant);
            case COAL -> c.material("coal", variant);
            case STEEL -> c.material("steel", variant);
            case TRANSFORMER -> c.material("transformer", variant);
            case FLARE_TOP -> c.material("flareTop", variant);
            case CONTAINER_0 -> c.materialAt("container", 0);
            case CONTAINER_1 -> c.materialAt("container", 1);
            case CONTAINER_2 -> c.materialAt("container", 2);
            case CONTAINER_3 -> c.materialAt("container", 3);
            case CONTAINER_4 -> c.materialAt("container", 4);
            case CONTAINER_5 -> c.materialAt("container", 5);
            case CONTAINER_6 -> c.materialAt("container", 6);
            case CONTAINER_7 -> c.materialAt("container", 7);
            case CRANE -> c.material("crane", variant);
            case CRANE_HOUSE -> c.material("craneHouse", variant);
            case BUND -> c.material("bund", variant);
            case TRUCK_CAB -> c.material("truckCab", position);
            case FLOOD -> c.material("flood", variant);
            case BOLLARD -> c.material("bollard", variant);
            case CAR -> c.material("car", position);
            case CAR_GLASS -> c.material("carGlass", variant);
            case SHELF -> c.material("shelf", position);
            case TABLE -> c.material("table", variant);
            case KITCHEN -> c.material("kitchen", position);
            case COUNTER -> c.material("counter", variant);
            case DESK -> c.material("desk", variant);
            case STORAGE -> c.material("storage", position);
        };
    }

    /** A palette of the catalogue by its index. */
    public static Palette get(int index) {
        return Catalog.get().palette(index);
    }
}
