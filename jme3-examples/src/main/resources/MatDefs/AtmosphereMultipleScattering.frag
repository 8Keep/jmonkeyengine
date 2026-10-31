#import "Common/ShaderLib/GLSLCompat.glsllib"
#import "MatDefs/AtmosphereTransport.glsllib"

uniform vec2 m_AtmosphereRadii;
uniform vec4 m_Rayleigh;
uniform vec4 m_Mie;
uniform vec3 m_GroundAlbedo;
uniform sampler2D m_TransmittanceMap;
varying vec2 texCoord;

const int DIRECTION_COUNT = 64;
const int SEGMENT_COUNT = 32;
const float GOLDEN_ANGLE = 2.39996322972865332;

vec3 transmittanceToSun(float radius, float cosineZenith) {
    if (AtmosphereRayHitsGround(radius, cosineZenith, m_AtmosphereRadii)) {
        return vec3(0.0);
    }
    vec2 unit = AtmosphereTransmittanceUnit(radius, cosineZenith, m_AtmosphereRadii);
    vec2 uv = AtmosphereUvFromUnitRange(unit, textureSize(m_TransmittanceMap, 0));
    return texture2D(m_TransmittanceMap, uv).rgb;
}

void main() {
    ivec2 lutSize = ivec2(32, 32);
    vec2 unit = AtmosphereUnitRangeFromRaster(texCoord, lutSize);
    float cosineSunZenith = 2.0 * unit.x - 1.0;
    float radius = m_AtmosphereRadii.x
            + unit.y * (m_AtmosphereRadii.y - m_AtmosphereRadii.x);
    vec3 origin = vec3(0.0, radius, 0.0);
    vec3 multipleOrderTwo = vec3(0.0);
    vec3 transfer = vec3(0.0);

    for (int directionIndex = 0; directionIndex < DIRECTION_COUNT; ++directionIndex) {
        float z = 1.0 - 2.0 * (float(directionIndex) + 0.5) / float(DIRECTION_COUNT);
        float radial = sqrt(max(0.0, 1.0 - z * z));
        float phi = GOLDEN_ANGLE * float(directionIndex);
        vec3 direction = vec3(radial * cos(phi), z, radial * sin(phi));

        float cosineViewZenith = dot(direction, vec3(0.0, 1.0, 0.0));
        bool groundHit = AtmosphereRayHitsGround(radius, cosineViewZenith, m_AtmosphereRadii);
        float distance = groundHit
                ? AtmosphereDistanceToGround(radius, cosineViewZenith, m_AtmosphereRadii)
                : AtmosphereDistanceToTop(radius, cosineViewZenith, m_AtmosphereRadii);
        vec3 opticalDepth = vec3(0.0);

        for (int step = 0; step < SEGMENT_COUNT; ++step) {
            float f0 = float(step) / float(SEGMENT_COUNT);
            float f1 = float(step + 1) / float(SEGMENT_COUNT);
            float d0 = distance * f0 * f0;
            float d1 = distance * f1 * f1;
            float segmentLength = d1 - d0;
            float midpoint = 0.5 * (d0 + d1);
            vec3 position = origin + direction * midpoint;
            float sampleRadius = length(position);
            vec3 density = AtmosphereDensity(sampleRadius,
                    m_AtmosphereRadii, m_Rayleigh, m_Mie);
            vec3 extinction = AtmosphereExtinction(density, m_Rayleigh, m_Mie);
            vec3 scattering = AtmosphereScattering(density, m_Rayleigh, m_Mie);
            vec3 viewTransmittance = exp(-opticalDepth);
            vec3 integratedSegment = AtmosphereSegmentIntegral(extinction, segmentLength);
            float cosineSun = dot(position / sampleRadius,
                    vec3(sqrt(max(0.0, 1.0 - cosineSunZenith * cosineSunZenith)),
                    cosineSunZenith, 0.0));
            vec3 sunTransmittance = transmittanceToSun(sampleRadius, cosineSun);
            // Eq. 6 uses the isotropic unit phase for L0's direct source.
            vec3 directSource = scattering * (1.0 / (4.0 * ATMOSPHERE_PI));
            multipleOrderTwo += viewTransmittance * integratedSegment
                    * sunTransmittance * directSource;
            transfer += viewTransmittance * integratedSegment * scattering;
            opticalDepth += extinction * segmentLength;
        }

        if (groundHit) {
            vec3 groundPosition = origin + direction * distance;
            vec3 groundNormal = normalize(groundPosition);
            float cosineGroundSun = dot(groundNormal,
                    vec3(sqrt(max(0.0, 1.0 - cosineSunZenith * cosineSunZenith)),
                    cosineSunZenith, 0.0));
            if (cosineGroundSun > 0.0) {
                vec3 sunGround = transmittanceToSun(m_AtmosphereRadii.x, cosineGroundSun);
                multipleOrderTwo += exp(-opticalDepth) * sunGround
                        * m_GroundAlbedo * (cosineGroundSun / ATMOSPHERE_PI);
            }
        }
    }

    multipleOrderTwo /= float(DIRECTION_COUNT);
    transfer /= float(DIRECTION_COUNT);
    // Keep the transfer visible in alpha and do not clamp it: values at or
    // above one reveal a failed energy-transfer estimate instead of hiding it.
    vec3 psi = multipleOrderTwo / (vec3(1.0) - transfer);
    float maxTransfer = max(transfer.r, max(transfer.g, transfer.b));
    gl_FragColor = vec4(psi, maxTransfer);
}
