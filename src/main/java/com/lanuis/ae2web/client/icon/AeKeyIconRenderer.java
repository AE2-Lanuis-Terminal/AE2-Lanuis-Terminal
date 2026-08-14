package com.lanuis.ae2web.client.icon;

import appeng.api.stacks.AEKey;
import com.lanuis.ae2web.Ae2LanuisMod;
import com.lanuis.ae2web.client.icon.foundation.FrameBuffer;
import com.lanuis.ae2web.client.icon.foundation.FullyBufferedBufferSource;
import com.lanuis.ae2web.icon.IconResourcePaths;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import org.joml.Matrix4f;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;

/**
 * 分帧烘焙 AEKey → PNG；由 {@link ClientIconEvents} 在 AFTER_LEVEL 拉取任务。
 */
public final class AeKeyIconRenderer {
    private static AeKeyIconRenderer instance;

    private final int size;
    private final FrameBuffer frameBuffer;
    private final NativeImage nativeImage;
    private final Matrix4f projectionMatrix;
    private final Deque<Runnable> renderTasks = new ArrayDeque<>();
    private int taskPullLimit = 1;
    private int remaining;
    private long lastNotifyMs;
    private Component statusLine = Component.literal("idle");

    private AeKeyIconRenderer(int size) {
        this.size = size;
        this.frameBuffer = new FrameBuffer(size, size);
        this.nativeImage = new NativeImage(size, size, false);
        this.projectionMatrix = new Matrix4f().setOrtho(0f, size, size, 0f, -1000f, 1000f);
    }

    public static boolean isBusy() {
        return instance != null;
    }

    public static AeKeyIconRenderer getInstance() {
        return instance;
    }

    public static Component statusText() {
        AeKeyIconRenderer r = instance;
        if (r == null) {
            return Component.literal("未在烘焙");
        }
        return r.statusLine;
    }

    public static int remainingTasks() {
        AeKeyIconRenderer r = instance;
        return r == null ? 0 : r.remaining;
    }

    /**
     * 取消进行中的烘焙并释放 FBO。
     *
     * @return true 若原先正在烘焙
     */
    public static boolean cancel() {
        AeKeyIconRenderer r = instance;
        if (r == null) {
            return false;
        }
        r.renderTasks.clear();
        r.remaining = 0;
        r.statusLine = Component.literal("已取消");
        try {
            r.dispose();
        } catch (Exception e) {
            Ae2LanuisMod.LOGGER.debug("Icon bake dispose after cancel: {}", e.toString());
        }
        instance = null;
        return true;
    }

    /**
     * 启动烘焙；已在进行中则返回 false。
     */
    public static boolean start(Path basePath, int pullLimit, int size, ProgressSink sink) {
        if (instance != null) {
            return false;
        }
        AeKeyIconRenderer renderer = new AeKeyIconRenderer(Math.max(16, size));
        renderer.taskPullLimit = Math.max(1, pullLimit);
        renderer.submit(basePath, sink);
        instance = renderer;
        return true;
    }

    private void submit(Path basePath, ProgressSink sink) {
        for (AeKeyImageProvider<?> provider : AeKeyImageProviders.all()) {
            enqueueProvider(provider, basePath, sink);
        }
        renderTasks.add(() -> {
            sink.onCompleted();
            statusLine = Component.literal("完成");
            dispose();
            instance = null;
        });
        remaining = renderTasks.size();
        statusLine = Component.literal("队列 " + remaining);
        sink.onStarted(remaining);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void enqueueProvider(AeKeyImageProvider provider, Path basePath, ProgressSink sink) {
        var entries = new java.util.ArrayList<>();
        for (Object e : provider.allEntries()) {
            entries.add(e);
        }
        int total = entries.size();
        renderTasks.add(() -> sink.onProviderTotal(provider.kind(), total));
        for (int i = 0; i < entries.size(); i++) {
            AEKey key = (AEKey) entries.get(i);
            int index = i;
            renderTasks.add(() -> {
                long now = System.currentTimeMillis();
                if (now - lastNotifyMs > 200) {
                    sink.onProgress(provider.kind(), index, total, key);
                    lastNotifyMs = now;
                    statusLine = Component.literal(
                            provider.kind() + " " + (index + 1) + "/" + total + " " + key.getId()
                    );
                }
                Path out = IconResourcePaths.resolvePng(
                        basePath,
                        provider.kind(),
                        key.getId().getNamespace(),
                        key.getId().getPath()
                );
                renderSingle(key, provider, out);
            });
        }
    }

    public void next() {
        int limit = taskPullLimit;
        while (!renderTasks.isEmpty() && limit-- >= 0) {
            Runnable task = renderTasks.pollFirst();
            remaining = renderTasks.size();
            if (task != null) {
                task.run();
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void renderSingle(AEKey key, AeKeyImageProvider provider, Path path) {
        FullyBufferedBufferSource bufferSource = new FullyBufferedBufferSource();
        PoseStack poseStack = new PoseStack();
        poseStack.pushPose();
        frameBuffer.clear();
        Lighting.setupForEntityInInventory();
        RenderSystem.enableCull();
        RenderSystem.setShaderColor(0.99f, 0.99f, 0.99f, 1f);
        frameBuffer.bindWrite(true);
        try {
            provider.renderImage(key, poseStack, bufferSource, size, size);
        } catch (Exception e) {
            Ae2LanuisMod.LOGGER.error("Icon bake failed for {}/{}", key.getType().getId(), key.getId(), e);
        }
        Map<RenderType, VertexBuffer> uploaded = bufferSource.upload();
        for (Map.Entry<RenderType, VertexBuffer> e : uploaded.entrySet()) {
            RenderType type = e.getKey();
            VertexBuffer vb = e.getValue();
            type.setupRenderState();
            frameBuffer.bindWrite(true);
            vb.bind();
            vb.drawWithShader(new Matrix4f(), projectionMatrix, RenderSystem.getShader());
            type.clearRenderState();
            vb.close();
        }
        VertexBuffer.unbind();
        frameBuffer.unbindWrite();
        Minecraft.getInstance().getMainRenderTarget().bindWrite(true);
        frameBuffer.bindRead();
        nativeImage.downloadTexture(0, true);
        nativeImage.flipY();
        // 纯黑 → 透明，便于网页叠底
        for (int y = 0; y < nativeImage.getHeight(); y++) {
            for (int x = 0; x < nativeImage.getWidth(); x++) {
                if (nativeImage.getPixelRGBA(x, y) == 0xFF000000) {
                    nativeImage.setPixelRGBA(x, y, 0);
                }
            }
        }
        try {
            Files.createDirectories(path.getParent());
            Files.deleteIfExists(path);
            nativeImage.writeToFile(path);
        } catch (Exception e) {
            Ae2LanuisMod.LOGGER.error("Failed to write icon {}", path, e);
        }
    }

    private void dispose() {
        frameBuffer.dispose();
        nativeImage.close();
    }

    /** 烘焙进度回调（聊天栏）。 */
    public interface ProgressSink {
        void onStarted(int queued);

        void onProviderTotal(String kind, int total);

        void onProgress(String kind, int index, int total, AEKey key);

        void onCompleted();
    }
}
