#import "Common/ShaderLib/GLSLCompat.glsllib"

uniform mat4 g_WorldViewProjectionMatrix;
uniform mat4 g_WorldMatrix;
uniform mat3 g_WorldNormalMatrix;

attribute vec3 inPosition;
attribute vec3 inNormal;
attribute vec3 inTexCoord;
attribute vec4 inColor;

varying vec3 vWorldPosition;
varying vec3 vWorldNormal;
varying vec3 vTexCoord;
varying vec4 vColor;

void main() {
    vec4 worldPosition = g_WorldMatrix * vec4(inPosition, 1.0);
    vWorldPosition = worldPosition.xyz;
    vWorldNormal = normalize(g_WorldNormalMatrix * inNormal);
    vTexCoord = inTexCoord;
    vColor = inColor;
    gl_Position = g_WorldViewProjectionMatrix * vec4(inPosition, 1.0);
}
