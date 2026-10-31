#import "Common/ShaderLib/GLSLCompat.glsllib"
#import "MatDefs/AtmosphereTransport.glsllib"

uniform vec2 m_AtmosphereRadii;
uniform vec4 m_Rayleigh;
uniform vec4 m_Mie;
uniform sampler2D m_TransmittanceMap;
uniform sampler2D m_MultipleScatteringMap;
uniform vec3 m_SunDirection;
uniform float m_SolarIrradiance;
uniform bool m_MultipleScattering;
uniform vec3 m_CameraOrigin;
uniform vec3 m_CameraForward;
uniform vec3 m_CameraRight;
uniform vec3 m_CameraUp;
uniform vec4 m_Frustum;
uniform float m_FrustumNear;
uniform bool m_ParallelProjection;
uniform float m_MaxDepthMeters;
varying vec2 texCoord;

const int TILE_SIZE = 32;
const int SLICES_X = 8;
const int SLICE_COUNT = 32;
const int INTEGRATION_SEGMENTS = 32;

vec3 transmittanceToSun(float radius, float cosineZenith) {
    if (AtmosphereRayHitsGround(radius, cosineZenith, m_AtmosphereRadii)) {
        return vec3(0.0);
    }
    vec2 unit = AtmosphereTransmittanceUnit(radius, cosineZenith, m_AtmosphereRadii);
    vec2 uv = AtmosphereUvFromUnitRange(unit, textureSize(m_TransmittanceMap, 0));
    return texture2D(m_TransmittanceMap, uv).rgb;
}

vec3 multipleScattering(float radius, float cosineSunZenith) {
    vec2 unit = AtmosphereMultipleScatteringUnit(
            cosineSunZenith, radius, m_AtmosphereRadii);
    vec2 uv = AtmosphereUvFromUnitRange(unit, textureSize(m_MultipleScatteringMap, 0));
    return texture2D(m_MultipleScatteringMap, uv).rgb;
}

void main() {
    ivec2 pixel = ivec2(floor(gl_FragCoord.xy));
    ivec2 tile = pixel / TILE_SIZE;
    ivec2 localPixel = pixel - tile * TILE_SIZE;
    bool transmittanceField = tile.x >= SLICES_X;
    if (transmittanceField) tile.x -= SLICES_X;

    int slice = tile.y * SLICES_X + tile.x;
    vec2 rasterCenter = vec2(localPixel) + vec2(0.5);
    vec2 screenUnit = clamp((rasterCenter - vec2(0.5)) / float(TILE_SIZE - 1),
            vec2(0.0), vec2(1.0));
    if (slice == 0) {
        gl_FragColor = vec4(transmittanceField ? vec3(1.0) : vec3(0.0), 1.0);
        return;
    }

    float sliceUnit = float(slice) / float(SLICE_COUNT - 1);
    float viewDepth = m_MaxDepthMeters * sliceUnit * sliceUnit;
    float frustumX = mix(m_Frustum.x, m_Frustum.y, screenUnit.x);
    float frustumY = mix(m_Frustum.z, m_Frustum.w, screenUnit.y);
    vec3 ray;
    vec3 rayOrigin = m_CameraOrigin;
    if (m_ParallelProjection) {
        ray = normalize(m_CameraForward);
        rayOrigin += m_CameraRight * frustumX + m_CameraUp * frustumY;
    } else {
        ray = normalize(m_CameraForward
                + m_CameraRight * (frustumX / m_FrustumNear)
                + m_CameraUp * (frustumY / m_FrustumNear));
    }

    // World coordinates use meters. The atmosphere model is kilometer-scaled
    // and centered at ground level, with world up aligned to +Y.
    vec3 origin = rayOrigin / 1000.0 + vec3(0.0, m_AtmosphereRadii.x, 0.0);
    vec3 sunDirection = normalize(m_SunDirection);
    float radius = length(origin);
    float viewCosineZenith = dot(origin / radius, ray);
    bool groundHit = AtmosphereRayHitsGround(radius, viewCosineZenith, m_AtmosphereRadii);
    float boundaryDistance = groundHit
            ? AtmosphereDistanceToGround(radius, viewCosineZenith, m_AtmosphereRadii)
            : AtmosphereDistanceToTop(radius, viewCosineZenith, m_AtmosphereRadii);
    float requestedDistance = viewDepth * length(m_ParallelProjection
            ? m_CameraForward
            : m_CameraForward + m_CameraRight * (frustumX / m_FrustumNear)
                    + m_CameraUp * (frustumY / m_FrustumNear)) / 1000.0;
    float distance = min(requestedDistance, boundaryDistance);

    float cosinePhase = dot(ray, sunDirection);
    float rayleighPhase = AtmosphereRayleighPhase(cosinePhase);
    float miePhase = AtmosphereMiePhase(cosinePhase, m_Mie.w);
    vec3 opticalDepth = vec3(0.0);
    vec3 radiance = vec3(0.0);
    for (int step = 0; step < INTEGRATION_SEGMENTS; ++step) {
        float f0 = float(step) / float(INTEGRATION_SEGMENTS);
        float f1 = float(step + 1) / float(INTEGRATION_SEGMENTS);
        float d0 = distance * f0 * f0;
        float d1 = distance * f1 * f1;
        float segmentLength = d1 - d0;
        float midpoint = 0.5 * (d0 + d1);
        vec3 position = origin + ray * midpoint;
        float sampleRadius = length(position);
        vec3 density = AtmosphereDensity(sampleRadius,
                m_AtmosphereRadii, m_Rayleigh, m_Mie);
        vec3 extinction = AtmosphereExtinction(density, m_Rayleigh, m_Mie);
        vec3 scattering = AtmosphereScattering(density, m_Rayleigh, m_Mie);
        vec3 viewTransmittance = exp(-opticalDepth);
        vec3 integratedSegment = AtmosphereSegmentIntegral(extinction, segmentLength);
        float cosineSunZenith = dot(position / sampleRadius, sunDirection);
        vec3 sunTransmittance = transmittanceToSun(sampleRadius, cosineSunZenith);
        vec3 directSource = density.x * m_Rayleigh.rgb * rayleighPhase
                + vec3(density.y * m_Mie.x * miePhase);
        vec3 source = directSource * sunTransmittance;
        if (m_MultipleScattering) {
            source += scattering * multipleScattering(sampleRadius, cosineSunZenith);
        }
        radiance += viewTransmittance * integratedSegment * source;
        opticalDepth += extinction * segmentLength;
    }

    vec3 value = transmittanceField ? exp(-opticalDepth) : radiance * m_SolarIrradiance;
    gl_FragColor = vec4(value, 1.0);
}
