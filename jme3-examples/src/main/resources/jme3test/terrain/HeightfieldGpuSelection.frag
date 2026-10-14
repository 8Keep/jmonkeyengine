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
    if (row == 0) fragColor = header;
    else if (row == 1) fragColor = records[x*2];
    else if (row == 2) fragColor = records[x*2+1];
    else if (row == 3) fragColor = records[170+x*2];
    else fragColor = records[170+x*2+1];
}
