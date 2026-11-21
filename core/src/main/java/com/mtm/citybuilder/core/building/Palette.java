package com.mtm.citybuilder.core.building;

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
    /** A window's glass as panes, set in the middle of their blocks and joined along the wall; anything else as it is. */
    private static String pane(String id) {
        return id.endsWith("glass") ? id + "_pane" : id;
    }

    /**
     * The block id of a part of a building with this palette and composition; {@code variant} is the building's,
     * {@code position} a hash of the block's column (for what varies from block to block, as pot plants). Null for a
     * part that leaves the world as it is. Slabs, doors and leaves come back as their blocks' ids; the caller sets
     * their states.
     */
    public String id(BuildingLayer.Part part, Building building, long position) {
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
            case WINDOW -> pane(window);
            case SHOPFRONT -> pane(shopfront);
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
            case ARCADE_POST -> c.material("arcadePost", variant ^ 0x404);
            case ARCADE_ROOF -> c.material("arcadeRoof", variant ^ 0x505);
        };
    }

    /** A palette of the catalogue by its index. */
    public static Palette get(int index) {
        return Catalog.get().palette(index);
    }
}
