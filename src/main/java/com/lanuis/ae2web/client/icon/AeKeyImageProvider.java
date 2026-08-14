package com.lanuis.ae2web.client.icon;

import appeng.api.client.AEKeyRenderHandler;
import appeng.api.client.AEKeyRendering;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;

/**
 * 一类 AEKey 的枚举 + 默认 AE2 {@code drawOnBlockFace} 绘制。
 */
public interface AeKeyImageProvider<T extends AEKey> {
    AEKeyType keyType();

    /** 磁盘子目录：{@code item} / {@code fluid}。 */
    String kind();

    Iterable<T> allEntries();

    @SuppressWarnings("unchecked")
    default void renderImage(
            T aeKey,
            PoseStack poseStack,
            MultiBufferSource bufferSource,
            int canvasSizeX,
            int canvasSizeY
    ) {
        AEKeyRenderHandler<T> renderer = (AEKeyRenderHandler<T>) AEKeyRendering.getOrThrow(keyType());
        poseStack.translate(canvasSizeX / 2f, canvasSizeY / 2f, 500f);
        poseStack.scale(1f, -1f, 10f);
        float scale = Math.min(canvasSizeX, canvasSizeY) * 0.8f;
        renderer.drawOnBlockFace(
                poseStack,
                bufferSource,
                aeKey,
                scale,
                LightTexture.FULL_BRIGHT,
                Minecraft.getInstance().level
        );
    }
}
