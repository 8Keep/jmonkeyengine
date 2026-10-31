#import "Common/ShaderLib/GLSLCompat.glsllib"

uniform sampler2D m_SkyViewMap;
uniform vec3 m_ViewUp;
uniform vec3 m_ViewForward;
uniform vec3 m_ViewSide;
uniform float m_HorizonElevation;
uniform vec3 m_SunDirection;
uniform vec4 m_SolarRadiance;
uniform mat4 g_ProjectionMatrix;
uniform mat4 g_ProjectionMatrixInverse;
uniform mat4 g_ViewMatrixInverse;
uniform vec4 g_ViewPort;

const float SKY_PI = 3.14159265358979323846;

void main() {
    // Reconstruct the ray at this exact pixel. Interpolated normals from a
    // tessellated sky sphere lose enough angular precision to shift the
    // horizon-focused lookup. The same path covers orthographic cameras.
    vec2 ndc = 2.0 * (gl_FragCoord.xy - g_ViewPort.xy) / g_ViewPort.zw - 1.0;
    vec3 viewRay = g_ProjectionMatrix[3][3] == 1.0 ? vec3(0.0, 0.0, -1.0)
            : (g_ProjectionMatrixInverse * vec4(ndc, -1.0, 1.0)).xyz;
    vec3 ray = normalize((g_ViewMatrixInverse * vec4(viewRay, 0.0)).xyz);
    vec3 up = normalize(m_ViewUp);
    vec3 forward = normalize(m_ViewForward);
    vec3 side = normalize(m_ViewSide);
    float latitude = asin(clamp(dot(ray, up), -1.0, 1.0));
    float delta = latitude - m_HorizonElevation;
    float hemisphereSpan = delta >= 0.0
            ? max(1.5707963267948966 - m_HorizonElevation, 1.0e-6)
            : max(1.5707963267948966 + m_HorizonElevation, 1.0e-6);
    float verticalUnit = 0.5 + 0.5 * sign(delta) * sqrt(clamp(abs(delta) / hemisphereSpan, 0.0, 1.0));
    verticalUnit = delta >= 0.0 ? max(verticalUnit, 64.0 / 127.0)
                                : min(verticalUnit, 63.0 / 127.0);
    float sampleV = (0.5 + verticalUnit * 127.0) / 128.0;
    float phi = atan(dot(ray, side), dot(ray, forward));
    float sampleU = fract((phi + SKY_PI) / (2.0 * SKY_PI));
    vec3 sky = texture2D(m_SkyViewMap, vec2(sampleU, sampleV)).rgb;

    float horizonWidth = max(fwidth(latitude), 1.0e-6);
    float aboveHorizon = smoothstep(m_HorizonElevation - horizonWidth,
            m_HorizonElevation + horizonWidth, latitude);
    float sunAngle = acos(clamp(dot(ray, normalize(m_SunDirection)), -1.0, 1.0));
    float sunWidth = max(fwidth(sunAngle) * 0.75, 1.0e-6);
    float disk = (1.0 - smoothstep(0.004675 - sunWidth, 0.004675 + sunWidth, sunAngle))
            * aboveHorizon;
    gl_FragColor = vec4(sky + m_SolarRadiance.rgb * disk, 1.0);
}
