#import "Common/ShaderLib/GLSLCompat.glsllib"
#import "Common/ShaderLib/PBR.glsllib"
#import "Common/ShaderLib/module/pbrlighting/DirectionalVisibility.glsllib"
#import "Common/MatDefs/Terrain/Forest/ConiferImpostorSample.glsllib"
uniform vec4 m_SunColor;
uniform vec4 m_AmbientColor;
uniform vec3 m_SunDirection;
uniform bool g_IsFirstLightPass;
#ifdef CONIFER_SKY_IRRADIANCE
uniform vec3 m_SkyIrradianceSH[9];
#endif
void main() {
    vec3 albedo, normal, worldPosition;
    ConiferReconstruct(albedo, normal, worldPosition);
    float visibility = 1.0;
#ifdef DIRECTIONAL_VISIBILITY
    float viewDepth = -(g_ViewMatrix * vec4(worldPosition, 1.0)).z;
    visibility = DirectionalVisibilityForPosition(worldPosition, viewDepth);
#endif
    vec3 fill;
#ifdef CONIFER_SKY_IRRADIANCE
    fill = sphericalHarmonics(normal, m_SkyIrradianceSH);
#else
    fill = m_AmbientColor.rgb;
#endif
    vec3 direct = (m_SunColor.rgb / 3.14159265359) * max(dot(normal, -normalize(m_SunDirection)), 0.0) * visibility;
    gl_FragColor = vec4(g_IsFirstLightPass ? albedo * vYawTint.z * (fill + direct) : vec3(0.0), 1.0);
}
