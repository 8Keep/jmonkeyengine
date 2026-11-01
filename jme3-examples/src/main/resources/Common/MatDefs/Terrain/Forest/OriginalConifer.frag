#import "Common/ShaderLib/GLSLCompat.glsllib"
#import "Common/ShaderLib/PBR.glsllib"
#import "Common/ShaderLib/module/pbrlighting/DirectionalVisibility.glsllib"

uniform sampler2D m_FrondTexture;
uniform float m_AlphaDiscardThreshold;
uniform vec4 m_SunColor;
uniform vec4 m_AmbientColor;
uniform vec3 m_SunDirection;
#ifdef CONIFER_SKY_IRRADIANCE
uniform vec3 m_SkyIrradianceSH[9];
#endif
uniform bool g_IsFirstLightPass;

varying vec3 vWorldPosition;
varying vec3 vWorldNormal;
varying vec3 vTexCoord;
varying vec4 vColor;
varying vec3 vInstanceTint;

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
    vec3 baseColor = vColor.rgb * vInstanceTint;
    float nDotSun = max(dot(normal, -normalize(m_SunDirection)), 0.0);
    float visibility = 1.0;
#ifdef DIRECTIONAL_VISIBILITY
    float viewDepth = -(g_ViewMatrix * vec4(vWorldPosition, 1.0)).z;
    visibility = DirectionalVisibilityForPosition(vWorldPosition, viewDepth);
#endif
    vec3 indirectFill;
#ifdef CONIFER_SKY_IRRADIANCE
    indirectFill = g_IsFirstLightPass
            ? baseColor * frond * sphericalHarmonics(normal, m_SkyIrradianceSH) : vec3(0.0);
#else
    indirectFill = g_IsFirstLightPass
            ? baseColor * frond * m_AmbientColor.rgb : vec3(0.0);
#endif
    vec3 directSun = (m_SunColor.rgb / 3.14159265359) * nDotSun * visibility;
    if (!g_IsFirstLightPass) directSun = vec3(0.0);
    gl_FragColor = vec4(indirectFill + baseColor * frond * directSun, 1.0);
}
