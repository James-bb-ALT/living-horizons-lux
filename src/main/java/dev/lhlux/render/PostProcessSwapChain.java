package dev.lhlux.render;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.lhlux.LivingHorizonsLuxMod;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;

/**
 * Reusable lazily-resized offscreen render targets (8-bit and signed 16-bit float) and zero-allocation
 * persistent VAO fullscreen quad helper.
 */
public final class PostProcessSwapChain {
    private static int quadVao;
    private static int quadVbo;

    private final Map<String, TextureTarget> targets = new HashMap<>();
    private final Map<String, FloatColorTarget> floatTargets = new HashMap<>();

    public static final class FloatColorTarget {
        private int fboId;
        private int colorTexId;
        private int width;
        private int height;

        FloatColorTarget(int width, int height) {
            allocate(width, height);
        }

        private void allocate(int w, int h) {
            int prevFbo = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
            int prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);

            this.width = w;
            this.height = h;
            this.fboId = GL30.glGenFramebuffers();
            this.colorTexId = GL11.glGenTextures();

            GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.colorTexId);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA16F, w, h, 0, GL11.GL_RGBA, GL11.GL_FLOAT, (ByteBuffer) null);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);

            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, this.fboId);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, this.colorTexId, 0);
            int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);

            GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevFbo);

            if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
                LivingHorizonsLuxMod.LOGGER.warn("RGBA16F FBO incomplete (status=0x{}), destroying", Integer.toHexString(status));
                destroy();
            }
        }

        public boolean isValid() {
            return fboId != 0 && colorTexId != 0;
        }

        public int frameBufferId() {
            return fboId;
        }

        public int colorTextureId() {
            return colorTexId;
        }

        public int width() {
            return width;
        }

        public int height() {
            return height;
        }

        public void bindWrite(boolean setViewport) {
            GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, fboId);
            if (setViewport) {
                RenderSystem.viewport(0, 0, width, height);
            }
        }

        public void destroy() {
            if (fboId != 0) {
                GL30.glDeleteFramebuffers(fboId);
                fboId = 0;
            }
            if (colorTexId != 0) {
                GL11.glDeleteTextures(colorTexId);
                colorTexId = 0;
            }
        }
    }

    public TextureTarget getOrCreate(String key, int width, int height, boolean withDepth, int filterMode) {
        int safeW = Math.max(1, width);
        int safeH = Math.max(1, height);
        TextureTarget target = targets.get(key);
        if (target == null) {
            target = new TextureTarget(safeW, safeH, withDepth, Minecraft.ON_OSX);
            target.setFilterMode(filterMode);
            targets.put(key, target);
        } else if (target.width != safeW || target.height != safeH) {
            target.resize(safeW, safeH, Minecraft.ON_OSX);
            target.setFilterMode(filterMode);
        } else if (target.filterMode != filterMode) {
            target.setFilterMode(filterMode);
        }
        return target;
    }

    public FloatColorTarget getOrCreateFloat(String key, int width, int height) {
        int safeW = Math.max(1, width);
        int safeH = Math.max(1, height);
        FloatColorTarget existing = floatTargets.get(key);
        if (existing != null && existing.isValid()) {
            if (existing.width() == safeW && existing.height() == safeH) {
                return existing;
            }
            existing.destroy();
            floatTargets.remove(key);
        }
        FloatColorTarget created = new FloatColorTarget(safeW, safeH);
        if (!created.isValid()) {
            return null;
        }
        floatTargets.put(key, created);
        return created;
    }

    public static void drawFullscreenQuad() {
        ensureQuadGeometry();
        GL30.glBindVertexArray(quadVao);
        GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
    }

    private static void ensureQuadGeometry() {
        if (quadVao != 0 && quadVbo != 0) {
            return;
        }
        int prevVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int prevVbo = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);

        quadVao = GL30.glGenVertexArrays();
        quadVbo = GL15.glGenBuffers();
        GL30.glBindVertexArray(quadVao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, quadVbo);

        // 4 vertices * (3 floats Position + 2 floats UV0) for GL_TRIANGLE_STRIP
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer verts = stack.mallocFloat(20);
            verts.put(-1.0f).put(-1.0f).put(0.0f).put(0.0f).put(0.0f);
            verts.put( 1.0f).put(-1.0f).put(0.0f).put(1.0f).put(0.0f);
            verts.put(-1.0f).put( 1.0f).put(0.0f).put(0.0f).put(1.0f);
            verts.put( 1.0f).put( 1.0f).put(0.0f).put(1.0f).put(1.0f);
            verts.flip();
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, verts, GL15.GL_STATIC_DRAW);
        }

        int stride = 5 * Float.BYTES;
        GL20.glEnableVertexAttribArray(0);
        GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, stride, 0L);
        GL20.glEnableVertexAttribArray(1);
        GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, stride, 3L * Float.BYTES);

        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, prevVbo);
        GL30.glBindVertexArray(prevVao);
    }

    public static void blitDepth(int srcFbo, int srcW, int srcH, int dstFbo, int dstW, int dstH) {
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, srcFbo);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, dstFbo);
        GL30.glBlitFramebuffer(0, 0, srcW, srcH, 0, 0, dstW, dstH, GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST);
    }

    public static void blitColor(int srcFbo, int srcW, int srcH, int dstFbo, int dstW, int dstH, boolean linear) {
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, srcFbo);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, dstFbo);
        GL30.glBlitFramebuffer(
                0, 0, srcW, srcH,
                0, 0, dstW, dstH,
                GL11.GL_COLOR_BUFFER_BIT,
                linear ? GL11.GL_LINEAR : GL11.GL_NEAREST
        );
    }

    public void destroyAll() {
        RenderSystem.assertOnRenderThreadOrInit();
        for (TextureTarget target : targets.values()) {
            if (target != null) {
                target.destroyBuffers();
            }
        }
        targets.clear();
        for (FloatColorTarget target : floatTargets.values()) {
            if (target != null) {
                target.destroy();
            }
        }
        floatTargets.clear();
    }
}
