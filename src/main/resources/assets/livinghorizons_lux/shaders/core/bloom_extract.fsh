#version 150

uniform sampler2D SceneSampler;
uniform sampler2D DepthSampler;
uniform float Threshold;
uniform float MaxDistance;
uniform float NearPlane;
uniform float FarPlane;

in vec2 texCoord0;
out vec4 fragColor;

float linearizeDepth(float d) {
    float ndc = d * 2.0 - 1.0;
    return (2.0 * NearPlane * FarPlane) / max(FarPlane + NearPlane - ndc * (FarPlane - NearPlane), 1.0e-5);
}

void main() {
    vec3 color = texture(SceneSampler, texCoord0).rgb;
    float depth = texture(DepthSampler, texCoord0).r;

    // Exclude sky/far Distant Horizons terrain beyond MaxDistance so distant horizon stays crisp
    if (depth >= 0.99995) {
        fragColor = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }
    float dist = linearizeDepth(depth);
    float distMask = 1.0 - smoothstep(MaxDistance * 0.80, MaxDistance, dist);
    if (distMask <= 0.001) {
        fragColor = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }

    float luma = dot(color, vec3(0.2126, 0.7152, 0.0722));
    float maxC = max(color.r, max(color.g, color.b));
    float minC = min(color.r, min(color.g, color.b));
    float saturation = (maxC - minC) / max(maxC, 1.0e-4);

    // Shine-inspired selective metric: emphasizes saturated glowing torches/portals & bright highlights
    float metric = max(luma, maxC * (0.82 + 0.45 * saturation));
    float knee = 0.22;
    float soft = clamp(metric - (Threshold - knee), 0.0, 2.0 * knee);
    soft = (soft * soft) / (4.0 * knee + 1.0e-5);
    float contribution = max(metric - Threshold, soft) / max(metric, 1.0e-4);

    fragColor = vec4(color * clamp(contribution, 0.0, 1.8) * distMask, 1.0);
}
