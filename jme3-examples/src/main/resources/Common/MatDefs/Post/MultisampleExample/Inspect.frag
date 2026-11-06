#import "Common/ShaderLib/GLSLCompat.glsllib"
#import "Common/ShaderLib/MultiSample.glsllib"
uniform COLORTEXTURE m_Texture;
uniform DEPTHTEXTURE m_DepthTexture;
uniform int m_Sample;
uniform bool m_DepthOnly;
varying vec2 texCoord;
void main() {
    gl_FragColor = m_DepthOnly ? vec4(fetchTextureSample(m_DepthTexture, texCoord, m_Sample).r)
            : fetchTextureSample(m_Texture, texCoord, m_Sample);
}
