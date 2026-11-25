package dev.lhlux.shader;

/**
 * Patches Living Horizons' {@code livinghorizons_world_light.fsh} water-surface check when Wavify
 * 3D wave compatibility is enabled so sun/moon glitter renders on top of 3D wave geometry instead
 * of being occluded or passing underneath the waves.
 */
public final class LivingHorizonsWorldLightShaderPatcher {
    private static final String MARKER = "lhlux_wavify_compat";
    private static final String TARGET =
            "vec4 lhWaterSurface(vec3 world, vec3 normal, ivec3 camMod) {\n"
            + "    ivec3 b = ivec3(floor(world));\n"
            + "    ivec3 w = (camMod + b) & ivec3(127, 63, 127);\n"
            + "    int i = w.z * 128 + w.x;\n"
            + "    vec4 column = texelFetch(VoxelSampler, ivec2(i & 2047, LH_WATER_ROW + (i >> 11)), 0);\n"
            + "    if (column.a < 0.5 || int(column.r * 255.0 + 0.5) != w.y) {\n"
            + "        return vec4(0.0);\n"
            + "    }\n"
            + "    // Only the top of the water: its height, less the 0.001 vanilla draws it below, and up to 0.032 lower in the\n"
            + "    // waves of the Living World pack.\n"
            + "    float f = world.y - float(b.y);\n"
            + "    if (f < column.g - 0.045 || f > column.g + 0.01 || normal.y < 0.8) {\n"
            + "        return vec4(0.0);\n"
            + "    }\n"
            + "    return column;\n"
            + "}";

    private static final String REPLACEMENT =
            "vec4 lhWaterSurface(vec3 world, vec3 normal, ivec3 camMod) { // " + MARKER + "\n"
            + "    ivec3 b = ivec3(floor(world));\n"
            + "    ivec3 w = (camMod + b) & ivec3(127, 63, 127);\n"
            + "    int i = w.z * 128 + w.x;\n"
            + "    vec4 column = texelFetch(VoxelSampler, ivec2(i & 2047, LH_WATER_ROW + (i >> 11)), 0);\n"
            + "    if (column.a < 0.5) {\n"
            + "        return vec4(0.0);\n"
            + "    }\n"
            + "    int waterWy = int(column.r * 255.0 + 0.5);\n"
            + "    int dy = (w.y - waterWy) & 63;\n"
            + "    float f = world.y - float(b.y);\n"
            + "    if (dy == 0) {\n"
            + "        if (f < column.g - 0.045 || (f > column.g + 0.01 && normal.y < 0.25) || normal.y < 0.25) {\n"
            + "            return vec4(0.0);\n"
            + "        }\n"
            + "        if (f > column.g + 0.01) {\n"
            + "            return vec4(column.r, column.g, 1.0, column.a);\n"
            + "        }\n"
            + "        return column;\n"
            + "    }\n"
            + "    if (dy <= 2 && normal.y >= 0.20) {\n"
            + "        return vec4(column.r, column.g, 1.0, column.a);\n"
            + "    }\n"
            + "    return vec4(0.0);\n"
            + "}";

    private LivingHorizonsWorldLightShaderPatcher() {
    }

    public static String patchForWavify(String source) {
        if (source == null || source.contains(MARKER)) {
            return source;
        }
        String normalized = source.replace("\r\n", "\n");
        if (!normalized.contains(TARGET)) {
            return source;
        }
        return normalized.replace(TARGET, REPLACEMENT);
    }
}
