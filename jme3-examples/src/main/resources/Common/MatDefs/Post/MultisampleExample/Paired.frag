#import "Common/ShaderLib/GLSLCompat.glsllib"
#import "Common/ShaderLib/MultiSample.glsllib"
uniform COLORTEXTURE m_Texture;
uniform DEPTHTEXTURE m_DepthTexture;
varying vec2 texCoord;
vec4 transformSample(vec4 color, float depth) {
    // A generic depth-dependent operation: color and depth must stay paired.
    if (depth >= 1.0) return color;
    return vec4(color.rgb * (0.25 + 0.75 * depth) + vec3(0.1 * depth), color.a);
}
void main() {
#if defined(RESOLVE_MS) && defined(RESOLVE_DEPTH_MS) && RESOLVE_MS == RESOLVE_DEPTH_MS
    vec4 sum = vec4(0.0);
    for (int sampleId = 0; sampleId < m_NumSamples; ++sampleId) {
        sum += transformSample(fetchTextureSample(m_Texture, texCoord, sampleId),
                fetchTextureSample(m_DepthTexture, texCoord, sampleId).r);
    }
    gl_FragColor = sum / float(m_NumSamples);
#else
    gl_FragColor = transformSample(getColor(m_Texture, texCoord), getDepth(m_DepthTexture, texCoord).r);
#endif
}
