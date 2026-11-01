#define ENABLE_PBRLightingUtils_getWorldPosition 1
#define ENABLE_PBRLightingUtils_getWorldNormal 1
#define ENABLE_PBRLightingUtils_computeDirectLightContribution 1
#import "Common/ShaderLib/GLSLCompat.glsllib"
#import "Common/ShaderLib/module/pbrlighting/PBRLightingUtils.glsllib"

// Preserve the legacy GLSL 1.10 path even if the material flag is set.
#if defined(DIRECTIONAL_VISIBILITY) && ((!defined(GL_ES) && __VERSION__ >= 150) || (defined(GL_ES) && __VERSION__ >= 300))
    #import "Common/ShaderLib/module/pbrlighting/DirectionalVisibility.glsllib"
    #define DIRECTIONAL_VISIBILITY_ENABLED 1
#endif

#ifdef HEIGHTFIELD_SURFACE
    #import "Common/MatDefs/Terrain/Heightfield/HeightfieldSurfaceSampling.glsllib"
    uniform float m_SurfaceFixedLod;
    #ifdef SURFACE_REGION_FALLBACK
        uniform float m_SurfaceRegionFade;
    #endif
#endif

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
#ifdef HEIGHTFIELD_SKY_IRRADIANCE
uniform bool g_IsFirstLightPass;
uniform vec3 m_SkyIrradianceSH[9];
#endif

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

void proceduralMaterial(vec3 worldPosition, vec3 normal, out vec3 albedo, out float roughness) {
    vec2 worldXZ = worldPosition.xz * max(m_TextureScale, 0.00001);
    float broadBreakup = valueNoise(worldXZ * 0.34);
    float fineBreakup = valueNoise(worldXZ * 2.7);
    float breakup = (broadBreakup - 0.5) * 0.22 + (fineBreakup - 0.5) * 0.07;
    float steepness = 1.0 - clamp(normal.y, 0.0, 1.0);
    #ifdef SURFACE_REGION_FALLBACK
        // Match the regional source's slope convention (rise/run). Using
        // 1-normal.y here leaves steep far slopes almost entirely snow covered.
        float slope = sqrt(max(1.0 - normal.y * normal.y, 0.0)) / max(abs(normal.y), 0.0001);
        float rockWeight = smoothstep(0.48, 1.30, slope + breakup);
    #else
        float rockWeight = smoothstep(0.22, 0.68, steepness + breakup);
    #endif
    vec3 groundColor = mix(m_GrassColor.rgb, m_RockColor.rgb, rockWeight);
    groundColor *= 0.94 + fineBreakup * 0.12;
    float snowFade = max(m_SnowFade, 0.0001);
    float snowWeight = smoothstep(m_SnowLine - snowFade, m_SnowLine + snowFade,
                                  worldPosition.y + breakup * snowFade * 0.4);
    #ifdef SURFACE_REGION_FALLBACK
        snowWeight *= 1.0 - smoothstep(0.65, 1.40, slope);
    #else
        snowWeight *= 1.0 - smoothstep(0.58, 0.88, steepness);
    #endif
    albedo = mix(groundColor, m_SnowColor.rgb, snowWeight);
    roughness = clamp(m_Roughness, 0.04, 1.0);
}

void main() {
    vec3 worldPosition = PBRLightingUtils_getWorldPosition();
    vec3 normal = normalize(PBRLightingUtils_getWorldNormal());
    PBRSurface surface = PBRLightingUtils_createPBRSurface(
        normalize(g_CameraPosition - worldPosition));
    surface.position = worldPosition;
    surface.geometryNormal = normal;
    surface.normal = normal;
    surface.metallic = 0.0;

    #ifdef HEIGHTFIELD_SURFACE
        // Compute derivatives before dependent page-table lookup.
        vec2 dxWorld = dFdx(worldPosition.xz);
        vec2 dyWorld = dFdy(worldPosition.xz);
        #ifdef SURFACE_REGION_FALLBACK
            vec2 regionPosition = worldPosition.xz - m_SurfaceRegionOrigin;
            float edgeDistance = min(min(regionPosition.x, regionPosition.y),
                    min(m_SurfaceRegionSize - regionPosition.x,
                            m_SurfaceRegionSize - regionPosition.y));
            float cacheWeight = smoothstep(0.0, max(m_SurfaceRegionFade, 0.0001), edgeDistance);
            vec3 fallbackAlbedo = vec3(0.0);
            float fallbackRoughness = 0.0;
            if (cacheWeight < 1.0) {
                proceduralMaterial(worldPosition, normal, fallbackAlbedo, fallbackRoughness);
            }
            surface.albedo = fallbackAlbedo;
            surface.roughness = fallbackRoughness;
            if (cacheWeight > 0.0) {
        #endif
        #ifdef SURFACE_DIRECT_REFERENCE
            HeightfieldSurfaceSample material = HeightfieldSurface_readDirect(
                    worldPosition.xz, dxWorld, dyWorld, m_SurfaceFixedLod);
        #else
            HeightfieldSurfaceSample material = HeightfieldSurface_readCache(
                    worldPosition.xz, dxWorld, dyWorld, m_SurfaceFixedLod);
        #endif
        // Canonical material normals use +Y as the tangent-space up direction.
        vec3 tangentAxis = abs(normal.x) < 0.99 ? vec3(1.0, 0.0, 0.0) : vec3(0.0, 0.0, 1.0);
        vec3 tangent = normalize(tangentAxis - normal * dot(tangentAxis, normal));
        vec3 bitangent = cross(tangent, normal);
        float momentLength = length(material.normalMoment);
        #ifdef SURFACE_REGION_FALLBACK
            vec3 cachedNormal = normal;
            if (momentLength > 1.0e-8) {
                cachedNormal = normalize(tangent * material.normalMoment.x
                        + normal * material.normalMoment.y + bitangent * material.normalMoment.z);
            }
            // Blend variance in roughness-squared space, before recovering roughness.
            float variance = clamp(1.0 - momentLength, 0.0, 1.0);
            vec3 blendedNormal = mix(normal, cachedNormal, cacheWeight);
            float blendedNormalLengthSquared = dot(blendedNormal, blendedNormal);
            if (cacheWeight >= 1.0) {
                surface.normal = cachedNormal;
            } else if (blendedNormalLengthSquared > 1.0e-8) {
                surface.normal = blendedNormal * inversesqrt(blendedNormalLengthSquared);
            } else {
                surface.normal = normal;
            }
            surface.albedo = mix(fallbackAlbedo, material.albedo, cacheWeight);
            surface.roughness = clamp(sqrt(max(mix(fallbackRoughness * fallbackRoughness,
                    material.roughnessSquared + variance, cacheWeight), 0.0)), 0.04, 1.0);
        #else
        if (momentLength > 1.0e-8) {
            surface.normal = normalize(tangent * material.normalMoment.x
                    + normal * material.normalMoment.y + bitangent * material.normalMoment.z);
        }
        surface.albedo = material.albedo;
        // Bounded variance broadening suppresses unresolved normal sparkle.
        // This is a filtering heuristic, not a calibrated microfacet fit.
        float variance = clamp(1.0 - momentLength, 0.0, 1.0);
        surface.roughness = clamp(sqrt(max(material.roughnessSquared + variance, 0.0)), 0.04, 1.0);
        #endif
        #ifdef SURFACE_REGION_FALLBACK
            }
        #endif
    #else
        proceduralMaterial(worldPosition, normal, surface.albedo, surface.roughness);
    #endif

    PBRLightingUtils_calculatePreLightingValues(surface);

    for (int i = 0; i < NB_LIGHTS; i += 3) {
        #if (defined(GL_ES) && __VERSION__ >= 300) || (!defined(GL_ES) && __VERSION__ >= 150)
        if (i >= g_LightCount * 3) {
            break;
        }
        #endif
        #ifdef DIRECTIONAL_VISIBILITY_ENABLED
            vec4 lightData0 = g_LightData[i];
            vec4 lightData1 = g_LightData[i + 1];
            bool designatedSun = DirectionalVisibilityMatchesLight(lightData0, lightData1);
            vec3 directBefore = surface.directLightContribution.rgb;
        #endif
        PBRLightingUtils_computeDirectLightContribution(
            g_LightData[i], g_LightData[i + 1], g_LightData[i + 2], surface);
        #ifdef DIRECTIONAL_VISIBILITY_ENABLED
            if (designatedSun) {
                float viewDepth = -(g_ViewMatrix * vec4(worldPosition, 1.0)).z;
                float visibility = DirectionalVisibilityForPosition(worldPosition, viewDepth);
                vec3 sunDelta = surface.directLightContribution.rgb - directBefore;
                surface.directLightContribution.rgb = directBefore + sunDelta * visibility;
            }
        #endif
    }

    // Indirect sky light stays separate from direct-only sun visibility.
    // The existing lighting logic identifies its first draw even without an
    // AmbientLight; later additive draws must not repeat this contribution.
    #ifdef HEIGHTFIELD_SKY_IRRADIANCE
        // EnvMapUtils.prepareShCoefs ordering is 0, y, z, x, yx, yz,
        // (3z²-1), zx, (x²-y²). The prepared coefficients already evaluate
        // to diffuse irradiance divided by PI for a Lambertian surface.
        vec3 skyIrradiance = sphericalHarmonics(surface.normal, m_SkyIrradianceSH);
        vec3 indirectFill = g_IsFirstLightPass
                ? surface.diffuseColor * skyIrradiance : vec3(0.0);
    #else
        vec3 skyFill = max(g_AmbientLightColor.rgb, vec3(0.0));
        float skyFacing = 0.32 + 0.68 * clamp(normal.y * 0.5 + 0.5, 0.0, 1.0);
        vec3 indirectFill = surface.diffuseColor * skyFill * skyFacing;
    #endif

    gl_FragColor = vec4(surface.directLightContribution + indirectFill, 1.0);
}
