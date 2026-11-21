package com.mtm.citybuilder.block;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.Locale;

/**
 * What a curve marker tells: where the MTR node it stands for is (relative to the marker), and how to set it (angle to
 * a tenth of a degree, transition length, grade), with the radius of the track's curve there. Filled by the city's
 * structures from their plan ({@code core.building.rail.CurveMarker}), as the structure templates carry it.
 */
public class CurveMarkerBlockEntity extends BlockEntity {
    private int nodeX;
    private int nodeY;
    private int nodeZ;
    private float angle;
    private float transition;
    private float grade;
    private int radius;
    private String track = "";

    public CurveMarkerBlockEntity(BlockPos pos, BlockState state) {
        super(CityBlocks.CURVE_MARKER_ENTITY.get(), pos, state);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        nodeX = tag.getInt("NodeX");
        nodeY = tag.getInt("NodeY");
        nodeZ = tag.getInt("NodeZ");
        angle = tag.getFloat("Angle");
        transition = tag.getFloat("Transition");
        grade = tag.getFloat("Grade");
        radius = tag.getInt("Radius");
        track = tag.getString("Track");
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putInt("NodeX", nodeX);
        tag.putInt("NodeY", nodeY);
        tag.putInt("NodeZ", nodeZ);
        tag.putFloat("Angle", angle);
        tag.putFloat("Transition", transition);
        tag.putFloat("Grade", grade);
        tag.putInt("Radius", radius);
        tag.putString("Track", track);
    }

    @Override
    public CompoundTag getUpdateTag() {
        return saveWithoutMetadata();
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    /** The board's lines: radius, transition length, the node's angle and grade, and where the node is. */
    public List<String> lines() {
        return List.of(
                radius > 0 ? "R=" + radius : "R=∞",
                String.format(Locale.ROOT, "TCL=%.0f", transition),
                String.format(Locale.ROOT, "%.1f°", angle),
                String.format(Locale.ROOT, "%+.1f‰", grade * 1000),
                String.format(Locale.ROOT, "%+d %+d %+d", nodeX, nodeY, nodeZ));
    }

    /** The whole of it for the chat, where the node is given in world coordinates. */
    public Component describe() {
        BlockPos node = getBlockPos().offset(nodeX, nodeY, nodeZ);
        return Component.translatable("block.citybuilder.curve_marker.describe", track, node.getX(), node.getY(),
                node.getZ(), String.format(Locale.ROOT, "%.1f", angle), String.format(Locale.ROOT, "%.0f", transition),
                String.format(Locale.ROOT, "%.1f", grade * 1000), radius > 0 ? String.valueOf(radius) : "∞");
    }
}
