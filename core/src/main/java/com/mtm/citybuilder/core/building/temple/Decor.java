package com.mtm.citybuilder.core.building.temple;

import java.util.Set;

/**
 * This mod's decoration blocks for temples and shrines, by their block-state strings (the blocks are registered by
 * {@code com.mtm.citybuilder.block.TempleBlocks}; textures and models by {@code tools/textures/temple_blocks.py}).
 */
public final class Decor {
    private Decor() {
    }

    static final String NS = "citybuilder:";
    /** Granite paving (石畳): approaches, the platforms' aprons. */
    public static final String ISHIDATAMI = NS + "ishidatami";
    /** White gravel (白砂): a temple's court. */
    public static final String SHIRASU = NS + "shirasu";
    /** Grey river pebbles (玉砂利): a shrine's court and approach. */
    public static final String TAMAJARI = NS + "tamajari";
    /** Moss ground (苔). */
    public static final String KOKE = NS + "koke";
    /** Moss with a flat stepping stone standing proud of it (飛石). */
    public static final String TOBIISHI = NS + "tobiishi";
    /** A garden rock (庭石). */
    public static final String NIWAISHI = NS + "niwaishi";
    public static final String TATAMI = NS + "tatami";
    public static final String COFFERED_CEILING = NS + "coffered_ceiling";
    public static final String ZABUTON = NS + "zabuton";
    public static final String KORO = NS + "koro";
    public static final String CHOCHIN = NS + "chochin";
    public static final String FUSUMA = NS + "fusuma", FUSUMA_GOLD = NS + "fusuma_gold";
    public static final String SHOJI = NS + "shoji", RANMA = NS + "ranma", BAMBOO_FENCE = NS + "bamboo_fence";

    /** Every block id of this mod the generators use. */
    public static final Set<String> IDS = Set.of(ISHIDATAMI, SHIRASU, TAMAJARI, KOKE, TOBIISHI, NIWAISHI, TATAMI,
            COFFERED_CEILING, ZABUTON, KORO, CHOCHIN, FUSUMA, FUSUMA_GOLD, SHOJI, RANMA, BAMBOO_FENCE,
            NS + "raked_gravel", NS + "raked_gravel_ring", NS + "shimenawa", NS + "saisen_bako");

    /** Gravel raked in straight lines along x or z (砂紋). */
    public static String raked(boolean alongX) {
        return NS + "raked_gravel[axis=" + (alongX ? "x" : "z") + "]";
    }

    /**
     * The raked rings round rocks centred on the block corner (cx, cz) (between columns cx - 1 and cx): the block at
     * (x, z) if it is within 3 blocks of that corner each way, else null.
     */
    public static String ring(int x, int z, int cx, int cz) {
        int i = x >= cx ? x - cx : cx - 1 - x, j = z >= cz ? z - cz : cz - 1 - z;
        if (i > 2 || j > 2) {
            return null;
        }
        String facing = x >= cx ? (z >= cz ? "south" : "east") : (z >= cz ? "west" : "north");
        return NS + "raked_gravel_ring[facing=" + facing + ",part=" + (i + 3 * j) + "]";
    }

    /** A tatami half: the mat's seam end facing {@code facing} (north, east, south, west). */
    public static String tatami(String facing) {
        return TATAMI + "[facing=" + facing + "]";
    }

    /** A pane joined along x (east and west) or along z (north and south); {@code half} for fusuma, else null. */
    public static String pane(String id, boolean alongX, String half) {
        String joins = alongX ? "east=true,north=false,south=false,waterlogged=false,west=true"
                : "east=false,north=true,south=true,waterlogged=false,west=false";
        return id + "[" + joins + (half != null ? ",half=" + half : "") + "]";
    }

    /** The sacred rope along x or z. */
    public static String shimenawa(boolean alongX) {
        return NS + "shimenawa[axis=" + (alongX ? "x" : "z") + "]";
    }

    /** The offering box, its front facing {@code facing}. */
    public static String saisenBako(String facing) {
        return NS + "saisen_bako[facing=" + facing + "]";
    }
}
