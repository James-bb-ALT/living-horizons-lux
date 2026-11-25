package dev.lhlux.render;

import com.mojang.blaze3d.platform.GlStateManager;
import dev.lhlux.LivingHorizonsLuxMod;
import java.io.IOException;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL43;
import org.lwjgl.system.MemoryStack;

/**
 * Standalone OpenGL 4.3 Compute Shader program helper (vendor-neutral across NVIDIA and AMD).
 */
public final class LuxComputeShader {
    private final String name;
    private final ResourceLocation compLoc;
    private final Map<String, Integer> uniformCache = new HashMap<>();

    private int programId;
    private boolean failed;

    public LuxComputeShader(String name, String compPath) {
        this.name = name;
        this.compLoc = new ResourceLocation(LivingHorizonsLuxMod.MOD_ID, compPath);
    }

    public boolean ensureCompiled() {
        if (programId != 0) {
            return true;
        }
        if (failed || !GpuCapabilities.supportsComputeAndSsbo()) {
            return false;
        }
        try {
            String src = readResource(compLoc);
            int cs = GL20.glCreateShader(GL43.GL_COMPUTE_SHADER);
            GL20.glShaderSource(cs, src);
            GL20.glCompileShader(cs);
            if (GL20.glGetShaderi(cs, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
                String log = GL20.glGetShaderInfoLog(cs);
                LivingHorizonsLuxMod.LOGGER.error("Compute shader compile error in {}: {}", compLoc, log);
                GL20.glDeleteShader(cs);
                failed = true;
                return false;
            }

            int prog = GL20.glCreateProgram();
            GL20.glAttachShader(prog, cs);
            GL20.glLinkProgram(prog);
            GL20.glDeleteShader(cs);
            if (GL20.glGetProgrami(prog, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
                String log = GL20.glGetProgramInfoLog(prog);
                LivingHorizonsLuxMod.LOGGER.error("Failed to link compute shader '{}': {}", name, log);
                GL20.glDeleteProgram(prog);
                failed = true;
                return false;
            }

            this.programId = prog;
            this.uniformCache.clear();
            return true;
        } catch (Throwable t) {
            LivingHorizonsLuxMod.LOGGER.error("Failed to load compute shader '{}'", name, t);
            failed = true;
            return false;
        }
    }

    public void bind() {
        if (programId != 0) {
            GlStateManager._glUseProgram(programId);
        }
    }

    public int uniformLocation(String uniform) {
        if (programId == 0) {
            return -1;
        }
        return uniformCache.computeIfAbsent(uniform, u -> GL20.glGetUniformLocation(programId, u));
    }

    public void setInt(String uniform, int value) {
        int loc = uniformLocation(uniform);
        if (loc >= 0) {
            GL20.glUniform1i(loc, value);
        }
    }

    public void setInt2(String uniform, int x, int y) {
        int loc = uniformLocation(uniform);
        if (loc >= 0) {
            GL20.glUniform2i(loc, x, y);
        }
    }

    public void setFloat(String uniform, float value) {
        int loc = uniformLocation(uniform);
        if (loc >= 0) {
            GL20.glUniform1f(loc, value);
        }
    }

    public void setVec2(String uniform, float x, float y) {
        int loc = uniformLocation(uniform);
        if (loc >= 0) {
            GL20.glUniform2f(loc, x, y);
        }
    }

    public void setVec3(String uniform, float x, float y, float z) {
        int loc = uniformLocation(uniform);
        if (loc >= 0) {
            GL20.glUniform3f(loc, x, y, z);
        }
    }

    public void setMat4(String uniform, Matrix4f mat) {
        int loc = uniformLocation(uniform);
        if (loc >= 0) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                FloatBuffer fb = stack.mallocFloat(16);
                mat.get(fb);
                GL20.glUniformMatrix4fv(loc, false, fb);
            }
        }
    }

    public void bindTexture(String samplerUniform, int unit, int textureId) {
        GlStateManager._activeTexture(GL13.GL_TEXTURE0 + unit);
        GlStateManager._bindTexture(textureId);
        setInt(samplerUniform, unit);
    }

    public void dispatch(int groupsX, int groupsY, int groupsZ) {
        if (programId != 0) {
            GL43.glDispatchCompute(groupsX, groupsY, groupsZ);
        }
    }

    public void destroy() {
        if (programId != 0) {
            GL20.glDeleteProgram(programId);
            programId = 0;
        }
        uniformCache.clear();
    }

    private static String readResource(ResourceLocation loc) throws IOException {
        var resource = Minecraft.getInstance().getResourceManager().getResourceOrThrow(loc);
        try (InputStream in = resource.open()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
