#version 150

uniform sampler2D SceneSampler;
uniform sampler2D DepthSampler;
uniform vec2 SourceTexelSize;

in vec2 texCoord0;
out vec4 fragColor;

void main() {
    vec3 color = texture(SceneSampler, texCoord0).rgb;
    float depth = texture(DepthSampler, texCoord0).r;
    float grad = abs(dFdx(depth)) + abs(dFdy(depth));
    float edgeMask = smoothstep(0.00030, 0.0055, grad);
    fragColor = vec4(clamp(color, 0.0, 1.0), edgeMask);
}
