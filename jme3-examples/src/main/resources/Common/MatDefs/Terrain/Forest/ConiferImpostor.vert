#import "Common/ShaderLib/GLSLCompat.glsllib"
#import "Common/MatDefs/Terrain/Forest/ConiferImpostorData.glsllib"

uniform mat4 g_WorldViewProjectionMatrix;
uniform mat4 g_WorldMatrix;
uniform mat4 g_ViewMatrix;
uniform mat4 g_ProjectionMatrix;
uniform vec3 g_CameraPosition;
attribute vec3 inPosition;
attribute vec4 inTexCoord2;
attribute vec3 inTexCoord3;
attribute float inSize;
varying vec3 vPlanePoint;
flat varying vec3 vObserverDirection;
flat varying vec3 vLocalEye;
flat varying vec4 vPositionScale;
flat varying vec3 vYawTint;
flat varying float vConiferLodWeight;

vec3 InverseInstanceDirection(vec3 direction) {
    float c = inTexCoord3.x, s = inTexCoord3.y;
    return vec3(c * direction.x - s * direction.z, direction.y,
                s * direction.x + c * direction.z);
}
vec3 InstancePoint(vec3 point) {
    float c = inTexCoord3.x, s = inTexCoord3.y;
    return vec3(c * point.x + s * point.z, point.y,
               -s * point.x + c * point.z) * inTexCoord2.w + inTexCoord2.xyz;
}
void main() {
    vec3 worldCenter = (g_WorldMatrix * vec4(InstancePoint(coniferCenter.xyz), 1.0)).xyz;
    mat3 worldToSource = inverse(mat3(g_WorldMatrix));
    bool parallel = g_ProjectionMatrix[3][3] == 1.0;
    vec3 direction = parallel
            ? vec3(g_ViewMatrix[0][2], g_ViewMatrix[1][2], g_ViewMatrix[2][2])
            : g_CameraPosition - worldCenter;
    vObserverDirection = normalize(InverseInstanceDirection(worldToSource * direction));
    vec3 eyeSource = (inverse(g_WorldMatrix) * vec4(g_CameraPosition, 1.0)).xyz;
    vLocalEye = InverseInstanceDirection(eyeSource - inTexCoord2.xyz) / inTexCoord2.w;
    vec3 right = abs(vObserverDirection.y) > 0.999999
            ? vec3(1.0, 0.0, 0.0) : normalize(cross(vec3(0.0, 1.0, 0.0), vObserverDirection));
    vec3 up = normalize(cross(vObserverDirection, right));
    float width = dot(abs(right), coniferExtents.xyz);
    float height = dot(abs(up), coniferExtents.xyz);
    float distanceToCenter = length(vLocalEye - coniferCenter.xyz);
    float margin = parallel ? 1.0 : min(1.12, distanceToCenter / max(1.0, distanceToCenter - 10.631));
    vPlanePoint = coniferCenter.xyz + margin * (right * width * inPosition.x + up * height * inPosition.y);
    vPositionScale = inTexCoord2;
    vYawTint = inTexCoord3;
    vConiferLodWeight = inSize;
    gl_Position = g_WorldViewProjectionMatrix * vec4(InstancePoint(vPlanePoint), 1.0);
}
