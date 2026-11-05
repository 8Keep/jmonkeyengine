#ifdef GL_ES
precision highp float;
#endif
layout(std140) uniform m_Control { vec4 guardValue; };
layout(std140) uniform m_Payload { vec4 records[16]; };
out vec4 outFragColor;
void main() {
    if (guardValue.x < -10000.0) discard;
    int i = int(gl_FragCoord.x);
    outFragColor = records[i];
}
