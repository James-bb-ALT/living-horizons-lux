package dev.lhlux.render;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;

/**
 * Zero-heap-allocation RAII OpenGL state saver/restorer so post-processing and shadow passes never leak GL state
 * into Minecraft HUD, Living Horizons, Embeddium, ImmediatelyFast, Fauna Engine, Weatherglass, or Distant Horizons.
 */
public final class GlStateGuard implements AutoCloseable {
    public static final int UNIT_0 = 1;
    public static final int UNIT_1 = 2;
    public static final int UNIT_2 = 4;
    public static final int UNIT_3 = 8;
    public static final int UNITS_012 = UNIT_0 | UNIT_1 | UNIT_2;
    public static final int UNITS_0123 = UNITS_012 | UNIT_3;

    private final int unitMask;
    private final int prevDrawFbo;
    private final int prevReadFbo;
    private final int prevProgram;
    private final int prevVao;
    private final int prevActiveTexture;
    private int prevTex0;
    private int prevTex1;
    private int prevTex2;
    private int prevTex3;
    private final int vpX;
    private final int vpY;
    private final int vpW;
    private final int vpH;
    private final boolean prevBlend;
    private final boolean prevDepthTest;
    private final boolean prevCull;
    private final boolean prevScissor;
    private final boolean prevDepthMask;
    private final boolean cmR;
    private final boolean cmG;
    private final boolean cmB;
    private final boolean cmA;

    public GlStateGuard() {
        this(UNITS_012);
    }

    public GlStateGuard(int textureUnitMask) {
        this.unitMask = textureUnitMask;
        this.prevDrawFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        this.prevReadFbo = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        this.prevProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        this.prevVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        this.prevActiveTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);

        if ((textureUnitMask & UNITS_0123) != 0) {
            if ((textureUnitMask & UNIT_0) != 0) {
                GlStateManager._activeTexture(GL13.GL_TEXTURE0);
                this.prevTex0 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            }
            if ((textureUnitMask & UNIT_1) != 0) {
                GlStateManager._activeTexture(GL13.GL_TEXTURE1);
                this.prevTex1 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            }
            if ((textureUnitMask & UNIT_2) != 0) {
                GlStateManager._activeTexture(GL13.GL_TEXTURE2);
                this.prevTex2 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            }
            if ((textureUnitMask & UNIT_3) != 0) {
                GlStateManager._activeTexture(GL13.GL_TEXTURE3);
                this.prevTex3 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            }
            GlStateManager._activeTexture(this.prevActiveTexture);
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer vp = stack.mallocInt(4);
            GL11.glGetIntegerv(GL11.GL_VIEWPORT, vp);
            this.vpX = vp.get(0);
            this.vpY = vp.get(1);
            this.vpW = vp.get(2);
            this.vpH = vp.get(3);

            ByteBuffer cm = stack.malloc(4);
            GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, cm);
            this.cmR = cm.get(0) != 0;
            this.cmG = cm.get(1) != 0;
            this.cmB = cm.get(2) != 0;
            this.cmA = cm.get(3) != 0;
        }

        this.prevBlend = GL11.glIsEnabled(GL11.GL_BLEND);
        this.prevDepthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        this.prevCull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        this.prevScissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        this.prevDepthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);

        if (this.prevScissor) {
            RenderSystem.disableScissor();
        }
    }

    @Override
    public void close() {
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, prevDrawFbo);
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevReadFbo);
        GlStateManager._glUseProgram(prevProgram);
        GL30.glBindVertexArray(prevVao);

        if ((unitMask & UNITS_0123) != 0) {
            if ((unitMask & UNIT_0) != 0) {
                GlStateManager._activeTexture(GL13.GL_TEXTURE0);
                GlStateManager._bindTexture(prevTex0);
            }
            if ((unitMask & UNIT_1) != 0) {
                GlStateManager._activeTexture(GL13.GL_TEXTURE1);
                GlStateManager._bindTexture(prevTex1);
            }
            if ((unitMask & UNIT_2) != 0) {
                GlStateManager._activeTexture(GL13.GL_TEXTURE2);
                GlStateManager._bindTexture(prevTex2);
            }
            if ((unitMask & UNIT_3) != 0) {
                GlStateManager._activeTexture(GL13.GL_TEXTURE3);
                GlStateManager._bindTexture(prevTex3);
            }
            GlStateManager._activeTexture(prevActiveTexture);
        }

        RenderSystem.viewport(vpX, vpY, vpW, vpH);

        GlStateManager._colorMask(cmR, cmG, cmB, cmA);
        GlStateManager._depthMask(prevDepthMask);
        if (prevBlend) {
            RenderSystem.enableBlend();
        } else {
            RenderSystem.disableBlend();
        }
        if (prevDepthTest) {
            RenderSystem.enableDepthTest();
        } else {
            RenderSystem.disableDepthTest();
        }
        if (prevCull) {
            RenderSystem.enableCull();
        } else {
            RenderSystem.disableCull();
        }
        if (prevScissor) {
            GL11.glEnable(GL11.GL_SCISSOR_TEST);
        } else {
            RenderSystem.disableScissor();
        }
    }
}
