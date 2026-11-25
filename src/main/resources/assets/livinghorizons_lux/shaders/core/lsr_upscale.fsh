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

void main() {
    vec2 uv = safeUv(texCoord0);
    vec4 center = texture(LowResSampler, uv);
    if (Sharpness <= 0.001) {
        fragColor = center;
        return;
    }

    vec2 t = SourceTexelSize;
    vec3 l = texture(LowResSampler, safeUv(uv - vec2(t.x, 0.0))).rgb;
    vec3 r = texture(LowResSampler, safeUv(uv + vec2(t.x, 0.0))).rgb;
    vec3 u = texture(LowResSampler, safeUv(uv - vec2(0.0, t.y))).rgb;
    vec3 d = texture(LowResSampler, safeUv(uv + vec2(0.0, t.y))).rgb;
    vec3 avg = (l + r + u + d) * 0.25;

    float lc = dot(center.rgb, LUMA);
    float lMin = min(lc, min(min(dot(l, LUMA), dot(r, LUMA)), min(dot(u, LUMA), dot(d, LUMA))));
    float lMax = max(lc, max(max(dot(l, LUMA), dot(r, LUMA)), max(dot(u, LUMA), dot(d, LUMA))));
    float contrast = lMax - lMin;

    float gain = clamp(Sharpness, 0.0, 1.0) * mix(0.30, 0.95, smoothstep(0.02, 0.20, contrast)) * 0.65;
    vec3 sharpened = center.rgb + (center.rgb - avg) * gain;

    vec3 lo = min(center.rgb, min(min(l, r), min(u, d)));
    vec3 hi = max(center.rgb, max(max(l, r), max(u, d)));
    vec3 guard = vec3(0.015 + contrast * 0.04);
    fragColor = vec4(clamp(sharpened, lo - guard, hi + guard), center.a);
}
