package com.mtm.citybuilder.core.settlement;

import com.mtm.citybuilder.core.culture.Style;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The settlements of one tier's plan block, as planned once and stored.
 *
 * @param planVersion the plan version of the settings the block was planned with
 * @param settlements in the order they were placed, the best first
 */
public record SettlementBlock(int planVersion, SettlementTier tier, int blockX, int blockZ, List<Settlement> settlements) {
    /** "CBS" and the format's version. */
    private static final int MAGIC = 0x43425301;

    public SettlementBlock {
        settlements = List.copyOf(settlements);
    }

    /** The key a block is stored under. */
    public static String key(SettlementTier tier, int blockX, int blockZ) {
        return "settlements/" + tier.name().toLowerCase(Locale.ROOT) + "/" + blockX + "_" + blockZ;
    }

    /** The settlement in a cell of the tier's grid, or null. */
    public Settlement inCell(int cellX, int cellZ) {
        for (Settlement settlement : settlements) {
            if (settlement.cellX() == cellX && settlement.cellZ() == cellZ) {
                return settlement;
            }
        }
        return null;
    }

    public byte[] encode() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(MAGIC);
            out.writeInt(planVersion);
            out.writeByte(tier.ordinal());
            out.writeInt(blockX);
            out.writeInt(blockZ);
            out.writeInt(settlements.size());
            for (Settlement settlement : settlements) {
                out.writeLong(settlement.id());
                out.writeInt(settlement.cellX());
                out.writeInt(settlement.cellZ());
                out.writeDouble(settlement.x());
                out.writeDouble(settlement.z());
                out.writeByte(settlement.style().ordinal());
                out.writeDouble(settlement.hinterland());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    public static SettlementBlock decode(byte[] data) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            if (in.readInt() != MAGIC) {
                throw new IllegalArgumentException("Not a settlement block");
            }
            int planVersion = in.readInt();
            SettlementTier tier = SettlementTier.values()[in.readByte()];
            int blockX = in.readInt();
            int blockZ = in.readInt();
            int count = in.readInt();
            List<Settlement> settlements = new ArrayList<>(count);
            for (int k = 0; k < count; k++) {
                long id = in.readLong();
                int cellX = in.readInt();
                int cellZ = in.readInt();
                double x = in.readDouble();
                double z = in.readDouble();
                Style style = Style.values()[in.readByte()];
                double hinterland = in.readDouble();
                settlements.add(new Settlement(id, tier, cellX, cellZ, x, z, style, hinterland));
            }
            return new SettlementBlock(planVersion, tier, blockX, blockZ, settlements);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
