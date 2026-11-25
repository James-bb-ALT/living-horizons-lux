#version 150

uniform sampler2D HistoryDepth;
uniform mat4 ReprojectionMat;
uniform mat4 SkyReprojectionMat;

in vec2 texCoord0;
out vec4 fragColor;

void main() {
    float depth = texture(HistoryDepth, texCoord0).r;
    vec4 historyClip = vec4(texCoord0 * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);

    // Distant Horizons distant LOD terrain sits near far plane (depth >= 0.99995) alongside the sky:
    // using rotation-only SkyReprojectionMat prevents parallax smearing on distant mountains and clouds.
    vec4 currClip = (depth >= 0.99995)
            ? (SkyReprojectionMat * historyClip)
            : (ReprojectionMat * historyClip);

    float valid = 1.0;
    vec2 delta = vec2(0.0);
    if (currClip.w <= 1.0e-5) {
        valid = 0.0;
    } else {
        vec3 ndc = currClip.xyz / currClip.w;
        vec2 destUv = ndc.xy * 0.5 + 0.5;
        float destDepth = ndc.z * 0.5 + 0.5;
        delta = destUv - texCoord0;
        bool inBounds = destUv.x >= -0.02 && destUv.x <= 1.02
                     && destUv.y >= -0.02 && destUv.y <= 1.02
                     && destDepth >= 0.0 && destDepth <= 1.0
                     && length(delta) <= 0.35;
        valid = inBounds ? 1.0 : 0.0;
    }

    float depthGrad = abs(dFdx(depth)) + abs(dFdy(depth));
    float depthEdge = smoothstep(0.00035, 0.0060, depthGrad);
    fragColor = vec4(delta, valid, depthEdge);
}
