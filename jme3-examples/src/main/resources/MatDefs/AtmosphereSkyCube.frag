#import "Common/ShaderLib/GLSLCompat.glsllib"
#import "MatDefs/AtmosphereTransport.glsllib"

uniform vec2 m_AtmosphereRadii;
uniform vec4 m_Rayleigh;
uniform vec4 m_Mie;
uniform vec3 m_GroundAlbedo;
uniform sampler2D m_TransmittanceMap;
uniform sampler2D m_MultipleScatteringMap;
uniform int m_CubeFace;
uniform float m_ObserverHeight;
uniform vec3 m_SunDirection;
uniform float m_SolarIrradiance;
uniform bool m_MultipleScattering;
varying vec2 texCoord;

const int VIEW_SEGMENTS = 32;

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

vec3 cubeDirection(vec2 uv, int face) {
    vec2 p = 2.0 * uv - 1.0;
    vec3 direction;
    if (face == 0) direction = vec3(1.0, -p.y, -p.x);
    else if (face == 1) direction = vec3(-1.0, -p.y, p.x);
    else if (face == 2) direction = vec3(p.x, 1.0, p.y);
    else if (face == 3) direction = vec3(p.x, -1.0, -p.y);
    else if (face == 4) direction = vec3(p.x, -p.y, 1.0);
    else direction = vec3(-p.x, -p.y, -1.0);
    return normalize(direction);
}

void main() {
    vec3 direction = cubeDirection(texCoord, m_CubeFace);
    vec3 sunDirection = normalize(m_SunDirection);
    float observerRadius = m_AtmosphereRadii.x + m_ObserverHeight;
    vec3 origin = vec3(0.0, observerRadius, 0.0);
    float viewCosineZenith = direction.y;
    bool groundHit = AtmosphereRayHitsGround(observerRadius,
            viewCosineZenith, m_AtmosphereRadii);
    float distance = groundHit
            ? AtmosphereDistanceToGround(observerRadius, viewCosineZenith, m_AtmosphereRadii)
            : AtmosphereDistanceToTop(observerRadius, viewCosineZenith, m_AtmosphereRadii);

    float cosinePhase = dot(direction, sunDirection);
    float rayleighPhase = AtmosphereRayleighPhase(cosinePhase);
    float miePhase = AtmosphereMiePhase(cosinePhase, m_Mie.w);
    vec3 opticalDepth = vec3(0.0);
    vec3 radiance = vec3(0.0);

    for (int step = 0; step < VIEW_SEGMENTS; ++step) {
        float f0 = float(step) / float(VIEW_SEGMENTS);
        float f1 = float(step + 1) / float(VIEW_SEGMENTS);
        float d0 = distance * f0 * f0;
        float d1 = distance * f1 * f1;
        float segmentLength = d1 - d0;
        float midpoint = 0.5 * (d0 + d1);
        vec3 position = origin + direction * midpoint;
        float radius = length(position);
        vec3 density = AtmosphereDensity(radius,
                m_AtmosphereRadii, m_Rayleigh, m_Mie);
        vec3 extinction = AtmosphereExtinction(density, m_Rayleigh, m_Mie);
        vec3 scattering = AtmosphereScattering(density, m_Rayleigh, m_Mie);
        vec3 viewTransmittance = exp(-opticalDepth);
        vec3 integratedSegment = AtmosphereSegmentIntegral(extinction, segmentLength);
        float cosineSunZenith = dot(position / radius, sunDirection);
        vec3 sunTransmittance = transmittanceToSun(radius, cosineSunZenith);
        vec3 directSource = density.x * m_Rayleigh.rgb * rayleighPhase
                + vec3(density.y * m_Mie.x * miePhase);
        vec3 source = directSource * sunTransmittance;
        if (m_MultipleScattering) {
            source += scattering * multipleScattering(radius, cosineSunZenith);
        }
        radiance += viewTransmittance * integratedSegment * source;
        opticalDepth += extinction * segmentLength;
    }

    if (groundHit) {
        vec3 groundPosition = origin + direction * distance;
        vec3 groundNormal = normalize(groundPosition);
        float cosineGroundSun = dot(groundNormal, sunDirection);
        if (cosineGroundSun > 0.0) {
            vec3 sunGround = transmittanceToSun(m_AtmosphereRadii.x, cosineGroundSun);
            radiance += exp(-opticalDepth) * sunGround * m_GroundAlbedo
                    * (cosineGroundSun / ATMOSPHERE_PI);
        }
    }

    gl_FragColor = vec4(radiance * m_SolarIrradiance, 1.0);
}
