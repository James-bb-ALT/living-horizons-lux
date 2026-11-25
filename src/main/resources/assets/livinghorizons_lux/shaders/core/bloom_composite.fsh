#version 150

uniform sampler2D SceneSampler;
uniform sampler2D BloomSampler;
uniform sampler2D DepthSampler;
uniform vec2 SceneTexelSize;
uniform float BloomStrength;
uniform int RimEnabled;
uniform float RimStrength;
uniform float DaylightFactor;

in vec2 texCoord0;
out vec4 fragColor;

void main() {
    vec4 scene = texture(SceneSampler, texCoord0);
    vec3 bloom = texture(BloomSampler, texCoord0).rgb * (BloomStrength * 1.15);

    vec3 outColor = scene.rgb + bloom;

    if (RimEnabled != 0 && RimStrength > 0.001) {
        float dC = texture(DepthSampler, texCoord0).r;
        if (dC < 0.9999) {
            float dR = texture(DepthSampler, texCoord0 + vec2(SceneTexelSize.x, 0.0)).r;
            float dU = texture(DepthSampler, texCoord0 + vec2(0.0, SceneTexelSize.y)).r;
            float edge = clamp((abs(dR - dC) + abs(dU - dC)) * 220.0, 0.0, 1.0);
            float rimMask = smoothstep(0.25, 0.9, edge) * (0.25 + 0.75 * DaylightFactor);
            vec3 rimTint = mix(vec3(0.55, 0.72, 1.0), vec3(1.0, 0.92, 0.78), DaylightFactor);
            outColor += rimTint * (rimMask * RimStrength * 0.25);
        }
    }

    // Soft shoulder roll-off above 0.95 so bloom adds vivid glow without crushing bright highlights
    outColor = min(outColor, 0.95 + (outColor - 0.95) / (1.0 + max(outColor - 0.95, vec3(0.0)) * 1.5));
    fragColor = vec4(clamp(outColor, 0.0, 1.0), scene.a);
}
