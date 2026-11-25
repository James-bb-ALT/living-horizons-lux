#version 150

const int MAX_LIGHTS = 48;

uniform sampler2D SceneSampler;
uniform sampler2D DepthSampler;
uniform mat4 InvViewProj;
uniform int LightCount;
uniform vec4 LightPosRadius[MAX_LIGHTS];
uniform vec4 LightColorIntensity[MAX_LIGHTS];
uniform float GlobalIntensity;
uniform float Saturation;
uniform float DaylightFactor;
uniform float DaytimeBoost;

in vec2 texCoord0;
out vec4 fragColor;

void main() {
    vec4 scene = texture(SceneSampler, texCoord0);
    if (LightCount <= 0) {
        fragColor = scene;
        return;
    }

    float depth = texture(DepthSampler, texCoord0).r;
    if (depth >= 0.99995) {
        fragColor = scene;
        return;
    }

    vec4 clipPos = vec4(texCoord0 * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    vec4 worldH = InvViewProj * clipPos;
    if (abs(worldH.w) <= 1.0e-5) {
        fragColor = scene;
        return;
    }
    vec3 worldPos = worldH.xyz / worldH.w;

    // Reconstruct geometric surface normal facing toward camera
    vec3 rawN = cross(dFdx(worldPos), dFdy(worldPos));
    float nLen = length(rawN);
    vec3 normal = nLen > 1.0e-6 ? (rawN / nLen) : normalize(-worldPos);
    if (dot(normal, -worldPos) < 0.0) {
        normal = -normal;
    }

    vec3 lightAccum = vec3(0.0);
    int count = min(LightCount, MAX_LIGHTS);
    for (int i = 0; i < count; ++i) {
        vec4 pr = LightPosRadius[i];
        vec3 toLight = pr.xyz - worldPos;
        float distSq = dot(toLight, toLight);
        float radius = pr.w;
        float rSq = radius * radius;
        if (distSq >= rSq) {
            continue;
        }
        float d = sqrt(distSq);
        float falloff = clamp(1.0 - d / radius, 0.0, 1.0);
        float att = (falloff * falloff) / (1.0 + 0.16 * distSq);
        float ndotl = clamp(dot(normal, toLight / max(d, 0.08)) * 0.45 + 0.55, 0.25, 1.0);
        vec4 ci = LightColorIntensity[i];
        lightAccum += ci.rgb * (ci.w * att * ndotl);
    }

    float accumPeak = max(lightAccum.r, max(lightAccum.g, lightAccum.b));
    if (accumPeak <= 1.0e-4) {
        fragColor = scene;
        return;
    }

    vec3 lightHue = lightAccum / max(accumPeak, 1.0e-4);
    float lightLuma = dot(lightHue, vec3(0.2126, 0.7152, 0.0722));
    vec3 satHue = max(vec3(0.0), mix(vec3(lightLuma), lightHue, clamp(Saturation, 0.0, 1.5)));

    float dayScale = mix(1.0, 0.35 + 0.65 * clamp(DaytimeBoost, 0.0, 1.0), clamp(DaylightFactor, 0.0, 1.0));
    float strength = accumPeak * GlobalIntensity * dayScale;

    float tintAmt = clamp(strength * 1.15, 0.0, 0.82);
    vec3 lit = mix(scene.rgb, scene.rgb * satHue * 1.25, tintAmt) + satHue * (strength * 0.24);
    lit = min(lit, 0.94 + (lit - 0.94) / (1.0 + max(lit - 0.94, vec3(0.0)) * 1.8));
    fragColor = vec4(clamp(lit, 0.0, 1.0), scene.a);
}
