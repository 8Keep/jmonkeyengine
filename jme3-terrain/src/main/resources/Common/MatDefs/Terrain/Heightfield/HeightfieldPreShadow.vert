#import "Common/ShaderLib/GLSLCompat.glsllib"
#import "Common/MatDefs/Terrain/Heightfield/HeightfieldSampling.glsllib"
uniform mat4 g_WorldViewProjectionMatrix;

attribute vec3 inPosition;

// Preserve rasterized depth across light batches and shared displacement passes.
invariant gl_Position;

void main() {
#ifdef HEIGHTFIELD_LOD
    ivec2 patchVertex = ivec2(floor(inPosition.xz + vec2(0.5)));
    ivec2 grid;
    vec3 localPosition = Heightfield_lodPosition(patchVertex, grid);
#else
    ivec2 grid = ivec2(floor(inPosition.xz + vec2(0.5)));
    vec3 localPosition = vec3(
        inPosition.x * m_SampleSpacing,
        Heightfield_readHeight(grid),
        inPosition.z * m_SampleSpacing);
#endif
    gl_Position = g_WorldViewProjectionMatrix * vec4(localPosition, 1.0);
}
