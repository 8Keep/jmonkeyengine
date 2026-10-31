#import "Common/ShaderLib/GLSLCompat.glsllib"
#import "MatDefs/AtmosphereTransport.glsllib"

uniform vec2 m_AtmosphereRadii;
uniform vec4 m_Rayleigh;
uniform vec4 m_Mie;
uniform vec3 m_GroundAlbedo;
uniform sampler2D m_TransmittanceMap;
uniform sampler2D m_MultipleScatteringMap;
uniform vec3 m_ViewUp;
uniform vec3 m_ViewForward;
uniform vec3 m_ViewSide;
uniform float m_ObserverAltitudeKm;
uniform float m_HorizonElevation;
uniform vec3 m_SunDirection;
uniform float m_SolarIrradiance;
uniform bool m_MultipleScattering;
varying vec2 texCoord;

const int VIEW_SEGMENTS = 64;

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
    float verticalUnit = clamp((texCoord.y * 128.0 - 0.5) / 127.0, 0.0, 1.0);
    float q = 2.0 * verticalUnit - 1.0;
    float latitude = m_HorizonElevation
            + (q >= 0.0 ? 1.5707963267948966 - m_HorizonElevation
                        : 1.5707963267948966 + m_HorizonElevation)
                    * q * abs(q);
    float phi = 6.283185307179586 * fract(texCoord.x) - ATMOSPHERE_PI;
    vec3 ray = normalize(cos(latitude) * (cos(phi) * normalize(m_ViewForward)
            + sin(phi) * normalize(m_ViewSide)) + sin(latitude) * normalize(m_ViewUp));
    vec3 sunDirection = normalize(m_SunDirection);

    float groundRadius = m_AtmosphereRadii.x;
    float observerRadius = groundRadius + m_ObserverAltitudeKm;
    float mu = dot(normalize(m_ViewUp), ray);
    float b = observerRadius * mu;
    float groundOffset = m_ObserverAltitudeKm * (2.0 * groundRadius + m_ObserverAltitudeKm);
    float groundDiscriminant = b * b - groundOffset;
    bool groundHit = latitude < m_HorizonElevation;
    float distance;
    if (groundHit) {
        float groundRoot = sqrt(max(groundDiscriminant, 0.0));
        distance = m_ObserverAltitudeKm <= 0.0 ? 0.0
                : groundOffset / max(groundRoot - b, 1.0e-12);
    } else {
        float topOffset = (m_AtmosphereRadii.y - observerRadius)
                * (m_AtmosphereRadii.y + observerRadius);
        float topRoot = sqrt(max(b * b + topOffset, 0.0));
        distance = b >= 0.0 ? topOffset / max(topRoot + b, 1.0e-12) : -b + topRoot;
    }

    float cosinePhase = dot(ray, sunDirection);
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

        // Evaluate altitude from a factored radius difference so near-ground
        // samples do not lose precision by subtracting Earth-sized radii.
        float altitudeNumerator = groundOffset
                + 2.0 * observerRadius * mu * midpoint + midpoint * midpoint;
        float sampleRadius = sqrt(max(groundRadius * groundRadius + altitudeNumerator, 0.0));
        float altitude = max(0.0, altitudeNumerator / max(sampleRadius + groundRadius, 1.0e-12));
        vec3 density = vec3(exp(-altitude / m_Rayleigh.w), exp(-altitude / m_Mie.z), 0.0);
        vec3 extinction = AtmosphereExtinction(density, m_Rayleigh, m_Mie);
        vec3 scattering = AtmosphereScattering(density, m_Rayleigh, m_Mie);
        vec3 viewTransmittance = exp(-opticalDepth);
        vec3 integratedSegment = AtmosphereSegmentIntegral(extinction, segmentLength);
        vec3 radial = (observerRadius * normalize(m_ViewUp) + ray * midpoint) / sampleRadius;
        float cosineSunZenith = dot(radial, sunDirection);
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

    if (groundHit) {
        float groundDistance = distance;
        vec3 groundNormal = normalize(observerRadius * normalize(m_ViewUp) + ray * groundDistance);
        float cosineGroundSun = dot(groundNormal, sunDirection);
        if (cosineGroundSun > 0.0) {
            vec3 sunGround = transmittanceToSun(groundRadius, cosineGroundSun);
            radiance += exp(-opticalDepth) * sunGround * m_GroundAlbedo
                    * (cosineGroundSun / ATMOSPHERE_PI);
        }
    }

    gl_FragColor = vec4(radiance * m_SolarIrradiance, 1.0);
}
