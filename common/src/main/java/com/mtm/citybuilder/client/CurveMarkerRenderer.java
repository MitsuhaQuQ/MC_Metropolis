package com.mtm.citybuilder.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.mtm.citybuilder.block.CurveMarkerBlock;
import com.mtm.citybuilder.block.CurveMarkerBlockEntity;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;

import java.util.List;

/** Writes a curve marker's lines on both faces of its board, in black as the real ones. */
public class CurveMarkerRenderer implements BlockEntityRenderer<CurveMarkerBlockEntity> {
    private static final float SCALE = 0.0095f;
    private static final int BLACK = 0xFF101010;
    private final Font font;

    public CurveMarkerRenderer(BlockEntityRendererProvider.Context context) {
        font = context.getFont();
    }

    @Override
    public void render(CurveMarkerBlockEntity marker, float partialTick, PoseStack pose, MultiBufferSource buffers,
                       int light, int overlay) {
        Direction facing = marker.getBlockState().getValue(CurveMarkerBlock.FACING);
        List<String> lines = marker.lines();
        for (int side = 0; side < 2; side++) {
            pose.pushPose();
            pose.translate(0.5, 0.75, 0.5);
            pose.mulPose(Axis.YP.rotationDegrees(-facing.toYRot() + (side == 0 ? 180 : 0)));
            pose.translate(0, 0, 1.01 / 16);  // just off the board's face (the board is 2/16 thick)
            pose.scale(SCALE, -SCALE, SCALE);
            float top = -lines.size() * (font.lineHeight + 1) / 2f;
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                font.drawInBatch(line, -font.width(line) / 2f, top + i * (font.lineHeight + 1), BLACK, false,
                        pose.last().pose(), buffers, Font.DisplayMode.POLYGON_OFFSET, 0, light);
            }
            pose.popPose();
        }
    }
}
