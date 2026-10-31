#import "Common/ShaderLib/GLSLCompat.glsllib"

attribute vec3 inPosition;
attribute vec2 inTexCoord;
varying vec2 texCoord;

void main() {
    texCoord = inTexCoord;
    gl_Position = vec4(inPosition.xy * 2.0 - 1.0, 0.0, 1.0);
}
