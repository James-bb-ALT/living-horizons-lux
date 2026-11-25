#version 430 core

layout(local_size_x = 16, local_size_y = 16) in;

layout(rgba8, binding = 0) uniform writeonly image2D OutImage;

layout(std430, binding = 0) readonly buffer LightBuffer {
    vec4 posRadius[256];
    vec4 colorIntensity[256];
};

uniform sampler2D SceneSampler;
uniform sampler2D DepthSampler;
uniform mat4 InvViewProj;
uniform ivec2 ImageSize;
uniform int LightCount;
uniform float GlobalIntensity;
uniform float Saturation;
uniform float DaylightFactor;
uniform float DaytimeBoost;

shared ivec3 sTileMinQ;
shared ivec3 sTileMaxQ;
shared uint sTileLightCount;
shared uint sTileLightIdx[64];

vec3 reconstructWorldPos(ivec2 px, float depth) {
    vec2 uv = (vec2(px) + 0.5) / vec2(ImageSize);
    vec4 clipPos = vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    vec4 worldH = InvViewProj * clipPos;
    return abs(worldH.w) > 1.0e-5 ? (worldH.xyz / worldH.w) : vec3(0.0);
}

void main() {
    if (gl_LocalInvocationIndex == 0u) {
        sTileMinQ = ivec3(0x7FFFFFFF);
        sTileMaxQ = ivec3(-0x7FFFFFFF);
        sTileLightCount = 0u;
    }
    memoryBarrierShared();
    barrier();

    ivec2 px = ivec2(gl_GlobalInvocationID.xy);
    bool inBounds = px.x < ImageSize.x && px.y < ImageSize.y;
    vec4 scene = vec4(0.0);
    float depth = 1.0;
    vec3 worldPos = vec3(0.0);
    bool isSky = true;

    if (inBounds) {
        scene = texelFetch(SceneSampler, px, 0);
        depth = texelFetch(DepthSampler, px, 0).r;
        isSky = depth >= 0.99995;
        if (!isSky) {
            worldPos = reconstructWorldPos(px, depth);
            ivec3 q = ivec3(clamp(worldPos * 64.0, vec3(-1.0e8), vec3(1.0e8)));
            atomicMin(sTileMinQ.x, q.x);
            atomicMin(sTileMinQ.y, q.y);
            atomicMin(sTileMinQ.z, q.z);
            atomicMax(sTileMaxQ.x, q.x);
            atomicMax(sTileMaxQ.y, q.y);
            atomicMax(sTileMaxQ.z, q.z);
        }
    }
    memoryBarrierShared();
    barrier();

    bool tileHasGeometry = sTileMinQ.x <= sTileMaxQ.x;
    if (tileHasGeometry) {
        vec3 tileMin = vec3(sTileMinQ) / 64.0 - vec3(0.05);
        vec3 tileMax = vec3(sTileMaxQ) / 64.0 + vec3(0.05);
        uint count = uint(clamp(LightCount, 0, 256));
        for (uint i = gl_LocalInvocationIndex; i < count; i += 256u) {
            vec4 pr = posRadius[i];
            vec3 closest = clamp(pr.xyz, tileMin, tileMax);
            vec3 diff = pr.xyz - closest;
            if (dot(diff, diff) <= pr.w * pr.w) {
                uint slot = atomicAdd(sTileLightCount, 1u);
                if (slot < 64u) {
                    sTileLightIdx[slot] = i;
                }
            }
        }
    }
    memoryBarrierShared();
    barrier();

    if (!inBounds) {
        return;
    }
    if (isSky || !tileHasGeometry || sTileLightCount == 0u) {
        imageStore(OutImage, px, scene);
        return;
    }

    ivec2 pxR = min(px + ivec2(1, 0), ImageSize - 1);
    ivec2 pxU = min(px + ivec2(0, 1), ImageSize - 1);
    float dR = texelFetch(DepthSampler, pxR, 0).r;
    float dU = texelFetch(DepthSampler, pxU, 0).r;
    vec3 pR = (dR < 0.99995) ? reconstructWorldPos(pxR, dR) : worldPos;
    vec3 pU = (dU < 0.99995) ? reconstructWorldPos(pxU, dU) : worldPos;
    vec3 rawN = cross(pR - worldPos, pU - worldPos);
    float nLen = length(rawN);
    vec3 normal = nLen > 1.0e-6 ? (rawN / nLen) : normalize(-worldPos);
    if (dot(normal, -worldPos) < 0.0) {
        normal = -normal;
    }

    vec3 lightAccum = vec3(0.0);
    uint visibleCount = min(sTileLightCount, 64u);
    for (uint j = 0u; j < visibleCount; ++j) {
        uint idx = sTileLightIdx[j];
        vec4 pr = posRadius[idx];
        vec3 toLight = pr.xyz - worldPos;
        float distSq = dot(toLight, toLight);
        float radius = pr.w;
        if (distSq >= radius * radius) {
            continue;
        }
        float d = sqrt(distSq);
        float falloff = clamp(1.0 - d / radius, 0.0, 1.0);
        float att = (falloff * falloff) / (1.0 + 0.16 * distSq);
        float ndotl = clamp(dot(normal, toLight / max(d, 0.08)) * 0.45 + 0.55, 0.25, 1.0);
        vec4 ci = colorIntensity[idx];
        lightAccum += ci.rgb * (ci.w * att * ndotl);
    }

    float accumPeak = max(lightAccum.r, max(lightAccum.g, lightAccum.b));
    if (accumPeak <= 1.0e-4) {
        imageStore(OutImage, px, scene);
        return;
    }

    vec3 lightHue = lightAccum / max(accumPeak, 1.0e-4);
    float lightLuma = dot(lightHue, vec3(0.2126, 0.7152, 0.0722));
    vec3 satHue = max(vec3(0.0), mix(vec3(lightLuma), lightHue, clamp(Saturation, 0.0, 1.5)));

    float dayScale = mix(1.0, 0.35 + 0.65 * clamp(DaytimeBoost, 0.0, 1.0), clamp(DaylightFactor, 0.0, 1.0));
    float strength = accumPeak * GlobalIntensity * dayScale;

    // Hue-preserving albedo tinting + smooth radial glow so colored torches & handheld lights read clearly even in daylight
    float tintAmt = clamp(strength * 1.15, 0.0, 0.82);
    vec3 lit = mix(scene.rgb, scene.rgb * satHue * 1.25, tintAmt) + satHue * (strength * 0.24);
    lit = min(lit, 0.94 + (lit - 0.94) / (1.0 + max(lit - 0.94, vec3(0.0)) * 1.8));
    imageStore(OutImage, px, vec4(clamp(lit, 0.0, 1.0), scene.a));
}
