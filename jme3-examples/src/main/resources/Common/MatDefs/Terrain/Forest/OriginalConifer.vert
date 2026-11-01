#import "Common/ShaderLib/GLSLCompat.glsllib"
#import "Common/MatDefs/Terrain/Forest/ConiferInstance.glsllib"

uniform mat4 g_WorldViewProjectionMatrix;
uniform mat4 g_WorldMatrix;
uniform mat3 g_WorldNormalMatrix;

attribute vec3 inPosition;
attribute vec3 inNormal;
attribute vec3 inTexCoord;
attribute vec4 inColor;
#ifdef CONIFER_LOD_FADE
attribute float inSize;
flat varying float vConiferLodWeight;
#endif

varying vec3 vWorldPosition;
varying vec3 vWorldNormal;
varying vec3 vObjectPosition;
varying vec3 vObjectNormal;
varying vec3 vTexCoord;
varying vec4 vColor;
varying vec3 vInstanceTint;

void main() {
    vec3 localPosition = ConiferInstancePosition(inPosition);
    vec4 worldPosition = g_WorldMatrix * vec4(localPosition, 1.0);
    vWorldPosition = worldPosition.xyz;
    vWorldNormal = normalize(g_WorldNormalMatrix * ConiferInstanceNormal(inNormal));
    vObjectPosition = inPosition;
    vObjectNormal = inNormal;
    vTexCoord = inTexCoord;
    vColor = inColor;
    vInstanceTint = ConiferInstanceTint();
#ifdef CONIFER_LOD_FADE
    vConiferLodWeight = inSize;
#endif
#ifdef CONIFER_INSTANCING
    gl_Position = g_WorldViewProjectionMatrix * vec4(localPosition, 1.0);
#else
    gl_Position = g_WorldViewProjectionMatrix * vec4(inPosition, 1.0);
#endif
}
