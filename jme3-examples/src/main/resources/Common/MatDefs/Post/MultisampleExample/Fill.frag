#import "Common/ShaderLib/GLSLCompat.glsllib"
uniform vec4 m_Color;
uniform float m_Depth;
uniform bool m_Pattern;
void main() {
    float checker = mod(floor(gl_FragCoord.x / 3.0) + floor(gl_FragCoord.y / 3.0), 2.0);
    float scale = m_Pattern ? mix(0.5, 1.0, checker) : 1.0;
    gl_FragColor = vec4(m_Color.rgb * scale, m_Color.a);
    gl_FragDepth = m_Depth;
}
