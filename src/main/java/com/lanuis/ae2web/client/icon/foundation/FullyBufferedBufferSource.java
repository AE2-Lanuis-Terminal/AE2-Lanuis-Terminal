package com.lanuis.ae2web.client.icon.foundation;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 收集各 {@link RenderType} 顶点后再一次性 upload，避免即时 flush 打乱离屏 FBO。
 */
public final class FullyBufferedBufferSource extends MultiBufferSource.BufferSource {
    private final Map<RenderType, BufferBuilder> bufferBuilders = new HashMap<>();

    public FullyBufferedBufferSource() {
        super(null, null);
    }

    @Override
    public VertexConsumer getBuffer(RenderType renderType) {
        return bufferBuilders.computeIfAbsent(renderType, rt -> {
            BufferBuilder builder = new BufferBuilder(15720);
            builder.begin(rt.mode(), rt.format());
            return builder;
        });
    }

    @Override
    public void endBatch(RenderType renderType) {
        // 推迟到 upload
    }

    @Override
    public void endBatch() {
    }

    @Override
    public void endLastBatch() {
    }

    public Map<RenderType, VertexBuffer> upload() {
        Map<RenderType, VertexBuffer> result = new LinkedHashMap<>();
        for (Map.Entry<RenderType, BufferBuilder> e : bufferBuilders.entrySet()) {
            BufferBuilder value = e.getValue();
            if (value.isCurrentBatchEmpty()) {
                continue;
            }
            var mesh = value.end();
            VertexBuffer vertexBuffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
            vertexBuffer.bind();
            vertexBuffer.upload(mesh);
            result.put(e.getKey(), vertexBuffer);
        }
        return result;
    }
}
