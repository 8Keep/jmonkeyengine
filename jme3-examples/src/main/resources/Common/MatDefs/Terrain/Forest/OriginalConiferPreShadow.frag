#import "Common/ShaderLib/GLSLCompat.glsllib"
#ifdef CONIFER_LOD_FADE
#import "Common/MatDefs/Terrain/Forest/ConiferLodFade.glsllib"
flat varying float vConiferLodWeight;
#endif

uniform sampler2D m_FrondTexture;
uniform float m_AlphaDiscardThreshold;
varying vec3 vTexCoord;

void main() {
    vec2 uvDx = dFdx(vTexCoord.xy);
    vec2 uvDy = dFdy(vTexCoord.xy);
#ifdef CONIFER_LOD_FADE
    ConiferMeshFade(vConiferLodWeight);
#endif
    if (vTexCoord.z > 0.5
            && textureGrad(m_FrondTexture, vTexCoord.xy, uvDx, uvDy).a <= m_AlphaDiscardThreshold) {
        discard;
    }
    gl_FragColor = vec4(1.0);
}
