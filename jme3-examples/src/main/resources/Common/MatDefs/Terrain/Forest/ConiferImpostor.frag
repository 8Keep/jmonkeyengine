#import "Common/ShaderLib/GLSLCompat.glsllib"
#import "Common/ShaderLib/PBR.glsllib"
#import "Common/ShaderLib/module/pbrlighting/DirectionalVisibility.glsllib"
#import "Common/MatDefs/Terrain/Forest/ConiferImpostorSample.glsllib"
#import "Common/MatDefs/Terrain/Forest/ConiferDiffuseLighting.glsllib"
void main() {
    vec3 albedo, normal, worldPosition;
    ConiferReconstruct(albedo, normal, worldPosition);
    float visibility = 1.0;
#ifdef DIRECTIONAL_VISIBILITY
    float viewDepth = -(g_ViewMatrix * vec4(worldPosition, 1.0)).z;
    visibility = DirectionalVisibilityForPosition(worldPosition, viewDepth);
#endif
    // No per-pixel wood tag exists in this atlas. Treat the intended small
    // far representation as an aggregate canopy; unresolved wood shares it.
    gl_FragColor = vec4(ConiferDiffuseLighting(albedo * vYawTint.z,
            normal, 1.0, visibility), 1.0);
}
