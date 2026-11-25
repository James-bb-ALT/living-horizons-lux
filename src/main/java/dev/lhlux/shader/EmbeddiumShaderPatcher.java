package dev.lhlux.shader;

import dev.lhlux.LivingHorizonsLuxMod;
import net.minecraft.resources.ResourceLocation;

/**
 * Compositional GLSL patcher for Embeddium 0.3.31 terrain shaders.
 * Runs AFTER Living Horizons' SodiumShaderPatch so swaying plants/water and Living Horizons vertex animations
 * stay 100% intact while adding CSM world-relative projection, receiver-plane bias PCF, and optional RGSS AA.
 */
public final class EmbeddiumShaderPatcher {
    private static final String TARGET_VSH = "blocks/block_layer_opaque.vsh";
    private static final String TARGET_FSH = "blocks/block_layer_opaque.fsh";

    private static final String VSH_MARKER = "v_LhLuxWorldRelPos";
    private static final String FSH_MARKER = "u_LhLuxNearShadow";

    private static final String VSH_POSITION_ANCHOR = "vec3 position = _vert_position + translation;";
    private static final String VSH_LH_TINT_ANCHOR = "_vert_color.rgb *= lh_tint(";
    private static final String VSH_LH_ANIM_ANCHOR = "position += lh_animate(";

    private static final String FSH_TEX_MIP_ANCHOR = "vec4 diffuseColor = texture(u_BlockTex, v_TexCoord, v_MaterialMipBias);";
    private static final String FSH_TEX_SIMPLE_ANCHOR = "vec4 diffuseColor = texture(u_BlockTex, v_TexCoord);";

    private static final String FSH_LINEAR_FOG_ANCHOR = "fragColor = _linearFog(diffuseColor, v_FragDistance, u_FogColor, u_FogStart, u_FogEnd);";
    private static final String FSH_LEGACY_FOG_ANCHOR = "fragColor = diffuseColor * getFogFactor();";

    private EmbeddiumShaderPatcher() {
    }

    public static String patchShaderSource(ResourceLocation name, String source) {
        if (name == null || source == null || !"sodium".equals(name.getNamespace())) {
            return source;
        }
        String path = name.getPath();
        if (TARGET_VSH.equals(path)) {
            return patchVertexShader(source);
        }
        if (TARGET_FSH.equals(path)) {
            return patchFragmentShader(source);
        }
        return source;
    }

    public static String patchVertexShader(String source) {
        if (source.contains(VSH_MARKER)) {
            return source;
        }
        int mainIdx = source.indexOf("void main()");
        if (mainIdx < 0) {
            LivingHorizonsLuxMod.LOGGER.warn("EmbeddiumShaderPatcher: could not locate void main() in block_layer_opaque.vsh");
            return source;
        }

        String declarations = "\n// Living Horizons: Lux CSM varyings\n"
                + "out vec3 v_LhLuxWorldRelPos;\n"
                + "out float v_LhLuxSkyLight;\n"
                + "out float v_LhLuxBlockLight;\n";

        StringBuilder out = new StringBuilder(source.length() + 512);
        out.append(source, 0, mainIdx);
        out.append(declarations);
        out.append(source, mainIdx, source.length());
        String withDecls = out.toString();

        // Inject right after Living Horizons' animation/tint if present, otherwise right after position declaration
        int anchorEnd = findEndOfStatement(withDecls, VSH_LH_TINT_ANCHOR);
        if (anchorEnd < 0) {
            anchorEnd = findEndOfStatement(withDecls, VSH_LH_ANIM_ANCHOR);
        }
        if (anchorEnd < 0) {
            int posIdx = withDecls.indexOf(VSH_POSITION_ANCHOR);
            if (posIdx >= 0) {
                anchorEnd = posIdx + VSH_POSITION_ANCHOR.length();
            }
        }
        if (anchorEnd < 0) {
            LivingHorizonsLuxMod.LOGGER.warn("EmbeddiumShaderPatcher: position anchor missing in block_layer_opaque.vsh; skipping CSM varying injection");
            return source;
        }

        String assign;
        if (withDecls.contains("_vert_tex_light_coord") || withDecls.contains("chunk_vertex.glsl")) {
            // Embeddium 0.3.31 defines _vert_tex_light_coord as ivec2(blockLight, skyLight) in 0..240
            assign = "\n    v_LhLuxWorldRelPos = position;\n"
                    + "    v_LhLuxBlockLight = clamp(float(_vert_tex_light_coord.x) / 240.0, 0.0, 1.0);\n"
                    + "    v_LhLuxSkyLight = clamp(float(_vert_tex_light_coord.y) / 240.0, 0.0, 1.0);\n";
        } else if (withDecls.contains("v_LightCoord")) {
            assign = "\n    v_LhLuxWorldRelPos = position;\n"
                    + "    v_LhLuxBlockLight = clamp(v_LightCoord.x, 0.0, 1.0);\n"
                    + "    v_LhLuxSkyLight = clamp(v_LightCoord.y, 0.0, 1.0);\n";
        } else {
            assign = "\n    v_LhLuxWorldRelPos = position;\n"
                    + "    v_LhLuxBlockLight = 0.0;\n"
                    + "    v_LhLuxSkyLight = 1.0;\n";
        }

        return withDecls.substring(0, anchorEnd) + assign + withDecls.substring(anchorEnd);
    }

    public static String patchFragmentShader(String source) {
        if (source.contains(FSH_MARKER)) {
            return source;
        }
        int mainIdx = source.indexOf("void main()");
        String fogAnchor = source.contains(FSH_LINEAR_FOG_ANCHOR)
                ? FSH_LINEAR_FOG_ANCHOR
                : (source.contains(FSH_LEGACY_FOG_ANCHOR) ? FSH_LEGACY_FOG_ANCHOR : null);
        if (mainIdx < 0 || fogAnchor == null) {
            LivingHorizonsLuxMod.LOGGER.warn("EmbeddiumShaderPatcher: expected anchors missing in block_layer_opaque.fsh");
            return source;
        }

        String header = "\n// Living Horizons: Lux Hardware PCF CSM + RGSS uniforms and helpers\n"
                + "in vec3 v_LhLuxWorldRelPos;\n"
                + "in float v_LhLuxSkyLight;\n"
                + "in float v_LhLuxBlockLight;\n"
                + "uniform sampler2DShadow u_LhLuxNearShadow;\n"
                + "uniform sampler2DShadow u_LhLuxFarShadow;\n"
                + "uniform mat4 u_LhLuxNearMat;\n"
                + "uniform mat4 u_LhLuxFarMat;\n"
                + "uniform vec4 u_LhLuxCascadeParams; // x=nearRadius, y=farRadius, z=texelSize, w=strength\n"
                + "uniform vec3 u_LhLuxSunDir;\n"
                + "uniform int u_LhLuxEnabled;\n"
                + "uniform int u_LhLuxPcfMode;\n"
                + "uniform int u_LhLuxRgssEnabled;\n"
                + "uniform int u_LhLuxShadowPass;\n"
                + "\n"
                + "vec2 lhlux_receiver_grad(vec3 proj) {\n"
                + "    vec3 dx = dFdx(proj);\n"
                + "    vec3 dy = dFdy(proj);\n"
                + "    float det = dx.x * dy.y - dx.y * dy.x;\n"
                + "    if (abs(det) <= 1.0e-5) return vec2(0.0);\n"
                + "    vec2 g = vec2((dx.z * dy.y - dx.y * dy.z) / det, (dx.x * dy.z - dx.z * dy.x) / det);\n"
                + "    return clamp(g, vec2(-0.05), vec2(0.05));\n"
                + "}\n"
                + "\n"
                + "float lhlux_sample_pcf(sampler2DShadow depthMap, vec3 proj, float texelSize, float bias, int mode) {\n"
                + "    if (proj.x <= 0.002 || proj.x >= 0.998 || proj.y <= 0.002 || proj.y >= 0.998 || proj.z <= 0.0 || proj.z >= 1.0) {\n"
                + "        return 1.0;\n"
                + "    }\n"
                + "    float baseZ = clamp(proj.z - bias, 0.0, 1.0);\n"
                + "    if (mode <= 0) {\n"
                + "        return texture(depthMap, vec3(proj.xy, baseZ));\n"
                + "    }\n"
                + "    vec2 grad = lhlux_receiver_grad(proj);\n"
                + "    const vec2 poisson4[4] = vec2[](vec2(-0.47, 0.31), vec2(0.42, 0.52), vec2(-0.29, -0.58), vec2(0.61, -0.24));\n"
                + "    float spread = (mode >= 2) ? (texelSize * 1.65) : (texelSize * 0.95);\n"
                + "    float sum = 0.0;\n"
                + "    for (int i = 0; i < 4; ++i) {\n"
                + "        vec2 off = poisson4[i] * spread;\n"
                + "        sum += texture(depthMap, vec3(proj.xy + off, clamp(proj.z + dot(grad, off) - bias, 0.0, 1.0)));\n"
                + "    }\n"
                + "    if (mode == 1) {\n"
                + "        return sum * 0.25;\n"
                + "    }\n"
                + "    float center = texture(depthMap, vec3(proj.xy, baseZ));\n"
                + "    return (sum + center) * 0.20;\n"
                + "}\n"
                + "\n"
                + "vec4 lhlux_sample_rgss(sampler2D tex, vec2 uv, float mipBias) {\n"
                + "    vec2 du = dFdx(uv);\n"
                + "    vec2 dv = dFdy(uv);\n"
                + "    vec2 off[4] = vec2[](vec2(0.125, 0.375), vec2(-0.125, -0.375), vec2(0.375, -0.125), vec2(-0.375, 0.125));\n"
                + "    vec4 acc = vec4(0.0);\n"
                + "    for (int i = 0; i < 4; ++i) {\n"
                + "        acc += texture(tex, uv + du * off[i].x + dv * off[i].y, mipBias);\n"
                + "    }\n"
                + "    return acc * 0.25;\n"
                + "}\n"
                + "\n"
                + "float lhlux_compute_shadow() {\n"
                + "    if (u_LhLuxEnabled == 0 || u_LhLuxShadowPass != 0 || u_LhLuxCascadeParams.w <= 0.001) return 1.0;\n"
                + "    float distXZ = length(v_LhLuxWorldRelPos.xz);\n"
                + "    float farRadius = max(u_LhLuxCascadeParams.y, 1.0);\n"
                + "    if (distXZ >= farRadius) return 1.0;\n"
                + "    vec3 geomNormal = normalize(cross(dFdx(v_LhLuxWorldRelPos), dFdy(v_LhLuxWorldRelPos)) + vec3(1.0e-5));\n"
                + "    float nDotL = dot(geomNormal, normalize(u_LhLuxSunDir));\n"
                + "    float bias = mix(0.0028, 0.0007, clamp(abs(nDotL), 0.0, 1.0));\n"
                + "    float nearVis = 1.0;\n"
                + "    float nearRadius = max(u_LhLuxCascadeParams.x, 1.0);\n"
                + "    if (distXZ < nearRadius) {\n"
                + "        vec4 np = u_LhLuxNearMat * vec4(v_LhLuxWorldRelPos, 1.0);\n"
                + "        nearVis = lhlux_sample_pcf(u_LhLuxNearShadow, np.xyz / max(np.w, 1.0e-5), u_LhLuxCascadeParams.z, bias, u_LhLuxPcfMode);\n"
                + "    }\n"
                + "    vec4 fp = u_LhLuxFarMat * vec4(v_LhLuxWorldRelPos, 1.0);\n"
                + "    float farVis = lhlux_sample_pcf(u_LhLuxFarShadow, fp.xyz / max(fp.w, 1.0e-5), u_LhLuxCascadeParams.z, bias * 2.2, min(u_LhLuxPcfMode, 1));\n"
                + "    float cascadeBlend = smoothstep(nearRadius * 0.72, nearRadius * 0.98, distXZ);\n"
                + "    float vis = mix(nearVis, farVis, cascadeBlend);\n"
                + "    // Radial fade before Distant Horizons boundary\n"
                + "    float edgeFade = smoothstep(farRadius * 0.82, farRadius, distXZ);\n"
                + "    vis = mix(vis, 1.0, edgeFade);\n"
                + "    // Only attenuate sky-lit direct sun; keep block/colored light bright in caves/indoors\n"
                + "    float skyWeight = smoothstep(0.15, 0.85, v_LhLuxSkyLight) * (1.0 - smoothstep(0.35, 0.95, v_LhLuxBlockLight));\n"
                + "    return mix(1.0, mix(1.0 - u_LhLuxCascadeParams.w, 1.0, vis), skyWeight);\n"
                + "}\n\n";

        StringBuilder out = new StringBuilder(source.length() + header.length() + 256);
        out.append(source, 0, mainIdx);
        out.append(header);
        out.append(source, mainIdx, source.length());
        String result = out.toString();

        if (result.contains(FSH_TEX_MIP_ANCHOR)) {
            result = result.replace(
                    FSH_TEX_MIP_ANCHOR,
                    "vec4 diffuseColor = (u_LhLuxRgssEnabled != 0) ? lhlux_sample_rgss(u_BlockTex, v_TexCoord, v_MaterialMipBias) : texture(u_BlockTex, v_TexCoord, v_MaterialMipBias);"
            );
        } else if (result.contains(FSH_TEX_SIMPLE_ANCHOR)) {
            result = result.replace(
                    FSH_TEX_SIMPLE_ANCHOR,
                    "vec4 diffuseColor = (u_LhLuxRgssEnabled != 0) ? lhlux_sample_rgss(u_BlockTex, v_TexCoord, 0.0) : texture(u_BlockTex, v_TexCoord);"
            );
        }

        result = result.replace(
                fogAnchor,
                "diffuseColor.rgb *= lhlux_compute_shadow();\n    " + fogAnchor
        );
        return result;
    }

    private static int findEndOfStatement(String src, String anchor) {
        int idx = src.indexOf(anchor);
        if (idx < 0) {
            return -1;
        }
        int semi = src.indexOf(';', idx);
        return semi >= 0 ? semi + 1 : -1;
    }
}
