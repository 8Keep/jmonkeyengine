#define ENABLE_PBRLightingUtils_getWorldPosition 1
#define ENABLE_PBRLightingUtils_getWorldNormal 1
#define ENABLE_PBRLightingUtils_computeDirectLightContribution 1
#import "Common/ShaderLib/GLSLCompat.glsllib"
#import "Common/ShaderLib/module/pbrlighting/PBRLightingUtils.glsllib"

uniform vec4 g_LightData[NB_LIGHTS];
uniform int g_LightCount;
uniform vec3 g_CameraPosition;

uniform vec4 m_GrassColor;
uniform vec4 m_RockColor;
uniform vec4 m_SnowColor;
uniform float m_Roughness;
uniform float m_TextureScale;
uniform float m_SnowLine;
uniform float m_SnowFade;
uniform vec4 g_AmbientLightColor;

float hash2(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

float valueNoise(vec2 p) {
    vec2 cell = floor(p);
    vec2 blend = fract(p);
    blend = blend * blend * (3.0 - 2.0 * blend);
    float a = hash2(cell);
    float b = hash2(cell + vec2(1.0, 0.0));
    float c = hash2(cell + vec2(0.0, 1.0));
    float d = hash2(cell + vec2(1.0, 1.0));
    return mix(mix(a, b, blend.x), mix(c, d, blend.x), blend.y);
}

void main() {
    vec3 worldPosition = PBRLightingUtils_getWorldPosition();
    vec3 normal = normalize(PBRLightingUtils_getWorldNormal());
    vec2 worldXZ = worldPosition.xz * max(m_TextureScale, 0.00001);

    float broadBreakup = valueNoise(worldXZ * 0.34);
    float fineBreakup = valueNoise(worldXZ * 2.7);
    float breakup = (broadBreakup - 0.5) * 0.22 + (fineBreakup - 0.5) * 0.07;

    float steepness = 1.0 - clamp(normal.y, 0.0, 1.0);
    float rockWeight = smoothstep(0.22, 0.68, steepness + breakup);
    vec3 groundColor = mix(m_GrassColor.rgb, m_RockColor.rgb, rockWeight);
    groundColor *= 0.94 + fineBreakup * 0.12;

    float snowFade = max(m_SnowFade, 0.0001);
    float snowWeight = smoothstep(m_SnowLine - snowFade, m_SnowLine + snowFade,
                                  worldPosition.y + breakup * snowFade * 0.4);
    snowWeight *= 1.0 - smoothstep(0.58, 0.88, steepness);

    PBRSurface surface = PBRLightingUtils_createPBRSurface(
        normalize(g_CameraPosition - worldPosition));
    surface.position = worldPosition;
    surface.geometryNormal = normal;
    surface.normal = normal;
    surface.albedo = mix(groundColor, m_SnowColor.rgb, snowWeight);
    surface.roughness = clamp(m_Roughness, 0.04, 1.0);
    surface.metallic = 0.0;

    PBRLightingUtils_calculatePreLightingValues(surface);

    for (int i = 0; i < NB_LIGHTS; i += 3) {
        #if (defined(GL_ES) && __VERSION__ >= 300) || (!defined(GL_ES) && __VERSION__ >= 150)
        if (i >= g_LightCount * 3) {
            break;
        }
        #endif
        PBRLightingUtils_computeDirectLightContribution(
            g_LightData[i], g_LightData[i + 1], g_LightData[i + 2], surface);
    }

    // Artistic hemisphere fill from the scene's AmbientLights. The lighting
    // logic supplies zero on additive light batches, so fill is applied once.
    // This is separate from probe/physical sky lighting.
    vec3 skyFill = max(g_AmbientLightColor.rgb, vec3(0.0));
    float skyFacing = 0.32 + 0.68 * clamp(normal.y * 0.5 + 0.5, 0.0, 1.0);
    vec3 indirectFill = surface.diffuseColor * skyFill * skyFacing;

    gl_FragColor = vec4(surface.directLightContribution + indirectFill, 1.0);
}
