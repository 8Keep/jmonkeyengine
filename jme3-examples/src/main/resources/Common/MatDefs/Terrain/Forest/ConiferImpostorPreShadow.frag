#import "Common/ShaderLib/GLSLCompat.glsllib"
#import "Common/MatDefs/Terrain/Forest/ConiferImpostorSample.glsllib"
void main() {
    vec3 albedo, normal, worldPosition;
    ConiferReconstruct(albedo, normal, worldPosition);
    gl_FragColor = vec4(1.0);
}
