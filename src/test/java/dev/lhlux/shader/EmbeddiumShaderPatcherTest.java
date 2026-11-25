package dev.lhlux.shader;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EmbeddiumShaderPatcherTest {
    private static final String EMBEDDIUM_0331_VSH_WITH_LH = """
            #version 330 core
            #import <sodium:include/fog.glsl>
            #import <sodium:include/chunk_vertex.glsl>
            #import <sodium:include/chunk_matrices.glsl>
            #import <sodium:include/chunk_material.glsl>
            uniform float u_LhTime;
            vec3 lh_animate(vec3 local, vec3 rel, vec4 col, float t) { return vec3(0.0); }
            vec3 lh_tint(vec3 local, vec4 col, float t) { return vec3(1.0); }
            out vec4 v_Color;
            out vec2 v_TexCoord;
            uniform vec3 u_RegionOffset;
            vec3 _get_draw_translation(uint pos) { return vec3(0.0); }
            void main() {
                _vert_init();
                vec3 translation = u_RegionOffset + _get_draw_translation(_draw_id);
                vec3 position = _vert_position + translation;

                // Living Horizons: wind, touch and water waves.
                vec3 lhLocal = _vert_position + _get_draw_translation(_draw_id);
                float lhTime = u_LhTime;
                position += lh_animate(lhLocal, position, _vert_color, lhTime);
                _vert_color.rgb *= lh_tint(lhLocal, _vert_color, lhTime);
                gl_Position = vec4(position, 1.0);
                v_Color = _vert_color * _sample_lightmap(u_LightTex, _vert_tex_light_coord);
            }
            """;

    private static final String EMBEDDIUM_0331_FSH = """
            #version 330 core
            #import <sodium:include/fog.glsl>
            in vec4 v_Color;
            in vec2 v_TexCoord;
            in float v_FragDistance;
            in float v_MaterialMipBias;
            in float v_MaterialAlphaCutoff;
            uniform sampler2D u_BlockTex;
            uniform vec4 u_FogColor;
            uniform float u_FogStart;
            uniform float u_FogEnd;
            out vec4 fragColor;
            void main() {
                vec4 diffuseColor = texture(u_BlockTex, v_TexCoord, v_MaterialMipBias);
            #ifdef USE_FRAGMENT_DISCARD
                if (diffuseColor.a < v_MaterialAlphaCutoff) {
                    discard;
                }
            #endif
                diffuseColor.rgb *= v_Color.rgb;
                diffuseColor.rgb *= v_Color.a;
                fragColor = _linearFog(diffuseColor, v_FragDistance, u_FogColor, u_FogStart, u_FogEnd);
            }
            """;

    @Test
    void preservesLivingHorizonsSwayAndInjectsCsmForEmbeddium0331Idempotently() {
        ResourceLocation vshLoc = new ResourceLocation("sodium", "blocks/block_layer_opaque.vsh");
        ResourceLocation fshLoc = new ResourceLocation("sodium", "blocks/block_layer_opaque.fsh");

        String patchedVsh = EmbeddiumShaderPatcher.patchShaderSource(vshLoc, EMBEDDIUM_0331_VSH_WITH_LH);
        assertTrue(patchedVsh.contains("lh_animate("));
        assertTrue(patchedVsh.contains("lh_tint("));
        assertTrue(patchedVsh.contains("v_LhLuxWorldRelPos = position;"));
        assertTrue(patchedVsh.contains("float(_vert_tex_light_coord.x) / 240.0"));
        assertTrue(patchedVsh.contains("float(_vert_tex_light_coord.y) / 240.0"));
        assertFalse(patchedVsh.contains("_vert_tex_light_coord >>"));
        assertTrue(patchedVsh.indexOf("v_LhLuxWorldRelPos = position;") > patchedVsh.indexOf("_vert_color.rgb *= lh_tint("));
        assertEquals(patchedVsh, EmbeddiumShaderPatcher.patchShaderSource(vshLoc, patchedVsh));

        String patchedFsh = EmbeddiumShaderPatcher.patchShaderSource(fshLoc, EMBEDDIUM_0331_FSH);
        assertTrue(patchedFsh.contains("uniform sampler2DShadow u_LhLuxNearShadow;"));
        assertTrue(patchedFsh.contains("uniform sampler2DShadow u_LhLuxFarShadow;"));
        assertTrue(patchedFsh.contains("lhlux_sample_pcf(sampler2DShadow depthMap"));
        assertTrue(patchedFsh.contains("lhlux_sample_rgss(u_BlockTex, v_TexCoord, v_MaterialMipBias)"));
        assertTrue(patchedFsh.contains("diffuseColor.rgb *= lhlux_compute_shadow();"));
        assertTrue(patchedFsh.indexOf("diffuseColor.rgb *= lhlux_compute_shadow();") < patchedFsh.indexOf("fragColor = _linearFog("));
        assertEquals(patchedFsh, EmbeddiumShaderPatcher.patchShaderSource(fshLoc, patchedFsh));
    }
}
