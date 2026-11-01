#import "Common/ShaderLib/GLSLCompat.glsllib"

uniform sampler2D m_FrondTexture;
uniform float m_AlphaDiscardThreshold;
varying vec3 vTexCoord;

void main() {
    vec2 uvDx = dFdx(vTexCoord.xy);
    vec2 uvDy = dFdy(vTexCoord.xy);
    if (vTexCoord.z > 0.5
            && textureGrad(m_FrondTexture, vTexCoord.xy, uvDx, uvDy).a <= m_AlphaDiscardThreshold) {
        discard;
    }
    gl_FragColor = vec4(1.0);
}
