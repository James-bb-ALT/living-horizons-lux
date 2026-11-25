#version 150

uniform sampler2D SceneSampler;
uniform sampler2D DepthSampler;
uniform mat4 InvViewProj;
uniform vec3 CameraPos;
uniform vec3 SunDir;
uniform float TimeSec;
uniform float WaterLevel;
uniform float DaylightFactor;
uniform float RainLevel;
uniform float CausticStrength;
uniform int CameraUnderwater;

in vec2 texCoord0;
out vec4 fragColor;

float causticLayer(vec2 p, float t) {
    vec2 i = p;
    float c = 1.0;
    float inten = 0.055;
    for (int n = 0; n < 3; ++n) {
        float nt = t * (1.0 - (2.4 / float(n + 1)));
        i = p + vec2(cos(nt - i.x) + sin(nt + i.y), sin(nt - i.y) + cos(nt + i.x));
        c += 1.0 / max(length(vec2(p.x / (sin(i.x + nt) / inten), p.y / (cos(i.y + nt) / inten))), 0.001);
    }
    c /= 3.0;
    c = 1.15 - pow(clamp(c, 0.0, 2.0), 1.35);
    return pow(clamp(abs(c), 0.0, 1.0), 3.0);
}

void main() {
    vec4 scene = texture(SceneSampler, texCoord0);
    float depth = texture(DepthSampler, texCoord0).r;
    if (depth >= 0.99995 || DaylightFactor <= 0.03 || CausticStrength <= 0.001) {
        fragColor = scene;
        return;
    }

    vec4 clipPos = vec4(texCoord0 * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    vec4 worldH = InvViewProj * clipPos;
    if (abs(worldH.w) <= 1.0e-5) {
        fragColor = scene;
        return;
    }
    vec3 worldRelPos = worldH.xyz / worldH.w;
    float dist = length(worldRelPos);
    if (dist > 72.0) {
        fragColor = scene;
        return;
    }

    vec3 worldPos = CameraPos + worldRelPos;
    float waterDepth = WaterLevel - worldPos.y;
    if (waterDepth <= 0.02 || waterDepth >= 14.0) {
        fragColor = scene;
        return;
    }

    vec3 rawN = cross(dFdx(worldRelPos), dFdy(worldRelPos));
    float nLen = length(rawN);
    vec3 normal = nLen > 1.0e-6 ? (rawN / nLen) : vec3(0.0, 1.0, 0.0);
    if (dot(normal, -worldRelPos) < 0.0) {
        normal = -normal;
    }
    float upFacing = smoothstep(0.10, 0.65, normal.y);
    if (upFacing <= 0.01) {
        fragColor = scene;
        return;
    }

    // Dry-cave guard when viewing from above water: allow underwater sand/stone while rejecting dry caves
    float wetMask = (CameraUnderwater != 0)
            ? 1.0
            : smoothstep(-0.08, 0.03, scene.b - scene.r * 0.72);
    if (wetMask <= 0.01) {
        fragColor = scene;
        return;
    }

    float depthEnvelope = smoothstep(0.02, 0.45, waterDepth) * (1.0 - smoothstep(4.5, 14.0, waterDepth));
    float distFade = 1.0 - smoothstep(34.0, 70.0, dist);
    float mask = upFacing * wetMask * depthEnvelope * distFade;
    if (mask <= 0.005) {
        fragColor = scene;
        return;
    }

    vec3 sun = normalize(SunDir);
    vec2 projXZ = worldPos.xz - sun.xz * (waterDepth / max(abs(sun.y), 0.25));
    // Crisp 1/16-block pixel-aligned projection matching vanilla voxel aesthetic
    vec2 snappedXZ = (floor(projXZ * 16.0) + 0.5) / 16.0;
    vec2 uv = snappedXZ * 0.65;
    float pattern = causticLayer(uv, TimeSec * 0.85) * 0.7 + causticLayer(uv * 1.45 + 1.7, -TimeSec * 0.62) * 0.3;

    float strength = mask * DaylightFactor * (1.0 - RainLevel * 0.72) * CausticStrength;
    vec3 causticTint = vec3(0.68, 0.94, 1.0) * (pattern * strength * 1.35);
    fragColor = vec4(clamp(scene.rgb + causticTint, 0.0, 1.0), scene.a);
}
