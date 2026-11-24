package com.mtm.citybuilder.core.building.landmark;

import com.mtm.citybuilder.core.building.ModelFootprint;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import com.mtm.citybuilder.core.building.temple.Model;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Voxelized Himeji Castle parts, loaded only when a town can use the part. */
public enum CastleAsset {
    KEEP("himeji_keep"), EAST("himeji_yagura_east"), SOUTHEAST("himeji_yagura_southeast"),
    SOUTHWEST("himeji_yagura_southwest"), SMALL("himeji_yagura_small"),
    WATARI("himeji_watari_yagura"), WEST_GALLERY("himeji_nishinomaru"), WATARI_WING("himeji_watari_yagura");

    private final String name;
    private Model model;
    private final ModelFootprint[] footprints = new ModelFootprint[4];

    public synchronized ModelFootprint footprint(int turn) {
        int q = Math.floorMod(turn, 4);
        if (footprints[q] != null) return footprints[q];
        int width = width(), depth = depth();
        int orientedDepth = q % 2 == 0 ? depth : width;
        var columns = new java.util.BitSet(width * depth);
        template().forEach((x, y, z, state) -> {
            int ix = x, iz = z, w = width, d = depth;
            for (int i = 0; i < q; i++) {
                int oldX = ix;
                ix = d - 1 - iz;
                iz = oldX;
                int oldW = w; w = d; d = oldW;
            }
            columns.set(ix * orientedDepth + iz);
        });
        footprints[q] = new ModelFootprint(name(), q % 2 == 0 ? width : depth, orientedDepth, columns);
        return footprints[q];
    }

    CastleAsset(String name) { this.name = name; }

    synchronized Model template() {
        if (model == null) model = this == WATARI_WING ? shortGallery() : load();
        return model;
    }

    public Model rotated(int quarterTurns) { return template().rotated(quarterTurns); }
    public int width() { return template().width(); }
    public int depth() { return template().depth(); }

    /** The turret and straight first wing, ending before the surveyed gallery turns away. */
    private static Model shortGallery() {
        Model cut = new Model();
        WATARI.template().forEach((x,y,z,state) -> { if (x < 24) cut.set(x,y,z,state); });
        Model result = new Model();
        result.place(cut,0,0,0,true);
        int end = result.maxX();
        for (int y=0; y<=result.maxY(); y++) {
            int first=result.depth(), last=-1;
            for (int z=0; z<result.depth(); z++) if (result.has(end,y,z)) {
                first=Math.min(first,z);
                last=z;
            }
            if (last < first) continue;
            String facing = result.get(end,y,first);
            for (int z=first+1; z<last; z++) result.setIfEmpty(end,y,z,facing);
        }
        return result;
    }

    private Model load() {
        var stream = Objects.requireNonNull(CastleAsset.class.getResourceAsStream("/citybuilder/buildings/" + name + ".json"));
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            var data = JsonParser.parseReader(reader).getAsJsonObject();
            JsonArray palette = data.getAsJsonArray("palette");
            JsonArray layers = data.getAsJsonArray("layers");
            Model model = new Model();
            for (int y = 0; y < layers.size(); y++) {
                JsonArray rows = layers.get(y).getAsJsonArray();
                for (int z = 0; z < rows.size(); z++) {
                    String row = rows.get(z).getAsString();
                    for (int x = 0; x < row.length(); x++) {
                        char block = row.charAt(x);
                        if (block != '.') model.set(x, y, z, palette.get(block - 'A').getAsString());
                    }
                }
            }
            return model;
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
