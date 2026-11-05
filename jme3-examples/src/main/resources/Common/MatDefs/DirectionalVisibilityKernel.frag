#import "Common/ShaderLib/GLSLCompat.glsllib"
#import "Common/ShaderLib/module/pbrlighting/DirectionalVisibility.glsllib"

uniform vec2 m_ProbeOutputSize;

void main() {
    vec2 uv = gl_FragCoord.xy / m_ProbeOutputSize;
    float visibility = DirectionalVisibilitySamplePcf5x5(
            m_DirectionalShadowMap0, uv, 0.5);
    gl_FragColor = vec4(visibility, visibility, visibility, 1.0);
}
