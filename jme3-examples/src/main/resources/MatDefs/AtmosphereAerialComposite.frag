#import "Common/ShaderLib/GLSLCompat.glsllib"
#import "Common/ShaderLib/MultiSample.glsllib"

uniform COLORTEXTURE m_Texture;
uniform DEPTHTEXTURE m_DepthTexture;
uniform sampler2D m_AerialMap;
uniform vec2 m_FrustumNearFar;
uniform bool m_ParallelProjection;
uniform float m_MaxDepthMeters;
varying vec2 texCoord;

const int TILE_SIZE = 32;
const int SLICES_X = 8;
const int SLICE_COUNT = 32;
const float AERIAL_ATLAS_WIDTH = 512.0;
const float AERIAL_ATLAS_HEIGHT = 128.0;

vec3 sampleAerialField(float slicePosition, vec2 uv, bool transmittanceField) {
    float clampedSlice = clamp(slicePosition, 0.0, float(SLICE_COUNT - 1));
    float lowerSlice = floor(clampedSlice);
    float upperSlice = min(lowerSlice + 1.0, float(SLICE_COUNT - 1));
    float blend = clampedSlice - lowerSlice;

    vec2 localCenter = vec2(0.5) + clamp(uv, vec2(0.0), vec2(1.0))
            * float(TILE_SIZE - 1);
    float fieldOffset = transmittanceField ? float(SLICES_X * TILE_SIZE) : 0.0;
    float lowerColumn = mod(lowerSlice, float(SLICES_X));
    float upperColumn = mod(upperSlice, float(SLICES_X));
    float lowerRow = floor(lowerSlice / float(SLICES_X));
    float upperRow = floor(upperSlice / float(SLICES_X));
    vec2 lowerPixel = vec2(fieldOffset + lowerColumn * float(TILE_SIZE),
            lowerRow * float(TILE_SIZE)) + localCenter;
    vec2 upperPixel = vec2(fieldOffset + upperColumn * float(TILE_SIZE),
            upperRow * float(TILE_SIZE)) + localCenter;
    vec3 lowerValue = texture2D(m_AerialMap,
            lowerPixel / vec2(AERIAL_ATLAS_WIDTH, AERIAL_ATLAS_HEIGHT)).rgb;
    vec3 upperValue = texture2D(m_AerialMap,
            upperPixel / vec2(AERIAL_ATLAS_WIDTH, AERIAL_ATLAS_HEIGHT)).rgb;
    return mix(lowerValue, upperValue, blend);
}

float viewDepthFromDeviceDepth(float depth) {
    float nearPlane = m_FrustumNearFar.x;
    float farPlane = m_FrustumNearFar.y;
    if (m_ParallelProjection) return mix(nearPlane, farPlane, depth);
    float ndcDepth = 2.0 * depth - 1.0;
    return (2.0 * nearPlane * farPlane)
            / (farPlane + nearPlane - ndcDepth * (farPlane - nearPlane));
}

void main() {
    vec4 scene = getColor(m_Texture, texCoord);
    float deviceDepth = getDepth(m_DepthTexture, texCoord).r;
    if (deviceDepth >= 1.0) {
        gl_FragColor = scene;
        return;
    }

    float viewDepth = viewDepthFromDeviceDepth(deviceDepth);
    float slicePosition = sqrt(clamp(viewDepth / max(m_MaxDepthMeters, 1.0e-6),
            0.0, 1.0)) * float(SLICE_COUNT - 1);
    vec3 radiance = sampleAerialField(slicePosition, texCoord, false);
    vec3 transmittance = sampleAerialField(slicePosition, texCoord, true);
    gl_FragColor = vec4(scene.rgb * transmittance + radiance, scene.a);
}
