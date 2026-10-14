#import "Common/ShaderLib/GLSLCompat.glsllib"
#import "Common/MatDefs/Terrain/Heightfield/HeightfieldSampling.glsllib"

attribute vec3 inPosition;
varying vec3 vLocalPosition;

void main() {
    ivec2 patchVertex = ivec2(floor(inPosition.xz + vec2(0.5)));
    ivec2 globalGrid;
    vec3 localPosition = Heightfield_lodPosition(patchVertex, globalGrid);
    vLocalPosition = localPosition;

    vec2 pixelCenter = (vec2(patchVertex) + vec2(0.5))
            / float(m_PatchGridCells + 1);
    gl_Position = vec4(pixelCenter * 2.0 - 1.0, 0.0, 1.0);
    gl_PointSize = 1.0;
}
