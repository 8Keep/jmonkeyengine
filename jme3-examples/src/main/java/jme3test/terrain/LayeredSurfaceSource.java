/*
 * Copyright (c) 2009-2026 jMonkeyEngine
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 * * Redistributions of source code must retain the above copyright
 *   notice, this list of conditions and the following disclaimer.
 *
 * * Redistributions in binary form must reproduce the above copyright
 *   notice, this list of conditions and the following disclaimer in the
 *   documentation and/or other materials provided with the distribution.
 *
 * * Neither the name of 'jMonkeyEngine' nor the names of its contributors
 *   may be used to endorse or promote products derived from this software
 *   without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A
 * PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER
 * OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package jme3test.terrain;

import com.jme3.renderer.Renderer;
import com.jme3.terrain.heightfield.HeightfieldSurfacePage;
import com.jme3.texture.Image;
import com.jme3.texture.Texture;
import com.jme3.texture.Texture2D;
import com.jme3.texture.TextureArray;
import com.jme3.texture.image.ColorSpace;
import com.jme3.util.BufferUtils;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/** Original finite three-material source used only by the surface controls. */
final class LayeredSurfaceSource implements HeightfieldSurfacePage.Source {
    static final int SIDE = 256;
    static final int ORIGIN = -64;
    static final float SPACING = .5f;
    final HeightfieldSurfacePage.Revision revision;
    private static final float[][] BASE_RGB = {{.16f, .28f, .08f}, {.36f, .34f, .31f}, {.32f, .19f, .09f}};
    private final float[][] colors = new float[3][SIDE * SIDE * 4];
    private final float[][] normals = new float[3][SIDE * SIDE * 4];
    private final float[] weights = new float[SIDE * SIDE * 4];
    TextureArray albedoTexture, normalTexture;
    Texture2D weightTexture;

    LayeredSurfaceSource(HeightfieldSurfacePage.Revision revision) {
        this.revision = revision;
        for (int z = 0; z < SIDE; z++) for (int x = 0; x < SIDE; x++) {
            int offset = (z * SIDE + x) * 4;
            double wx = (x + ORIGIN + .5) * SPACING;
            double wz = (z + ORIGIN + .5) * SPACING;
            for (int layer = 0; layer < 3; layer++) {
                float detail = (float) (.08 * Math.sin(wx * (1.7 + layer))
                        * Math.cos(wz * (2.1 + layer * .6)));
                float[] rgb = BASE_RGB[layer];
                float tint = layer == 1 && revision.getAppearance() > 0 ? .12f : 0;
                for (int c = 0; c < 3; c++) colors[layer][offset + c] = Math.max(.02f, rgb[c] + detail + tint);
                colors[layer][offset + 3] = layer == 0 ? .82f : layer == 1 ? .53f : .9f;
                float nx = (float) (.35 * Math.sin(wx * (2.7 + layer) + wz));
                float nz = (float) (.35 * Math.cos(wz * (3.1 + layer) - wx));
                float inverseLength = 1f / (float) Math.sqrt(1 + nx * nx + nz * nz);
                normals[layer][offset] = nx * inverseLength;
                normals[layer][offset + 1] = inverseLength;
                normals[layer][offset + 2] = nz * inverseLength;
                normals[layer][offset + 3] = layer == 0 ? 0f : layer == 1 ? 1f : .15f;
            }
            // A height edit changes both geometry and its material classifier.
            double dx = (heightAt(wx + SPACING, wz, revision)
                    - heightAt(wx - SPACING, wz, revision)) / (2 * SPACING);
            double dz = (heightAt(wx, wz + SPACING, revision)
                    - heightAt(wx, wz - SPACING, revision)) / (2 * SPACING);
            float rock = (float) (.15 + .35 * Math.min(1, Math.hypot(dx, dz) / .3)
                    + .05 * Math.sin(wx * .13));
            float soil = (float) (.2 + .18 * Math.cos(wz * .16));
            float grass = 1 - rock - soil;
            if (revision.getSemantics() > 0 && wx > 28 && wx < 33) {
                rock = 0; soil = 1; grass = 0;
            }
            weights[offset] = grass;
            weights[offset + 1] = rock;
            weights[offset + 2] = soil;
            weights[offset + 3] = grass >= rock && grass >= soil ? 1 : rock >= soil ? 2 : 3;
        }
    }

    /** Original rolling field shared by geometry and the slope classifier. */
    static float heightAt(double wx, double wz, HeightfieldSurfacePage.Revision revision) {
        double rolling = 1.7 * Math.sin(wx * .075) * Math.cos(wz * .061)
                + .9 * Math.sin((wx + wz) * .12) + .45 * Math.cos(wz * .21 - wx * .08);
        double ridge = Math.max(0, 1 - Math.abs(Math.sin(wx * .035 + wz * .027))) * 2.4;
        double edit = revision.getHeight() > 0
                ? 1.25 * Math.exp(-((wx - 32) * (wx - 32) + (wz - 31) * (wz - 31)) / 70) : 0;
        return (float) (rolling + ridge + edit);
    }

    @Override public void sample(long x, long z, HeightfieldSurfacePage.Sample store) {
        long localX = x - ORIGIN, localZ = z - ORIGIN;
        if (localX < 0 || localX >= SIDE || localZ < 0 || localZ >= SIDE) {
            throw new IllegalArgumentException("Fixture source does not cover this canonical cell");
        }
        int offset = ((int) localZ * SIDE + (int) localX) * 4;
        float red = 0, green = 0, blue = 0, nx = 0, ny = 0, nz = 0, roughnessSquared = 0, detailWeight = 0;
        for (int layer = 0; layer < 3; layer++) {
            float weight = weights[offset + layer];
            red += colors[layer][offset] * weight;
            green += colors[layer][offset + 1] * weight;
            blue += colors[layer][offset + 2] * weight;
            nx += normals[layer][offset] * weight;
            ny += normals[layer][offset + 1] * weight;
            nz += normals[layer][offset + 2] * weight;
            float roughness = colors[layer][offset + 3];
            roughnessSquared += roughness * roughness * weight;
            detailWeight += normals[layer][offset + 3] * weight;
        }
        float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        store.albedoR = red; store.albedoG = green; store.albedoB = blue;
        store.normalX = nx / length; store.normalY = ny / length; store.normalZ = nz / length;
        store.roughness = (float) Math.sqrt(roughnessSquared);
        store.semanticId = (int) weights[offset + 3];
        store.detailWeight = detailWeight;
    }

    void createTextures() {
        if (albedoTexture != null) throw new IllegalStateException("Fixture already owns GPU inputs");
        albedoTexture = array(colors);
        normalTexture = array(normals);
        weightTexture = new Texture2D(image(weights));
        nearest(weightTexture);
    }

    private static TextureArray array(float[][] values) {
        List<Image> images = new ArrayList<>();
        for (float[] layer : values) images.add(image(layer));
        TextureArray texture = new TextureArray(images);
        nearest(texture);
        return texture;
    }

    private static Image image(float[] values) {
        ByteBuffer data = BufferUtils.createByteBuffer(values.length * Float.BYTES).order(ByteOrder.nativeOrder());
        for (float value : values) data.putFloat(value);
        data.flip();
        return new Image(Image.Format.RGBA32F, SIDE, SIDE, data, ColorSpace.Linear);
    }

    private static void nearest(Texture texture) {
        texture.setMinFilter(Texture.MinFilter.NearestNoMipMaps);
        texture.setMagFilter(Texture.MagFilter.Nearest);
        texture.setWrap(Texture.WrapMode.EdgeClamp);
    }

    void close(Renderer renderer) {
        if (albedoTexture == null) return;
        release(renderer, albedoTexture.getImage());
        release(renderer, normalTexture.getImage());
        release(renderer, weightTexture.getImage());
        albedoTexture = null;
        normalTexture = null;
        weightTexture = null;
    }

    private static void release(Renderer renderer, Image image) {
        renderer.deleteImage(image);
        for (ByteBuffer data : image.getData()) BufferUtils.destroyDirectBuffer(data);
    }
}
