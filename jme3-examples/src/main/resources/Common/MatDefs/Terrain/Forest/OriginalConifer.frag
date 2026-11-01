#import "Common/ShaderLib/GLSLCompat.glsllib"
#import "Common/ShaderLib/module/pbrlighting/DirectionalVisibility.glsllib"

uniform sampler2D m_FrondTexture;
uniform float m_AlphaDiscardThreshold;
uniform vec4 m_SunColor;
uniform vec4 m_AmbientColor;
uniform vec3 m_SunDirection;

varying vec3 vWorldPosition;
varying vec3 vWorldNormal;
varying vec3 vTexCoord;
varying vec4 vColor;

void main() {
    vec2 uvDx = dFdx(vTexCoord.xy);
    vec2 uvDy = dFdy(vTexCoord.xy);
    vec3 frond = vec3(1.0);
    if (vTexCoord.z > 0.5) {
        vec4 sampleColor = textureGrad(m_FrondTexture, vTexCoord.xy, uvDx, uvDy);
        if (sampleColor.a <= m_AlphaDiscardThreshold) discard;
        frond = sampleColor.rgb;
    }

    vec3 normal = normalize(vWorldNormal);
    float nDotSun = max(dot(normal, -normalize(m_SunDirection)), 0.0);
    float visibility = 1.0;
#ifdef DIRECTIONAL_VISIBILITY
    float viewDepth = -(g_ViewMatrix * vec4(vWorldPosition, 1.0)).z;
    visibility = DirectionalVisibilityForPosition(vWorldPosition, viewDepth);
#endif
    vec3 illumination = m_AmbientColor.rgb + m_SunColor.rgb * nDotSun * visibility;
    gl_FragColor = vec4(vColor.rgb * frond * illumination, 1.0);
}
