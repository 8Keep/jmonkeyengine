/*
 * Copyright (c) 2009-2026 jMonkeyEngine
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * * Redistributions of source code must retain the above copyright notice,
 *   this list of conditions and the following disclaimer.
 * * Redistributions in binary form must reproduce the above copyright notice,
 *   this list of conditions and the following disclaimer in the documentation
 *   and/or other materials provided with the distribution.
 * * Neither the name of 'jMonkeyEngine' nor the names of its contributors may
 *   be used to endorse or promote products derived from this software without
 *   specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package jme3test.terrain.forest;

import com.jme3.asset.AssetManager;
import com.jme3.material.MatParam;
import com.jme3.material.Material;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Renderer;
import com.jme3.scene.Mesh;
import com.jme3.scene.VertexBuffer;
import com.jme3.shader.bufferobject.BufferObject;
import com.jme3.texture.TextureArray;
import com.jme3.util.BufferUtils;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * Original-tree far representation. Owns only its quad and immutable frame UBO;
 * the baker owns the two shared texture arrays. Use under a uniform-scale parent.
 */
public final class ConiferImpostor implements AutoCloseable {
    public static final float BOUND_RADIUS = 17f; // sqrt(2) * source sphere radius * maximum 1.12 quad margin
    private static final int METADATA_BYTES = (2 + 3 * ConiferImpostorFrames.COUNT) * 16;
    private final Renderer renderer;
    private final BufferObject metadata = new BufferObject();
    private final Material material;
    private final Mesh mesh = new Mesh();
    private boolean closed;

    public ConiferImpostor(Renderer renderer, AssetManager assets, Material lighting,
            TextureArray colors, TextureArray normalDepth) {
        if (renderer == null || assets == null || lighting == null || colors == null || normalDepth == null) {
            throw new IllegalArgumentException("renderer, assets, lighting and baked arrays are required");
        }
        validateArray(colors); validateArray(normalDepth);
        this.renderer = renderer;
        material = new Material(assets, "Common/MatDefs/Terrain/Forest/ConiferImpostor.j3md");
        for (MatParam param : lighting.getParams()) {
            if (material.getMaterialDef().getMaterialParam(param.getName()) != null) {
                material.setParam(param.getName(), param.getVarType(), param.getValue());
            }
        }
        material.setTexture("ConiferColorFrames", colors);
        material.setTexture("ConiferNormalDepthFrames", normalDepth);
        metadata.setData(BufferUtils.createByteBuffer(METADATA_BYTES).order(ByteOrder.nativeOrder()));
        metadata.setBinding(4);
        FloatBuffer data = metadata.getData().order(ByteOrder.nativeOrder()).asFloatBuffer();
        data.put(new float[]{0, 9, 0, 0, 4, 9, 4, 0});
        Vector3f value = new Vector3f();
        for (int i = 0; i < ConiferImpostorFrames.COUNT; i++) {
            put(data, ConiferImpostorFrames.direction(i, value), 0);
            put(data, ConiferImpostorFrames.right(i, value), ConiferImpostorFrames.halfWidth(i));
            put(data, ConiferImpostorFrames.up(i, value), ConiferImpostorFrames.halfHeight(i));
        }
        metadata.setUpdateNeeded();
        material.setUniformBufferObject("ConiferFrameData", metadata);
        mesh.setBuffer(VertexBuffer.Type.Position, 3, new float[]{-1,-1,0, 1,-1,0, 1,1,0, -1,1,0});
        mesh.setBuffer(VertexBuffer.Type.Index, 3, new short[]{0,1,2, 0,2,3});
        mesh.updateBound();
    }

    /**
     * Continuous mesh weight for a screen-size transition. Capture availability
     * fades at the observer/light horizon; close perspectives stay on geometry.
     */
    public static float meshWeight(float pixels, float localDistance, float observerUp,
            float sunUp, float readyFraction) {
        if (!Float.isFinite(pixels) || !Float.isFinite(localDistance) || !Float.isFinite(observerUp)
                || !Float.isFinite(sunUp) || !Float.isFinite(readyFraction) || localDistance < 0) {
            throw new IllegalArgumentException("finite projected size, distance and availability required");
        }
        float pixelWeight = unit((pixels - 24) / 16);
        float distanceWeight = unit((160 - localDistance) / 56);
        float available = smooth(observerUp / .08f) * smooth(sunUp / .06f) * smooth(readyFraction);
        return 1 - (1 - Math.max(pixelWeight, distanceWeight)) * available;
    }
    private static float unit(float value) { return Math.max(0, Math.min(1, value)); }
    private static float smooth(float value) { float t = unit(value); return t * t * (3 - 2 * t); }

    private static void put(FloatBuffer target, Vector3f value, float fourth) {
        target.put(value.x).put(value.y).put(value.z).put(fourth);
    }
    private static void validateArray(TextureArray texture) {
        com.jme3.texture.Image image = texture.getImage();
        if (image == null || image.getWidth() != ConiferImpostorFrames.WIDTH
                || image.getHeight() != ConiferImpostorFrames.HEIGHT
                || image.getData().size() != ConiferImpostorFrames.COUNT || !image.hasMipmaps()) {
            throw new IllegalArgumentException("complete 65-view baked mip arrays are required");
        }
    }
    private void ensureOpen() { if (closed) throw new IllegalStateException("impostor is closed"); }
    public Material getMaterial() { ensureOpen(); return material; }
    public Mesh getMesh() { ensureOpen(); return mesh; }
    public int getMetadataBytes() { return METADATA_BYTES; }

    /** Caller must detach/drain every color/caster user before closing. */
    @Override public void close() {
        if (closed) return;
        closed = true;
        renderer.deleteBuffer(metadata);
        BufferUtils.destroyDirectBuffer(metadata.getData());
        for (VertexBuffer buffer : mesh.getBufferList().getArray()) {
            renderer.deleteBuffer(buffer);
            BufferUtils.destroyDirectBuffer(buffer.getData());
        }
    }
}
