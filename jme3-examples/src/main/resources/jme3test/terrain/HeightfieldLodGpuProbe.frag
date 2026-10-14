#import "Common/ShaderLib/GLSLCompat.glsllib"

varying vec3 vLocalPosition;

void main() {
    gl_FragColor = vec4(vLocalPosition, 1.0);
}
