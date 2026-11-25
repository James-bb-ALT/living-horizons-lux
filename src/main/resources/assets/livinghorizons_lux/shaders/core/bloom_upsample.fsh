#version 150

uniform sampler2D LowSampler;
uniform sampler2D CurrentSampler;
uniform vec2 LowTexelSize;
uniform float Radius;

in vec2 texCoord0;
out vec4 fragColor;

void main() {
    vec2 d = LowTexelSize * Radius;
    vec3 up = texture(LowSampler, texCoord0 + vec2(-d.x * 2.0, 0.0)).rgb;
    up += texture(LowSampler, texCoord0 + vec2(-d.x, d.y)).rgb * 2.0;
    up += texture(LowSampler, texCoord0 + vec2(0.0, d.y * 2.0)).rgb;
    up += texture(LowSampler, texCoord0 + vec2(d.x, d.y)).rgb * 2.0;
    up += texture(LowSampler, texCoord0 + vec2(d.x * 2.0, 0.0)).rgb;
    up += texture(LowSampler, texCoord0 + vec2(d.x, -d.y)).rgb * 2.0;
    up += texture(LowSampler, texCoord0 + vec2(0.0, -d.y * 2.0)).rgb;
    up += texture(LowSampler, texCoord0 + vec2(-d.x, -d.y)).rgb * 2.0;
    up /= 12.0;

    vec3 curr = texture(CurrentSampler, texCoord0).rgb;
    fragColor = vec4(curr + up * 0.65, 1.0);
}
