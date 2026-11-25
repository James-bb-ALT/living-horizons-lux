#version 150

uniform sampler2D SourceSampler;
uniform vec2 TexelSize;

in vec2 texCoord0;
out vec4 fragColor;

void main() {
    vec2 h = TexelSize * 0.5;
    vec3 acc = texture(SourceSampler, texCoord0).rgb * 4.0;
    acc += texture(SourceSampler, texCoord0 - h).rgb;
    acc += texture(SourceSampler, texCoord0 + h).rgb;
    acc += texture(SourceSampler, texCoord0 + vec2(h.x, -h.y)).rgb;
    acc += texture(SourceSampler, texCoord0 + vec2(-h.x, h.y)).rgb;
    fragColor = vec4(acc / 8.0, 1.0);
}
