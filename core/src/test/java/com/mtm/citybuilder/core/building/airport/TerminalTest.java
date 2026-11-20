package com.mtm.citybuilder.core.building.airport;

import com.mtm.citybuilder.core.building.temple.Model;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerminalTest {
    /** Takamatsu as measured on PLATEAU: 165 x 55 m, three floors, check-in on the ground floor, a roof deck. */
    static final Terminal TAKAMATSU = new Terminal(new int[]{0, 6, 11}, Terminal.CheckIn.GROUND,
            List.of(new Wing(Wing.Role.HALL, 0, 0, 165, 55, 16, 16, Wing.RoofForm.FLAT, 3, "S", 8, true)),
            20, 30, 26, 0.3, 0.7, 0.45, 0.2, 15, TerminalPalette.TIMBER);

    private static String base(String s) {
        int i = s.indexOf('[');
        return i < 0 ? s : s.substring(0, i);
    }

    @Test
    void blocksAreVanillaStates() {
        for (Terminal t : List.of(TAKAMATSU, TerminalComposer.compose(7, true))) {
            t.build().model().forEach((x, y, z, s) -> assertTrue(s.startsWith("minecraft:") && !s.contains("null"), s));
        }
    }

    @Test
    void everyFloorHasItsProgramme() {
        Zones z = TAKAMATSU.build().zones();
        for (Zone want : List.of(Zone.CHECK_IN, Zone.ARRIVAL_LOBBY, Zone.BAGGAGE_CLAIM, Zone.BAGGAGE_HANDLING,
                Zone.CUSTOMS, Zone.CORE)) {
            assertTrue(z.area(0, want) > 0, "ground floor without " + want);
        }
        for (Zone want : List.of(Zone.LOBBY, Zone.SECURITY, Zone.IMMIGRATION, Zone.SHOPS, Zone.GATE_LOUNGE)) {
            assertTrue(z.area(1, want) > 0, "gates' level without " + want);
        }
        assertTrue(z.area(2, Zone.RESTAURANTS) > 0, "no restaurants upstairs");
        assertTrue(z.area(3, Zone.DECK) > 0, "no observation deck");
    }

    @Test
    void bridgesOpenIntoTheLounges() {
        Terminal.Built b = TAKAMATSU.build();
        Model m = b.model();
        assertTrue(b.gates().size() >= 4, b.gates().size() + " gates");
        int y = TAKAMATSU.levels()[1];
        for (int[] g : b.gates()) {
            for (int dy = 1; dy <= 3; dy++) {
                assertNull(m.get(g[0], y + dy, g[1]), "door shut at " + g[0] + "," + g[1]);
            }
            // A floor all the way out, and the lounge inside the door
            for (int s = 1; s <= TAKAMATSU.bridgeLength(); s++) {
                assertNotNull(m.get(g[0] + g[2] * s, y, g[1] + g[3] * s), "bridge broken at " + s);
                assertNull(m.get(g[0] + g[2] * s, y + 1, g[1] + g[3] * s), "bridge blocked at " + s);
            }
            assertEquals(Zone.GATE_LOUNGE, b.zones().get(1, g[0] - g[2] * 10, g[1] - g[3] * 10));
        }
    }

    @Test
    void roofCoversTheFloors() {
        // Over every square of the hall stands roof: no hole lets the rain in.
        Model m = TAKAMATSU.build().model();
        for (int x = 0; x < 165; x++) {
            for (int z = 0; z < 55; z++) {
                boolean covered = false;
                for (int y = 12; y <= 19 && !covered; y++) { // lifts open onto the deck under a hood
                    covered = m.get(x, y, z) != null;
                }
                assertTrue(covered, "open at " + x + "," + z);
            }
        }
    }

    @Test
    void escalatorsClimbFromFloorToFloor() {
        Model m = TAKAMATSU.build().model();
        int[] stairs = new int[17];
        m.forEach((x, y, z, s) -> {
            if (s.startsWith("minecraft:smooth_stone_stairs")) {
                stairs[y]++;
            }
        });
        for (int y = 1; y <= 11; y++) {
            assertTrue(stairs[y] > 0, "no escalator step at " + y);
        }
    }

    @Test
    void composedTerminalsCoverEveryPlanAndScheme() {
        Set<TerminalComposer.Plan> plans = EnumSet.noneOf(TerminalComposer.Plan.class);
        Set<Terminal.CheckIn> schemes = EnumSet.noneOf(Terminal.CheckIn.class);
        for (long seed = 0; seed < 40; seed++) {
            for (boolean big : new boolean[]{false, true}) {
                TerminalComposer.Choice c = TerminalComposer.choose(seed, big);
                plans.add(c.plan());
                schemes.add(c.checkIn());
            }
        }
        assertEquals(EnumSet.allOf(TerminalComposer.Plan.class), plans);
        assertEquals(EnumSet.allOf(Terminal.CheckIn.class), schemes);
    }

    @Test
    void everyPlanAndSchemeBuilds() {
        // Each unit swapped in turn into the same draw: every combination builds, with gates and its check-in.
        for (TerminalComposer.Plan plan : TerminalComposer.Plan.values()) {
            for (Terminal.CheckIn checkIn : Terminal.CheckIn.values()) {
                TerminalComposer.Choice c = TerminalComposer.choose(11, true);
                Terminal t = TerminalComposer.compose(11, true, new TerminalComposer.Choice(plan, checkIn,
                        c.hallRoof(), c.palette(), c.deck(), c.international()));
                Terminal.Built b = t.build();
                assertTrue(b.gates().size() >= 4, plan + "/" + checkIn + ": " + b.gates().size() + " gates");
                assertTrue(b.zones().area(checkIn.level(), Zone.CHECK_IN) > 0, plan + "/" + checkIn);
                assertTrue(b.zones().area(1, Zone.SECURITY) > 0, plan + "/" + checkIn);
            }
        }
    }

    @Test
    void swappingThePaletteKeepsTheLayout() {
        TerminalComposer.Choice c = TerminalComposer.choose(3, false);
        Zones a = TerminalComposer.compose(3, false, c).build().zones();
        Zones b = TerminalComposer.compose(3, false, new TerminalComposer.Choice(c.plan(), c.checkIn(), c.hallRoof(),
                c.palette() == TerminalPalette.WHITE ? TerminalPalette.SILVER : TerminalPalette.WHITE, c.deck(),
                c.international())).build().zones();
        for (int lev = 0; lev < a.levels(); lev++) {
            assertArrayEquals(a.rows(lev), b.rows(lev));
        }
    }

    @Test
    void sameSeedSameTerminal() {
        Model a = TerminalComposer.compose(42, true).build().model();
        Model b = TerminalComposer.compose(42, true).build().model();
        assertEquals(a.size(), b.size());
        a.forEach((x, y, z, s) -> assertEquals(s, b.get(x, y, z)));
    }
}
