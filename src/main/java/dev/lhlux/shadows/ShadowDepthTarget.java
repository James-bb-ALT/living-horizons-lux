package dev.lhlux.shadows;

import com.mojang.blaze3d.platform.GlStateManager;
import dev.lhlux.render.GpuCapabilities;
import java.nio.ByteBuffer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;

/**
 * Depth-only OpenGL framebuffer target configured for Hardware PCF ({@code sampler2DShadow})
 * with border=1.0 and 32F precision on both NVIDIA and AMD GPUs.
 */
public final class ShadowDepthTarget {
    private static final float[] BORDER_ONE = {1.0f, 1.0f, 1.0f, 1.0f};

    private int fboId;
    private int depthTextureId;
    private int size;

    public void ensureSize(int resolution) {
        int clamped = Math.max(256, resolution);
        if (fboId != 0 && size == clamped) {
            return;
        }
        destroy();
        this.size = clamped;

        this.depthTextureId = GlStateManager._genTexture();
        GlStateManager._bindTexture(depthTextureId);
        // Hardware PCF uses GL_LINEAR + COMPARE_REF_TO_TEXTURE (works natively on NVIDIA and AMD units)
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL13.GL_CLAMP_TO_BORDER);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL13.GL_CLAMP_TO_BORDER);
        GL11.glTexParameterfv(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_BORDER_COLOR, BORDER_ONE);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_COMPARE_MODE, GL30.GL_COMPARE_REF_TO_TEXTURE);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_COMPARE_FUNC, GL11.GL_LEQUAL);

        int internalFormat = GpuCapabilities.supportsDepth32f() ? GL30.GL_DEPTH_COMPONENT32F : GL14.GL_DEPTH_COMPONENT24;
        GL11.glTexImage2D(
                GL11.GL_TEXTURE_2D,
                0,
                internalFormat,
                clamped,
                clamped,
                0,
                GL11.GL_DEPTH_COMPONENT,
                GL11.GL_FLOAT,
                (ByteBuffer) null
        );

        this.fboId = GlStateManager.glGenFramebuffers();
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, fboId);
        GlStateManager._glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, depthTextureId, 0);
        GL11.glDrawBuffer(GL11.GL_NONE);
        GL11.glReadBuffer(GL11.GL_NONE);

        int status = GlStateManager.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            destroy();
            throw new IllegalStateException("ShadowDepthTarget incomplete framebuffer status: 0x" + Integer.toHexString(status));
        }
        GlStateManager._bindTexture(0);
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
    }

    public void bindForWrite() {
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, fboId);
        GlStateManager._viewport(0, 0, size, size);
    }

    public int depthTextureId() {
        return depthTextureId;
    }

    public int size() {
        return size;
    }

    public boolean isAllocated() {
        return fboId != 0 && depthTextureId != 0;
    }

    public void destroy() {
        if (fboId != 0) {
            GlStateManager._glDeleteFramebuffers(fboId);
            fboId = 0;
        }
        if (depthTextureId != 0) {
            GlStateManager._deleteTexture(depthTextureId);
            depthTextureId = 0;
        }
        size = 0;
    }
}
