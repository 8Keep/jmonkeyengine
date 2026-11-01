#import "Common/ShaderLib/GLSLCompat.glsllib"
#import "Common/MatDefs/Terrain/Forest/ConiferInstance.glsllib"

uniform mat4 g_WorldViewProjectionMatrix;
attribute vec3 inPosition;
attribute vec3 inTexCoord;
varying vec3 vTexCoord;

void main() {
    vTexCoord = inTexCoord;
    gl_Position = g_WorldViewProjectionMatrix * vec4(ConiferInstancePosition(inPosition), 1.0);
}
