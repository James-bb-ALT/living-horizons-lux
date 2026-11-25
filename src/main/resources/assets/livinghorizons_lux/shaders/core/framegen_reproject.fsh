#version 150

uniform sampler2D HistoryColor;
uniform sampler2D MotionSampler;
uniform vec2 SourceTexelSize;
uniform float ReprojectionStrength;

in vec2 texCoord0;
out vec4 fragColor;

vec2 clampUv(vec2 uv) {
    vec2 inset = max(SourceTexelSize * 0.55, vec2(0.00005));
    return clamp(uv, inset, vec2(1.0) - inset);
}

vec4 nearestHistory(vec2 uv) {
    ivec2 sz = textureSize(HistoryColor, 0);
    ivec2 px = clamp(ivec2(clamp(uv, vec2(0.0), vec2(0.999999)) * vec2(sz)), ivec2(0), sz - ivec2(1));
    return texelFetch(HistoryColor, px, 0);
}

vec3 disocclusionFill(vec2 rawUv, vec2 safeUv) {
    vec2 inward = vec2(0.0);
    if (rawUv.x < 0.0) inward.x = 1.0;
    else if (rawUv.x > 1.0) inward.x = -1.0;
    if (rawUv.y < 0.0) inward.y = 1.0;
    else if (rawUv.y > 1.0) inward.y = -1.0;

    if (dot(inward, inward) < 0.5) {
        vec2 toCenter = vec2(0.5) - safeUv;
        float l = length(toCenter);
        inward = l > 1.0e-5 ? toCenter / l : vec2(0.0, 1.0);
    } else {
        inward = normalize(inward);
    }

    vec3 c0 = nearestHistory(safeUv).rgb;
    vec3 c1 = texture(HistoryColor, clampUv(safeUv + inward * SourceTexelSize * 2.0)).rgb;
    vec3 c2 = texture(HistoryColor, clampUv(safeUv + inward * SourceTexelSize * 5.0)).rgb;
    return c0 * 0.58 + c1 * 0.27 + c2 * 0.15;
}

void main() {
    float maxTexel = max(SourceTexelSize.x, SourceTexelSize.y);
    float strength = clamp(ReprojectionStrength, 0.0, 1.0);

    vec2 sourceUv = texCoord0;
    float valid = 1.0;
    float motionEdge = 0.0;

    // 3-step fixed-point reverse search to converge cleanly under parallax
    for (int i = 0; i < 3; ++i) {
        if (sourceUv.x < 0.0 || sourceUv.x > 1.0 || sourceUv.y < 0.0 || sourceUv.y > 1.0) {
            valid = 0.0;
            break;
        }
        vec4 m = texture(MotionSampler, sourceUv);
        if (m.z < 0.5) {
            valid = 0.0;
            break;
        }
        motionEdge = max(motionEdge, m.w);
        vec2 nextUv = texCoord0 - m.xy * strength;
        if (length(nextUv - sourceUv) < maxTexel * 0.25) {
            sourceUv = nextUv;
            break;
        }
        sourceUv = nextUv;
    }

    bool outOfBounds = sourceUv.x < 0.0 || sourceUv.x > 1.0 || sourceUv.y < 0.0 || sourceUv.y > 1.0;
    vec2 safeUv = clampUv(sourceUv);

    if (!outOfBounds && valid >= 0.5) {
        // Forward-backward consistency residual check rejects foreground jelly-stretching on disocclusions
        vec4 mCheck = texture(MotionSampler, safeUv);
        vec2 reprojectedBack = safeUv + mCheck.xy * strength;
        if (mCheck.z < 0.5 || length(reprojectedBack - texCoord0) > maxTexel * 4.5) {
            valid = 0.0;
        }
    }

    if (outOfBounds || valid < 0.5) {
        fragColor = vec4(disocclusionFill(sourceUv, safeUv), 1.0);
        return;
    }

    vec4 src = texture(HistoryColor, safeUv);
    float edgeFactor = max(motionEdge, src.a);
    if (edgeFactor > 0.35) {
        vec3 crisp = nearestHistory(safeUv).rgb;
        fragColor = vec4(mix(src.rgb, crisp, smoothstep(0.35, 0.75, edgeFactor)), 1.0);
    } else {
        fragColor = vec4(src.rgb, 1.0);
    }
}
