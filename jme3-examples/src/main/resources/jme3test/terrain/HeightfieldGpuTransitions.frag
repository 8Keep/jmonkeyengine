#ifdef GL_ES
precision highp float;
precision highp int;
#endif
layout(std140) uniform m_Result {
    vec4 header;
    vec4 records[340];
};
out vec4 fragColor;
void main() {
    int x = int(gl_FragCoord.x);
    int row = int(gl_FragCoord.y);
    fragColor = row == 0 ? header : records[x * 4 + row - 1];
}
