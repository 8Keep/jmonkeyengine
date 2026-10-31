#import "Common/ShaderLib/GLSLCompat.glsllib"
#import "MatDefs/AtmosphereTransport.glsllib"

uniform vec2 m_AtmosphereRadii;
uniform vec4 m_Rayleigh;
uniform vec4 m_Mie;
varying vec2 texCoord;

void main() {
    vec2 unit = AtmosphereUnitRangeFromRaster(texCoord, ivec2(256, 64));
    float ground = m_AtmosphereRadii.x;
    float top = m_AtmosphereRadii.y;
    float H = sqrt(max(0.0, (top - ground) * (top + ground)));
    float rho = unit.y * H;
    float radius = sqrt(ground * ground + rho * rho);
    float distanceMin = top - radius;
    float distanceMax = rho + H;
    float distance = distanceMin + unit.x * (distanceMax - distanceMin);

    // At the exact top/zenith endpoint the ray has zero length.
    if (distance <= 1.0e-6) {
        gl_FragColor = vec4(1.0, 1.0, 1.0, 1.0);
        return;
    }

    float cosineZenith = (H * H - rho * rho - distance * distance)
            / (2.0 * radius * distance);
    cosineZenith = clamp(cosineZenith, -1.0, 1.0);

    float opticalRayleigh = 0.0;
    float opticalMie = 0.0;
    float previousDistance = 0.0;
    vec3 previousDensity = AtmosphereDensity(radius, m_AtmosphereRadii, m_Rayleigh, m_Mie);
    for (int i = 0; i < 40; ++i) {
        float fraction = float(i + 1) / 40.0;
        float currentDistance = distance * fraction * fraction;
        float segmentLength = currentDistance - previousDistance;
        float sampleRadius = sqrt(max(0.0, radius * radius
                + 2.0 * radius * cosineZenith * currentDistance
                + currentDistance * currentDistance));
        vec3 currentDensity = AtmosphereDensity(sampleRadius,
                m_AtmosphereRadii, m_Rayleigh, m_Mie);
        opticalRayleigh += 0.5 * (previousDensity.x + currentDensity.x) * segmentLength;
        opticalMie += 0.5 * (previousDensity.y + currentDensity.y) * segmentLength;
        previousDistance = currentDistance;
        previousDensity = currentDensity;
    }

    vec3 transmittance = exp(-m_Rayleigh.rgb * opticalRayleigh
            - vec3(m_Mie.y * opticalMie));
    gl_FragColor = vec4(transmittance, 1.0);
}
