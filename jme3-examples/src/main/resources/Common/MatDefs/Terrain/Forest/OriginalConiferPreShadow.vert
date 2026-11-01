#import "Common/ShaderLib/GLSLCompat.glsllib"
#import "Common/MatDefs/Terrain/Forest/ConiferInstance.glsllib"

uniform mat4 g_WorldViewProjectionMatrix;
attribute vec3 inPosition;
attribute vec3 inTexCoord;
#ifdef CONIFER_LOD_FADE
attribute float inSize;
flat varying float vConiferLodWeight;
#endif
varying vec3 vTexCoord;

void main() {
    vTexCoord = inTexCoord;
#ifdef CONIFER_LOD_FADE
    vConiferLodWeight = inSize;
#endif
    gl_Position = g_WorldViewProjectionMatrix * vec4(ConiferInstancePosition(inPosition), 1.0);
}
