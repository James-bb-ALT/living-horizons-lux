package dev.lhlux.shader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WorldLightShaderPatcherTest {
    @Test
    void patchesLhWaterSurfaceIdempotentlyForWavify3dWaves() {
        String sample = ""
                + "vec4 lhWaterSurface(vec3 world, vec3 normal, ivec3 camMod) {\r\n"
                + "    ivec3 b = ivec3(floor(world));\r\n"
                + "    ivec3 w = (camMod + b) & ivec3(127, 63, 127);\r\n"
                + "    int i = w.z * 128 + w.x;\r\n"
                + "    vec4 column = texelFetch(VoxelSampler, ivec2(i & 2047, LH_WATER_ROW + (i >> 11)), 0);\r\n"
                + "    if (column.a < 0.5 || int(column.r * 255.0 + 0.5) != w.y) {\r\n"
                + "        return vec4(0.0);\r\n"
                + "    }\r\n"
                + "    // Only the top of the water: its height, less the 0.001 vanilla draws it below, and up to 0.032 lower in the\r\n"
                + "    // waves of the Living World pack.\r\n"
                + "    float f = world.y - float(b.y);\r\n"
                + "    if (f < column.g - 0.045 || f > column.g + 0.01 || normal.y < 0.8) {\r\n"
                + "        return vec4(0.0);\r\n"
                + "    }\r\n"
                + "    return column;\r\n"
                + "}\r\n";

        String patched = LivingHorizonsWorldLightShaderPatcher.patchForWavify(sample);
        assertTrue(patched.contains("lhlux_wavify_compat"));
        assertTrue(patched.contains("if (dy <= 2 && normal.y >= 0.20)"));
        assertEquals(patched, LivingHorizonsWorldLightShaderPatcher.patchForWavify(patched));
    }
}
