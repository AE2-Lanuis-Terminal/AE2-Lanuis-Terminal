package com.lanuis.ae2web.client.icon.foundation;

import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;

import java.nio.IntBuffer;

/**
 * 离屏 FBO，供 AEKey 图标烘焙；仅客户端加载。
 */
public final class FrameBuffer {
    private int xSize;
    private int ySize;
    private final boolean hasDepth;
    private final int framebufferId;
    private int colorTextureId;
    private int depthTextureId;
    private float clearColorR;
    private float clearColorG;
    private float clearColorB;
    private float clearColorA;

    public FrameBuffer(int xSize, int ySize) {
        this(xSize, ySize, true);
    }

    public FrameBuffer(int xSize, int ySize, boolean hasDepth) {
        this.xSize = xSize;
        this.ySize = ySize;
        this.hasDepth = hasDepth;
        this.framebufferId = GL30.glGenFramebuffers();
        createTexture();
        int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException("Incomplete framebuffer, status: " + status);
        }
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
    }

    private void createTexture() {
        colorTextureId = GL11.glGenTextures();
        if (hasDepth) {
            depthTextureId = GL11.glGenTextures();
        }
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, colorTextureId);
        GL11.glTexImage2D(
                GL11.GL_TEXTURE_2D,
                0,
                GL11.GL_RGBA,
                xSize,
                ySize,
                0,
                GL11.GL_RGBA,
                GL11.GL_UNSIGNED_BYTE,
                (IntBuffer) null
        );
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        if (hasDepth) {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthTextureId);
            GL11.glTexImage2D(
                    GL11.GL_TEXTURE_2D,
                    0,
                    GL11.GL_DEPTH_COMPONENT,
                    xSize,
                    ySize,
                    0,
                    GL11.GL_DEPTH_COMPONENT,
                    GL11.GL_FLOAT,
                    (IntBuffer) null
            );
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_COMPARE_MODE, GL11.GL_ZERO);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        }
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebufferId);
        GL30.glFramebufferTexture2D(
                GL30.GL_FRAMEBUFFER,
                GL30.GL_COLOR_ATTACHMENT0,
                GL11.GL_TEXTURE_2D,
                colorTextureId,
                0
        );
        if (hasDepth) {
            GL30.glFramebufferTexture2D(
                    GL30.GL_FRAMEBUFFER,
                    GL30.GL_DEPTH_ATTACHMENT,
                    GL11.GL_TEXTURE_2D,
                    depthTextureId,
                    0
            );
        }
    }

    public void clear() {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebufferId);
        GL11.glClearColor(clearColorR, clearColorG, clearColorB, clearColorA);
        if (hasDepth) {
            GL11.glClearDepth(1.0);
        }
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | (hasDepth ? GL11.GL_DEPTH_BUFFER_BIT : 0));
        if (Minecraft.ON_OSX) {
            GL11.glGetError();
        }
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
    }

    public void bindRead() {
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, colorTextureId);
    }

    public void bindWrite(boolean setViewport) {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebufferId);
        if (setViewport) {
            GL11.glViewport(0, 0, xSize, ySize);
        }
    }

    public void unbindWrite() {
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, 0);
    }

    public void dispose() {
        GL11.glDeleteTextures(colorTextureId);
        if (hasDepth) {
            GL11.glDeleteTextures(depthTextureId);
        }
        GL30.glDeleteFramebuffers(framebufferId);
    }
}
