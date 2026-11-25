#version 150

uniform sampler2D LowResColor;
uniform sampler2D LowResDepth;
uniform sampler2D HistoryColor;
uniform mat4 CurrToPrevMat;
uniform mat4 CurrToPrevSkyMat;
uniform vec2 JitterUv;
uniform vec2 LowResTexelSize;
uniform vec2 NativeTexelSize;
uniform float Sharpness;
uniform int HistoryValid;

in vec2 texCoord0;
out vec4 fragColor;

vec2 clampLowUv(vec2 uv) {
    vec2 inset = max(LowResTexelSize * 0.55, vec2(0.0001));
    return clamp(uv, inset, vec2(1.0) - inset);
}

vec2 clampNativeUv(vec2 uv) {
    vec2 inset = max(NativeTexelSize * 0.55, vec2(0.0001));
    return clamp(uv, inset, vec2(1.0) - inset);
}

vec3 rgbToYCoCg(vec3 c) {
    float co = c.r - c.b;
    float tmp = c.b + co * 0.5;
    float cg = c.g - tmp;
    float y = tmp + cg * 0.5;
    return vec3(y, co, cg);
}

vec3 yCoCgToRgb(vec3 ycc) {
    float tmp = ycc.x - ycc.z * 0.5;
    float g = ycc.z + tmp;
    float b = tmp - ycc.y * 0.5;
    float r = b + ycc.y;
    return clamp(vec3(r, g, b), 0.0, 1.0);
}

// Crisp 5-tap Catmull-Rom history sample to prevent temporal blurring
vec3 sampleHistoryCatmullRom(vec2 uv) {
    vec2 texSize = vec2(textureSize(HistoryColor, 0));
    vec2 samplePos = uv * texSize;
    vec2 tc1 = floor(samplePos - 0.5) + 0.5;
    vec2 f = samplePos - tc1;
    vec2 f2 = f * f;
    vec2 f3 = f2 * f;

    vec2 w0 = f2 - 0.5 * (f3 + f);
    vec2 w1 = 1.5 * f3 - 2.5 * f2 + 1.0;
    vec2 w3 = 0.5 * (f3 - f2);
    vec2 w2 = 1.0 - w0 - w1 - w3;

    vec2 w12 = w1 + w2;
    vec2 tc0 = (tc1 - 1.0) / texSize;
    vec2 tc3 = (tc1 + 2.0) / texSize;
    vec2 tc12 = (tc1 + w2 / max(w12, vec2(1.0e-5))) / texSize;

    vec3 res = texture(HistoryColor, clampNativeUv(vec2(tc12.x, tc0.y))).rgb * (w12.x * w0.y)
             + texture(HistoryColor, clampNativeUv(vec2(tc0.x, tc12.y))).rgb * (w0.x * w12.y)
             + texture(HistoryColor, clampNativeUv(vec2(tc12.x, tc12.y))).rgb * (w12.x * w12.y)
             + texture(HistoryColor, clampNativeUv(vec2(tc3.x, tc12.y))).rgb * (w3.x * w12.y)
             + texture(HistoryColor, clampNativeUv(vec2(tc12.x, tc3.y))).rgb * (w12.x * w3.y);
    float totalW = (w12.x * w0.y) + (w0.x * w12.y) + (w12.x * w12.y) + (w3.x * w12.y) + (w12.x * w3.y);
    return clamp(res / max(totalW, 1.0e-4), 0.0, 1.0);
}

void main() {
    vec2 unjitteredUv = clampLowUv(texCoord0 + JitterUv);

    // Sample 3x3 neighborhood in YCoCg space for variance clipping + closest depth lookup
    vec3 m1 = vec3(0.0);
    vec3 m2 = vec3(0.0);
    vec3 centerYcc = vec3(0.0);
    float minDepth = 1.0;
    vec2 bestOffset = vec2(0.0);

    for (int oy = -1; oy <= 1; ++oy) {
        for (int ox = -1; ox <= 1; ++ox) {
            vec2 off = vec2(float(ox), float(oy)) * LowResTexelSize;
            vec2 suv = clampLowUv(unjitteredUv + off);
            vec3 c = rgbToYCoCg(texture(LowResColor, suv).rgb);
            if (ox == 0 && oy == 0) {
                centerYcc = c;
            }
            m1 += c;
            m2 += c * c;

            float d = texture(LowResDepth, suv).r;
            if (d < minDepth) {
                minDepth = d;
                bestOffset = off;
            }
        }
    }

    vec3 currCenterRgb = yCoCgToRgb(centerYcc);
    if (HistoryValid == 0) {
        fragColor = vec4(currCenterRgb, 1.0);
        return;
    }

    vec2 depthUv = clampLowUv(texCoord0 + bestOffset);
    vec4 currClip = vec4(depthUv * 2.0 - 1.0, minDepth * 2.0 - 1.0, 1.0);
    vec4 prevClip = (minDepth >= 0.99995)
            ? (CurrToPrevSkyMat * currClip)
            : (CurrToPrevMat * currClip);

    if (prevClip.w <= 1.0e-5) {
        fragColor = vec4(currCenterRgb, 1.0);
        return;
    }

    vec3 prevNdc = prevClip.xyz / prevClip.w;
    vec2 prevUv = prevNdc.xy * 0.5 + 0.5 + (depthUv - texCoord0);
    if (prevUv.x < 0.0 || prevUv.x > 1.0 || prevUv.y < 0.0 || prevUv.y > 1.0) {
        fragColor = vec4(currCenterRgb, 1.0);
        return;
    }

    vec3 histYcc = rgbToYCoCg(sampleHistoryCatmullRom(prevUv));
    m1 /= 9.0;
    m2 /= 9.0;
    vec3 sigma = sqrt(max(vec3(0.0), m2 - m1 * m1));
    float clipScale = 1.25;
    vec3 boxMin = m1 - sigma * clipScale;
    vec3 boxMax = m1 + sigma * clipScale;
    vec3 clippedHistYcc = clamp(histYcc, boxMin, boxMax);

    float velPixels = length((prevUv - texCoord0) / max(NativeTexelSize, vec2(1.0e-5)));
    float blendAlpha = clamp(0.12 + velPixels * 0.025, 0.12, 0.42);
    vec3 resolvedYcc = mix(clippedHistYcc, centerYcc, blendAlpha);

    fragColor = vec4(yCoCgToRgb(resolvedYcc), 1.0);
}
