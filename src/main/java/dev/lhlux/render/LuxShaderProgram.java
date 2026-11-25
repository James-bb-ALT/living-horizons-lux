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
import org.lwjgl.system.MemoryStack;

/**
 * Lightweight standalone OpenGL shader program loader for Living Horizons: Lux post-processing passes.
 */
public final class LuxShaderProgram {
    private final String name;
    private final ResourceLocation vertLoc;
    private final ResourceLocation fragLoc;
    private final Map<String, Integer> uniformCache = new HashMap<>();

    private int programId;
    private boolean failed;

    public LuxShaderProgram(String name, String vertPath, String fragPath) {
        this.name = name;
        this.vertLoc = new ResourceLocation(LivingHorizonsLuxMod.MOD_ID, vertPath);
        this.fragLoc = new ResourceLocation(LivingHorizonsLuxMod.MOD_ID, fragPath);
    }

    public boolean ensureCompiled() {
        if (programId != 0) {
            return true;
        }
        if (failed) {
            return false;
        }
        try {
            String vertSrc = readResource(vertLoc);
            String fragSrc = readResource(fragLoc);
            int vs = compileShader(GL20.GL_VERTEX_SHADER, vertSrc, vertLoc.toString());
            int fs = compileShader(GL20.GL_FRAGMENT_SHADER, fragSrc, fragLoc.toString());
            if (vs == 0 || fs == 0) {
                if (vs != 0) GL20.glDeleteShader(vs);
                if (fs != 0) GL20.glDeleteShader(fs);
                failed = true;
                return false;
            }

            int prog = GL20.glCreateProgram();
            GL20.glAttachShader(prog, vs);
            GL20.glAttachShader(prog, fs);
            GL20.glBindAttribLocation(prog, 0, "Position");
            GL20.glBindAttribLocation(prog, 1, "UV0");
            GL20.glLinkProgram(prog);
            GL20.glDeleteShader(vs);
            GL20.glDeleteShader(fs);

            if (GL20.glGetProgrami(prog, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
                String log = GL20.glGetProgramInfoLog(prog);
                LivingHorizonsLuxMod.LOGGER.error("Failed to link Lux shader '{}': {}", name, log);
                GL20.glDeleteProgram(prog);
                failed = true;
                return false;
            }
            this.programId = prog;
            this.uniformCache.clear();
            return true;
        } catch (Throwable t) {
            LivingHorizonsLuxMod.LOGGER.error("Failed to load Lux shader '{}'", name, t);
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
        return uniformCache.computeIfAbsent(uniform, u -> {
            int loc = GL20.glGetUniformLocation(programId, u);
            if (loc < 0 && !u.endsWith("]")) {
                loc = GL20.glGetUniformLocation(programId, u + "[0]");
            }
            return loc;
        });
    }

    public void setInt(String uniform, int value) {
        int loc = uniformLocation(uniform);
        if (loc >= 0) {
            GL20.glUniform1i(loc, value);
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

    public void setVec4Array(String uniform, float[] xyzw, int count) {
        if (count <= 0) {
            return;
        }
        int loc = uniformLocation(uniform);
        if (loc >= 0) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                FloatBuffer fb = stack.mallocFloat(count * 4);
                fb.put(xyzw, 0, count * 4);
                fb.flip();
                GL20.glUniform4fv(loc, fb);
            }
        }
    }

    public void bindTexture(String samplerUniform, int unit, int textureId) {
        GlStateManager._activeTexture(GL13.GL_TEXTURE0 + unit);
        GlStateManager._bindTexture(textureId);
        setInt(samplerUniform, unit);
    }

    public void resetFailure() {
        failed = false;
    }

    public void destroy() {
        if (programId != 0) {
            GL20.glDeleteProgram(programId);
            programId = 0;
        }
        uniformCache.clear();
    }

    private static int compileShader(int type, String source, String debugName) {
        int id = GL20.glCreateShader(type);
        GL20.glShaderSource(id, source);
        GL20.glCompileShader(id);
        if (GL20.glGetShaderi(id, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetShaderInfoLog(id);
            LivingHorizonsLuxMod.LOGGER.error("Shader compile error in {}: {}", debugName, log);
            GL20.glDeleteShader(id);
            return 0;
        }
        return id;
    }

    private static String readResource(ResourceLocation loc) throws IOException {
        var resource = Minecraft.getInstance().getResourceManager().getResourceOrThrow(loc);
        try (InputStream in = resource.open()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
