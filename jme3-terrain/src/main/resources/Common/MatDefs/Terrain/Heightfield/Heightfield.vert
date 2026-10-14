#import "Common/ShaderLib/GLSLCompat.glsllib"
#import "Common/MatDefs/Terrain/Heightfield/HeightfieldSampling.glsllib"

uniform mat4 g_WorldMatrix;
uniform mat4 g_WorldViewProjectionMatrix;
uniform mat3 g_WorldNormalMatrix;

attribute vec3 inPosition;

// Preserve rasterized depth across light batches and shared displacement passes.
invariant gl_Position;

varying vec3 wPosition;
varying vec3 wNormal;

void main() {
    ivec2 grid;
    vec3 localPosition;
    float leftHeight;
    float rightHeight;
    float backHeight;
    float frontHeight;

#ifdef HEIGHTFIELD_LOD
    ivec2 patchVertex = ivec2(floor(inPosition.xz + vec2(0.5)));
    localPosition = Heightfield_lodPosition(patchVertex, grid);
    // Shading keeps the fine source-lattice central difference as an explicit
    // approximation while geometry moves between fine and parent surfaces.
    leftHeight = Heightfield_readGlobalHeight(grid + ivec2(-1, 0));
    rightHeight = Heightfield_readGlobalHeight(grid + ivec2(1, 0));
    backHeight = Heightfield_readGlobalHeight(grid + ivec2(0, -1));
    frontHeight = Heightfield_readGlobalHeight(grid + ivec2(0, 1));
#else
    grid = ivec2(floor(inPosition.xz + vec2(0.5)));
    float height = Heightfield_readHeight(grid);
    leftHeight = Heightfield_readHeight(grid + ivec2(-1, 0));
    rightHeight = Heightfield_readHeight(grid + ivec2(1, 0));
    backHeight = Heightfield_readHeight(grid + ivec2(0, -1));
    frontHeight = Heightfield_readHeight(grid + ivec2(0, 1));
    localPosition = vec3(inPosition.x * m_SampleSpacing, height,
                         inPosition.z * m_SampleSpacing);
#endif

    // Equivalent to the central-difference gradient, without overflow from
    // subtracting opposite finite heights or dividing by very small spacing.
    vec3 gradientNormal = vec3(leftHeight * 0.5 - rightHeight * 0.5,
                              m_SampleSpacing,
                              backHeight * 0.5 - frontHeight * 0.5);
    float largest = max(max(abs(gradientNormal.x), abs(gradientNormal.z)),
                        gradientNormal.y);
    vec3 localNormal = normalize(gradientNormal / largest);

    vec4 worldPosition = g_WorldMatrix * vec4(localPosition, 1.0);

    wPosition = worldPosition.xyz;
    wNormal = normalize(g_WorldNormalMatrix * localNormal);
    gl_Position = g_WorldViewProjectionMatrix * vec4(localPosition, 1.0);
}
