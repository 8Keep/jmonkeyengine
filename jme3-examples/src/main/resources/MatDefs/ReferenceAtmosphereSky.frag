#import "Common/ShaderLib/GLSLCompat.glsllib"
uniform samplerCube m_SkyMap;
uniform vec3 m_SunDirection;
uniform vec4 m_SolarRadiance;
varying vec3 direction;

void main() {
    vec3 ray = normalize(direction);
    vec3 sky = textureCube(m_SkyMap, ray).rgb;
    float cosineAngle = clamp(dot(ray, normalize(m_SunDirection)), -1.0, 1.0);
    float angle = acos(cosineAngle);
    float edge = max(fwidth(angle) * 0.75, 0.000001);
    float disk = 1.0 - smoothstep(0.004675 - edge, 0.004675 + edge, angle);
    gl_FragColor = vec4(sky + m_SolarRadiance.rgb * disk, 1.0);
}
