#version 150

uniform sampler2D PrevColor;
uniform sampler2D CurrColor;
uniform sampler2D MotionSampler;
uniform sampler2D HistoryDepth;
uniform mat4 PrevToPrevPrevMat;
uniform mat4 PrevToPrevPrevSkyMat;
uniform vec2 SourceTexelSize;
uniform float ReprojectionStrength;
uniform float BlendAlpha;

in vec2 texCoord0;
out vec4 fragColor;

vec2 clampUv(vec2 uv) {
    vec2 inset = max(SourceTexelSize * 0.55, vec2(0.00005));
    return clamp(uv, inset, vec2(1.0) - inset);
}

vec4 nearestCurr(vec2 uv) {
    ivec2 sz = textureSize(CurrColor, 0);
    ivec2 px = clamp(ivec2(clamp(uv, vec2(0.0), vec2(0.999999)) * vec2(sz)), ivec2(0), sz - ivec2(1));
    return texelFetch(CurrColor, px, 0);
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

    vec3 c0 = nearestCurr(safeUv).rgb;
    vec3 c1 = texture(CurrColor, clampUv(safeUv + inward * SourceTexelSize * 2.0)).rgb;
    vec3 c2 = texture(CurrColor, clampUv(safeUv + inward * SourceTexelSize * 5.0)).rgb;
    return c0 * 0.58 + c1 * 0.27 + c2 * 0.15;
}

void main() {
    float maxTexel = max(SourceTexelSize.x, SourceTexelSize.y);
    float strength = clamp(ReprojectionStrength, 0.0, 1.0);

    vec2 sourceUv = texCoord0;
    float valid = 1.0;
    float motionEdge = 0.0;

    // 3-step fixed-point reverse search into CurrColor
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
        vec4 mCheck = texture(MotionSampler, safeUv);
        vec2 reprojectedBack = safeUv + mCheck.xy * strength;
        if (mCheck.z < 0.5 || length(reprojectedBack - texCoord0) > maxTexel * 4.5) {
            valid = 0.0;
        }
    }

    vec4 currSample = texture(CurrColor, safeUv);
    float edgeFactor = max(motionEdge, currSample.a);
    vec3 currRgb = currSample.rgb;
    if (edgeFactor > 0.35) {
        vec3 crisp = nearestCurr(safeUv).rgb;
        currRgb = mix(currRgb, crisp, smoothstep(0.35, 0.75, edgeFactor));
    }

    // Reproject the CurrColor history point accurately into PrevColor using CurrHistory's depth
    float histDepth = texture(HistoryDepth, safeUv).r;
    vec4 histClip = vec4(safeUv * 2.0 - 1.0, histDepth * 2.0 - 1.0, 1.0);
    vec4 prevClip = (histDepth >= 0.99995)
            ? (PrevToPrevPrevSkyMat * histClip)
            : (PrevToPrevPrevMat * histClip);

    bool prevValid = prevClip.w > 1.0e-5;
    vec2 prevUv = safeUv;
    if (prevValid) {
        vec3 prevNdc = prevClip.xyz / prevClip.w;
        prevUv = prevNdc.xy * 0.5 + 0.5;
        prevValid = prevUv.x >= 0.0 && prevUv.x <= 1.0 && prevUv.y >= 0.0 && prevUv.y <= 1.0;
    }

    if (outOfBounds || valid < 0.5) {
        vec3 fill = disocclusionFill(sourceUv, safeUv);
        if (prevValid) {
            vec3 prevRgb = texture(PrevColor, clampUv(prevUv)).rgb;
            fill = mix(fill, prevRgb, 0.35);
        }
        fragColor = vec4(fill, 1.0);
        return;
    }

    if (!prevValid || edgeFactor > 0.35) {
        fragColor = vec4(currRgb, 1.0);
        return;
    }

    // Neighborhood color-clamping (3x3 cross around safeUv) prevents ghosting/doubling on fast motion
    vec3 n0 = currRgb;
    vec3 n1 = texture(CurrColor, clampUv(safeUv + vec2(SourceTexelSize.x, 0.0))).rgb;
    vec3 n2 = texture(CurrColor, clampUv(safeUv - vec2(SourceTexelSize.x, 0.0))).rgb;
    vec3 n3 = texture(CurrColor, clampUv(safeUv + vec2(0.0, SourceTexelSize.y))).rgb;
    vec3 n4 = texture(CurrColor, clampUv(safeUv - vec2(0.0, SourceTexelSize.y))).rgb;
    vec3 boxMin = min(n0, min(min(n1, n2), min(n3, n4)));
    vec3 boxMax = max(n0, max(max(n1, n2), max(n3, n4)));

    vec3 prevClamped = clamp(texture(PrevColor, clampUv(prevUv)).rgb, boxMin, boxMax);
    float smoothAlpha = clamp(BlendAlpha, 0.0, 0.25) * (1.0 - smoothstep(0.10, 0.35, edgeFactor));
    fragColor = vec4(mix(currRgb, prevClamped, smoothAlpha), 1.0);
}
