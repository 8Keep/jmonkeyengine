#import "Common/ShaderLib/GLSLCompat.glsllib"
#import "Common/ShaderLib/PBR.glsllib"
#import "Common/ShaderLib/module/pbrlighting/DirectionalVisibility.glsllib"
#ifdef CONIFER_LOD_FADE
#import "Common/MatDefs/Terrain/Forest/ConiferLodFade.glsllib"
#endif

uniform sampler2D m_FrondTexture;
uniform float m_AlphaDiscardThreshold;
uniform vec3 m_ConiferBakeToObserver;
#import "Common/MatDefs/Terrain/Forest/ConiferDiffuseLighting.glsllib"

varying vec3 vWorldPosition;
varying vec3 vWorldNormal;
varying vec3 vObjectPosition;
varying vec3 vObjectNormal;
varying vec3 vTexCoord;
varying vec4 vColor;
varying vec3 vInstanceTint;
#ifdef CONIFER_LOD_FADE
flat varying float vConiferLodWeight;
#endif

void main() {
    vec2 uvDx = dFdx(vTexCoord.xy);
    vec2 uvDy = dFdy(vTexCoord.xy);
#ifdef CONIFER_LOD_FADE
    ConiferMeshFade(vConiferLodWeight);
#endif
    vec3 frond = vec3(1.0);
    float coverage = 1.0;
    if (vTexCoord.z > 0.5) {
        vec4 sampleColor = textureGrad(m_FrondTexture, vTexCoord.xy, uvDx, uvDy);
        if (sampleColor.a <= m_AlphaDiscardThreshold) discard;
        frond = sampleColor.rgb;
    }

#if CONIFER_BAKE_MODE == 1
    vec3 linearColor = vColor.rgb * frond * coverage;
    vec3 srgbColor = mix(12.92 * linearColor,
            1.055 * pow(max(linearColor, vec3(0.0)), vec3(1.0 / 2.4)) - 0.055,
            step(vec3(0.0031308), linearColor));
    gl_FragColor = vec4(srgbColor, coverage);
#elif CONIFER_BAKE_MODE == 2
    vec3 objectNormal = normalize(vObjectNormal);
    float signedDepth = dot(vObjectPosition - vec3(0.0, 9.0, 0.0),
            normalize(m_ConiferBakeToObserver));
    gl_FragColor = vec4(objectNormal * coverage, signedDepth * coverage);
#else

    vec3 normal = normalize(vWorldNormal);
    vec3 baseColor = vColor.rgb * vInstanceTint;
    float visibility = 1.0;
#ifdef DIRECTIONAL_VISIBILITY
    float viewDepth = -(g_ViewMatrix * vec4(vWorldPosition, 1.0)).z;
    visibility = DirectionalVisibilityForPosition(vWorldPosition, viewDepth);
#endif
    vec3 albedo = baseColor * frond;
    gl_FragColor = vec4(ConiferDiffuseLighting(albedo, normal,
            vTexCoord.z > 0.5 ? 1.0 : 0.0, visibility), 1.0);
#endif
}
