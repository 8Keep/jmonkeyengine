#import "Common/ShaderLib/GLSLCompat.glsllib"
#import "Common/MatDefs/Terrain/Heightfield/HeightfieldSampling.glsllib"

layout(std140) uniform m_Selection {
    vec4 m_Header;
    vec4 m_Records[340];
};
uniform mat4 m_ViewProjection;
in vec3 inPosition;
out vec3 v_WorldPosition;
out vec3 v_Normal;

void main() {
    int record = 170 + gl_InstanceID * 2;
    vec4 patchData = m_Records[record];
    vec4 info = m_Records[record + 1];
    int edgeMask = int(floor(info.y + 0.5)) & 15;
    int stride = int(floor(patchData.w + 0.5));
    ivec2 vertex = ivec2(floor(inPosition.xz + vec2(0.5)));
    vec4 edgeMorph = vec4((edgeMask & 1) != 0 ? 1.0 : 0.0,
                         (edgeMask & 2) != 0 ? 1.0 : 0.0,
                         (edgeMask & 4) != 0 ? 1.0 : 0.0,
                         (edgeMask & 8) != 0 ? 1.0 : 0.0);
    ivec2 grid;
    vec3 localPosition = Heightfield_lodPosition(vertex, ivec2(patchData.yz), stride,
                                                16, 0.0, edgeMorph, grid);
    float leftHeight = Heightfield_readGlobalHeight(grid + ivec2(-1, 0));
    float rightHeight = Heightfield_readGlobalHeight(grid + ivec2(1, 0));
    float southHeight = Heightfield_readGlobalHeight(grid + ivec2(0, -1));
    float northHeight = Heightfield_readGlobalHeight(grid + ivec2(0, 1));
    vec3 gradient = vec3(leftHeight * 0.5 - rightHeight * 0.5,
                         m_SampleSpacing,
                         southHeight * 0.5 - northHeight * 0.5);
    v_Normal = normalize(gradient / max(max(abs(gradient.x), gradient.y), abs(gradient.z)));
    v_WorldPosition = localPosition;
    gl_Position = m_ViewProjection * vec4(localPosition, 1.0);
}
