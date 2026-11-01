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
#ifdef ROCK_DETAIL
uniform sampler2D m_RockDetailMap;
uniform vec3 m_RockDetailMean;
uniform float m_RockDetailWorldScale;
uniform float m_RockDetailStrength;
#endif
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

#ifdef SURFACE_REGION_FALLBACK
// Fade unresolved procedural octaves toward their mean rather than leaving
// subpixel grass/snow speckle in distant views. Derivatives are supplied before
// the material-table branch, never taken in nonuniform control flow.
float farMaterialNoise(vec2 position, float frequency, float footprint) {
    float resolved = 1.0 - smoothstep(0.25, 0.75, footprint * frequency);
    return mix(0.5, valueNoise(position * frequency), resolved);
}
#endif

#ifdef ROCK_DETAIL
vec3 rockDetailModulation(vec3 position, vec3 normal, vec3 dxPosition, vec3 dyPosition) {
    float scale = max(m_RockDetailWorldScale, 0.00001);
    vec3 p = position * scale;
    vec3 dx = dxPosition * scale;
    vec3 dy = dyPosition * scale;
    vec3 weights = abs(normal);
    weights *= weights;
    weights *= weights;
    weights /= max(weights.x + weights.y + weights.z, 0.00001);
    // Explicit gradients remain defined when this procedural fallback is
    // evaluated in a nonuniform material-page region branch. Coordinates stay
    // unwrapped: the sampler performs mirrored repeat after LOD selection.
    vec3 detail = textureGrad(m_RockDetailMap, p.yz, dx.yz, dy.yz).rgb * weights.x
            + textureGrad(m_RockDetailMap, p.xz, dx.xz, dy.xz).rgb * weights.y
            + textureGrad(m_RockDetailMap, p.xy, dx.xy, dy.xy).rgb * weights.z;
    vec3 modulation = clamp(detail / max(m_RockDetailMean, vec3(0.01)), vec3(0.45), vec3(1.8));
    return mix(vec3(1.0), modulation, clamp(m_RockDetailStrength, 0.0, 1.0));
}
#endif

void proceduralMaterial(vec3 worldPosition, vec3 normal, vec3 dxPosition, vec3 dyPosition,
        out vec3 albedo, out float roughness) {
    #ifdef SURFACE_REGION_FALLBACK
        // Metre-scale fields continue across streamed height pages. Broad
        // exposure/snow variation avoids a perfectly horizontal snow ring.
        float footprint = max(length(dxPosition.xz), length(dyPosition.xz));
        float exposure = farMaterialNoise(worldPosition.xz + vec2(310.0, -170.0), 0.007, footprint);
        float patches = farMaterialNoise(worldPosition.xz + vec2(-90.0, 230.0), 0.028, footprint);
        float grain = farMaterialNoise(worldPosition.xz + vec2(50.0, -410.0), 0.14, footprint);
        float breakup = (exposure - 0.5) * 0.18 + (patches - 0.5) * 0.13;
        // Rise/run, matching the regional material source's slope convention.
        float slope = sqrt(max(1.0 - normal.y * normal.y, 0.0)) / max(abs(normal.y), 0.0001);
        float rockWeight = smoothstep(0.35, 1.05, slope + breakup);
    #else
        vec2 worldXZ = worldPosition.xz * max(m_TextureScale, 0.00001);
        float broadBreakup = valueNoise(worldXZ * 0.34);
        float fineBreakup = valueNoise(worldXZ * 2.7);
        float breakup = (broadBreakup - 0.5) * 0.22 + (fineBreakup - 0.5) * 0.07;
        float steepness = 1.0 - clamp(normal.y, 0.0, 1.0);
        float rockWeight = smoothstep(0.22, 0.68, steepness + breakup);
    #endif
    vec3 rockColor = m_RockColor.rgb;
    #ifdef ROCK_DETAIL
        rockColor *= rockDetailModulation(worldPosition, normal, dxPosition, dyPosition);
    #endif
    vec3 groundColor = mix(m_GrassColor.rgb, rockColor, rockWeight);
    #ifdef SURFACE_REGION_FALLBACK
        groundColor *= 0.86 + patches * 0.20 + grain * 0.08;
    #else
        groundColor *= 0.94 + fineBreakup * 0.12;
    #endif
    float snowFade = max(m_SnowFade, 0.0001);
    #ifdef SURFACE_REGION_FALLBACK
        float snowHeight = worldPosition.y + (exposure - 0.5) * 150.0 + (patches - 0.5) * 32.0;
        float snowWeight = smoothstep(m_SnowLine - snowFade, m_SnowLine + snowFade, snowHeight);
        snowWeight *= 1.0 - smoothstep(0.50, 1.15, slope);
    #else
        float snowWeight = smoothstep(m_SnowLine - snowFade, m_SnowLine + snowFade,
                                      worldPosition.y + breakup * snowFade * 0.4);
        snowWeight *= 1.0 - smoothstep(0.58, 0.88, steepness);
    #endif
    albedo = mix(groundColor, m_SnowColor.rgb, snowWeight);
    roughness = clamp(m_Roughness, 0.04, 1.0);
}

void main() {
    vec3 worldPosition = PBRLightingUtils_getWorldPosition();
    vec3 normal = normalize(PBRLightingUtils_getWorldNormal());
    // Capture derivatives before any dependent cache or procedural branch.
    #if defined(HEIGHTFIELD_SURFACE) || defined(ROCK_DETAIL) || defined(SURFACE_REGION_FALLBACK)
        vec3 dxPosition = dFdx(worldPosition);
        vec3 dyPosition = dFdy(worldPosition);
    #else
        vec3 dxPosition = vec3(0.0);
        vec3 dyPosition = vec3(0.0);
    #endif
    PBRSurface surface = PBRLightingUtils_createPBRSurface(
        normalize(g_CameraPosition - worldPosition));
    surface.position = worldPosition;
    surface.geometryNormal = normal;
    surface.normal = normal;
    surface.metallic = 0.0;

    #ifdef HEIGHTFIELD_SURFACE
        // Compute derivatives before dependent page-table lookup.
        vec2 dxWorld = dxPosition.xz;
        vec2 dyWorld = dyPosition.xz;
        #ifdef SURFACE_REGION_FALLBACK
            vec2 regionPosition = worldPosition.xz - m_SurfaceRegionOrigin;
            float edgeDistance = min(min(regionPosition.x, regionPosition.y),
                    min(m_SurfaceRegionSize - regionPosition.x,
                            m_SurfaceRegionSize - regionPosition.y));
            float cacheWeight = smoothstep(0.0, max(m_SurfaceRegionFade, 0.0001), edgeDistance);
            vec3 fallbackAlbedo = vec3(0.0);
            float fallbackRoughness = 0.0;
            if (cacheWeight < 1.0) {
                proceduralMaterial(worldPosition, normal, dxPosition, dyPosition,
                        fallbackAlbedo, fallbackRoughness);
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
        proceduralMaterial(worldPosition, normal, dxPosition, dyPosition, surface.albedo, surface.roughness);
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
