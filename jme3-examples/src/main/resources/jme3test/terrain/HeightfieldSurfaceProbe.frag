#import "Common/ShaderLib/GLSLCompat.glsllib"
#import "Common/MatDefs/Terrain/Heightfield/HeightfieldSurfaceSampling.glsllib"
varying vec2 surfaceWorldXZ;
uniform float m_SurfaceProbeLod;
uniform int m_SurfaceProbeOutput;
uniform int m_SurfaceProbeRawLayer;
uniform int m_SurfaceProbeRawSide;
uniform vec2 m_SurfaceProbeExtent;
void main() {
    if (m_SurfaceProbeOutput == 4) {
        ivec2 cell = ivec2(floor(surfaceWorldXZ / m_SurfaceProbeExtent * float(m_SurfaceProbeRawSide)));
        gl_FragColor = texelFetch(m_SurfaceAlbedoPages, ivec3(cell, m_SurfaceProbeRawLayer), 0);
        return;
    }
    if (m_SurfaceProbeOutput == 5 || m_SurfaceProbeOutput == 6) {
        vec4 page = HeightfieldSurface_pageRecord(surfaceWorldXZ);
        int mip = int(floor(m_SurfaceProbeLod));
        float stride = page.z * exp2(float(mip));
        vec2 coordinate = (surfaceWorldXZ / m_SurfaceCanonicalSpacing - page.xy) / stride
                       + float(m_SurfaceGutter) - vec2(0.5);
        int layer = int(page.w + float(mip) + 0.5);
        if (m_SurfaceProbeOutput == 5) {
            gl_FragColor = vec4(coordinate, float(layer), m_SurfaceProbeLod);
        } else {
            ivec2 lo = ivec2(floor(coordinate));
            gl_FragColor = vec4(texelFetch(m_SurfaceAlbedoPages, ivec3(lo, layer), 0).r,
                texelFetch(m_SurfaceAlbedoPages, ivec3(lo + ivec2(1, 0), layer), 0).r,
                texelFetch(m_SurfaceAlbedoPages, ivec3(lo + ivec2(0, 1), layer), 0).r,
                texelFetch(m_SurfaceAlbedoPages, ivec3(lo + ivec2(1, 1), layer), 0).r);
        }
        return;
    }
    vec2 dx = dFdx(surfaceWorldXZ);
    vec2 dy = dFdy(surfaceWorldXZ);
#ifdef SURFACE_DIRECT_REFERENCE
    HeightfieldSurfaceSample value = HeightfieldSurface_readDirect(surfaceWorldXZ, dx, dy, m_SurfaceProbeLod);
#else
    HeightfieldSurfaceSample value = HeightfieldSurface_readCache(surfaceWorldXZ, dx, dy, m_SurfaceProbeLod);
#endif
    if (m_SurfaceProbeOutput == 1) {
        gl_FragColor = vec4(value.normalMoment, value.roughnessSquared);
    } else if (m_SurfaceProbeOutput == 2) {
        gl_FragColor = vec4(value.semanticId, 0.0, 0.0, 1.0);
    } else {
        gl_FragColor = vec4(value.albedo, value.detailWeight);
    }
}
