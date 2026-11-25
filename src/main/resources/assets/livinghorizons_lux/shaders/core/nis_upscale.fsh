#version 150

uniform sampler2D LowResSampler;
uniform vec2 SourceTexelSize;
uniform float Sharpness;

in vec2 texCoord0;
out vec4 fragColor;

const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);

vec2 safeUv(vec2 uv) {
    vec2 inset = max(SourceTexelSize * 0.55, vec2(0.00005));
    return clamp(uv, inset, vec2(1.0) - inset);
}

// Edge-adaptive directional spatial upscaling (NIS / FSR1 EASU-style) + RCAS sharpening
void main() {
    vec2 uv = safeUv(texCoord0);
    vec2 texSize = 1.0 / max(SourceTexelSize, vec2(1.0e-6));
    vec2 p = uv * texSize - 0.5;
    vec2 fp = fract(p);
    vec2 base = (floor(p) + 0.5) * SourceTexelSize;

    vec2 tx = vec2(SourceTexelSize.x, 0.0);
    vec2 ty = vec2(0.0, SourceTexelSize.y);

    vec3 c00 = texture(LowResSampler, safeUv(base)).rgb;
    vec3 c10 = texture(LowResSampler, safeUv(base + tx)).rgb;
    vec3 c01 = texture(LowResSampler, safeUv(base + ty)).rgb;
    vec3 c11 = texture(LowResSampler, safeUv(base + tx + ty)).rgb;

    vec3 lN = texture(LowResSampler, safeUv(base - ty)).rgb;
    vec3 lS = texture(LowResSampler, safeUv(base + ty * 2.0)).rgb;
    vec3 lW = texture(LowResSampler, safeUv(base - tx)).rgb;
    vec3 lE = texture(LowResSampler, safeUv(base + tx * 2.0)).rgb;

    float gH = abs(dot(c10 - lW, LUMA)) + abs(dot(lE - c00, LUMA));
    float gV = abs(dot(c01 - lN, LUMA)) + abs(dot(lS - c00, LUMA));
    float aniso = clamp((gV - gH) / max(gV + gH, 1.0e-4), -1.0, 1.0);

    // Directional edge-preserving interpolation weight
    vec2 w = fp;
    if (abs(aniso) > 0.20) {
        vec2 smoothW = smoothstep(vec2(0.0), vec2(1.0), fp);
        w = mix(fp, smoothW, abs(aniso) * 0.65);
    }

    vec3 upscaled = mix(mix(c00, c10, w.x), mix(c01, c11, w.x), w.y);

    if (Sharpness <= 0.001) {
        fragColor = vec4(upscaled, 1.0);
        return;
    }

    vec3 l = texture(LowResSampler, safeUv(uv - tx)).rgb;
    vec3 r = texture(LowResSampler, safeUv(uv + tx)).rgb;
    vec3 u = texture(LowResSampler, safeUv(uv - ty)).rgb;
    vec3 d = texture(LowResSampler, safeUv(uv + ty)).rgb;
    vec3 avg = (l + r + u + d) * 0.25;

    float lc = dot(upscaled, LUMA);
    float lMin = min(lc, min(min(dot(l, LUMA), dot(r, LUMA)), min(dot(u, LUMA), dot(d, LUMA))));
    float lMax = max(lc, max(max(dot(l, LUMA), dot(r, LUMA)), max(dot(u, LUMA), dot(d, LUMA))));
    float contrast = lMax - lMin;

    float gain = clamp(Sharpness, 0.0, 1.0) * mix(0.42, 1.10, smoothstep(0.015, 0.22, contrast)) * 0.72;
    vec3 sharpened = upscaled + (upscaled - avg) * gain;

    vec3 lo = min(upscaled, min(min(l, r), min(u, d)));
    vec3 hi = max(upscaled, max(max(l, r), max(u, d)));
    vec3 guard = vec3(0.012 + contrast * 0.035);
    fragColor = vec4(clamp(sharpened, lo - guard, hi + guard), 1.0);
}
