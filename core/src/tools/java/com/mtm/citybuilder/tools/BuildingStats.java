package com.mtm.citybuilder.tools;

import com.mtm.citybuilder.core.CityLogic;
import com.mtm.citybuilder.core.building.Building;
import com.mtm.citybuilder.core.building.BuildingLayer;
import com.mtm.citybuilder.core.plot.Plot;
import com.mtm.citybuilder.core.plot.TownPlots;
import com.mtm.citybuilder.core.street.District;
import com.mtm.citybuilder.core.street.StreetClass;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Statistics of the generated buildings (L8) over an area, in the terms of the PLATEAU statistics of Kyoto (design
 * document section 6, "京都实测"), so the two can be set side by side: storeys overall, by the street fronted and by
 * distance from the town's core; kinds, roofs, footprints and coverage.
 */
final class BuildingStats {
    private BuildingStats() {
    }

    private record Seen(Building building, Plot plot, double distance, int[] cells) {
    }

    static void print(CityLogic logic, TownPlots plots, double coreX, double coreZ, double centerX, double centerZ, double half) {
        long start = System.nanoTime();
        BuildingLayer layer = logic.buildings();
        Map<Long, Seen> seen = new HashMap<>();
        int samples = 0;
        int covered = 0;
        int step = 2;
        for (double x = centerX - half; x < centerX + half; x += step) {
            for (double z = centerZ - half; z < centerZ + half; z += step) {
                samples++;
                Plot plot = plots.at(x, z);
                Building b = layer.building(plots, plot);
                if (b == null || plot.id() != b.id() || plot.along() < b.from() || plot.along() >= b.to()
                        || plot.depth() < b.front() || plot.depth() >= b.back()) {
                    continue;
                }
                covered++;
                double d = Math.hypot(x - coreX, z - coreZ);
                Seen s = seen.computeIfAbsent(b.id(), k -> new Seen(b, plot, d, new int[1]));
                s.cells[0]++;
            }
        }
        System.out.printf("L8 buildings: %d over %.1f km2, found in %.1f s; footprint coverage %.0f%% of the area (Kyoto 0-2 km: 39-57%%)%n",
                seen.size(), samples * step * step / 1e6, (System.nanoTime() - start) / 1e9, 100.0 * covered / Math.max(1, samples));

        List<Seen> all = new ArrayList<>(seen.values());
        System.out.println("  storeys: " + shares(all, s -> s.building.storeys()) + "   (Kyoto: 1F 9%, 2F 68%, 3F 18%, 4-5F 3.5%, 6F+ 1.4%)");
        Map<Building.Type, Integer> types = new EnumMap<>(Building.Type.class);
        Map<Building.Roof, Integer> roofs = new EnumMap<>(Building.Roof.class);
        for (Seen s : all) {
            types.merge(s.building.type(), 1, Integer::sum);
            roofs.merge(s.building.roof(), 1, Integer::sum);
        }
        System.out.println("  kinds: " + percent(types, all.size()));
        System.out.println("  roofs: " + percent(roofs, all.size()) + "   (Kyoto LOD2: gable 55%, flat 26%, hip 5%)");

        System.out.println("  by kind: n | short side p25/p50/p75 | long side | area p50 | storeys p50/p90");
        for (Building.Type type : Building.Type.values()) {
            List<Seen> of = all.stream().filter(s -> s.building.type() == type).toList();
            if (of.isEmpty()) {
                continue;
            }
            List<Double> shorter = new ArrayList<>();
            List<Double> longer = new ArrayList<>();
            List<Double> area = new ArrayList<>();
            List<Double> storeys = new ArrayList<>();
            for (Seen s : of) {
                double w = s.building.width();
                double d = s.building.depth();
                shorter.add(Math.min(w, d));
                longer.add(Math.max(w, d));
                area.add(s.cells[0] * (double) step * step);
                storeys.add((double) s.building.storeys());
            }
            System.out.printf("    %-9s %6d | %s | %s | %s | %s%n", type, of.size(), pct(shorter, 25, 50, 75), pct(longer, 25, 50, 75),
                    pct(area, 50), pct(storeys, 50, 90));
        }

        System.out.println("  by street fronted: n | storeys p50/p75/p90 | >=4F | >=6F   (Kyoto: arterial 3/4-5/7-9, 36-38%, 15-22%;"
                + " minor 2/3/4, 11%, 3%; lane 2/3/3, 5%, 1%)");
        Map<StreetClass, List<Seen>> byStreet = new EnumMap<>(StreetClass.class);
        for (Seen s : all) {
            byStreet.computeIfAbsent(s.plot.street(), k -> new ArrayList<>()).add(s);
        }
        for (var e : byStreet.entrySet()) {
            print(e.getKey().toString(), e.getValue());
        }
        System.out.println("  by district:");
        Map<District, List<Seen>> byDistrict = new EnumMap<>(District.class);
        for (Seen s : all) {
            byDistrict.computeIfAbsent(s.plot.district(), k -> new ArrayList<>()).add(s);
        }
        for (var e : byDistrict.entrySet()) {
            print(e.getKey().toString(), e.getValue());
        }
        System.out.println("  by distance from the core (Kyoto: 0-0.5 km 3/5/8, 17% 6F+; 1-2 km 2/3/4; beyond 2/3/3):");
        Map<Integer, List<Seen>> rings = new TreeMap<>();
        for (Seen s : all) {
            rings.computeIfAbsent((int) Math.floor(s.distance / 500.0), k -> new ArrayList<>()).add(s);
        }
        for (var e : rings.entrySet()) {
            print(String.format("%.1f-%.1f km", e.getKey() * 0.5, e.getKey() * 0.5 + 0.5), e.getValue());
        }
    }

    private static void print(String label, List<Seen> of) {
        List<Double> storeys = new ArrayList<>();
        int four = 0;
        int six = 0;
        for (Seen s : of) {
            storeys.add((double) s.building.storeys());
            four += s.building.storeys() >= 4 ? 1 : 0;
            six += s.building.storeys() >= 6 ? 1 : 0;
        }
        System.out.printf("    %-14s %6d | %s | %3.0f%% | %3.0f%%%n", label, of.size(), pct(storeys, 50, 75, 90),
                100.0 * four / of.size(), 100.0 * six / of.size());
    }

    private static String pct(List<Double> values, int... ps) {
        List<Double> v = new ArrayList<>(values);
        Collections.sort(v);
        StringBuilder out = new StringBuilder();
        for (int p : ps) {
            if (out.length() > 0) {
                out.append('/');
            }
            double x = v.get(Math.min(v.size() - 1, (int) (p / 100.0 * v.size())));
            out.append(x == Math.rint(x) ? String.format("%.0f", x) : String.format("%.1f", x));
        }
        return out.toString();
    }

    private static String shares(List<Seen> all, java.util.function.ToIntFunction<Seen> key) {
        Map<Integer, Integer> counts = new TreeMap<>();
        for (Seen s : all) {
            counts.merge(key.applyAsInt(s), 1, Integer::sum);
        }
        StringBuilder out = new StringBuilder();
        for (var e : counts.entrySet()) {
            out.append(String.format("%dF %.1f%%  ", e.getKey(), 100.0 * e.getValue() / all.size()));
        }
        return out.toString().trim();
    }

    private static <K> String percent(Map<K, Integer> counts, int total) {
        StringBuilder out = new StringBuilder();
        for (var e : counts.entrySet()) {
            out.append(String.format("%s %.0f%%  ", e.getKey(), 100.0 * e.getValue() / total));
        }
        return out.toString().trim();
    }
}
