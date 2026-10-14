#import "Common/ShaderLib/GLSLCompat.glsllib"
attribute vec3 inPosition;
uniform vec2 m_SurfaceProbeOrigin;
uniform vec2 m_SurfaceProbeExtent;
varying vec2 surfaceWorldXZ;
void main() {
    surfaceWorldXZ = m_SurfaceProbeOrigin + inPosition.xy * m_SurfaceProbeExtent;
    gl_Position = vec4(inPosition.xy * 2.0 - 1.0, 0.0, 1.0);
}
