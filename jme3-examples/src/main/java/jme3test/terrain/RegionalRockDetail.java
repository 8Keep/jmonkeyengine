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
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package jme3test.terrain;

import com.jme3.asset.AssetManager;
import com.jme3.asset.TextureKey;
import com.jme3.material.Material;
import com.jme3.math.Vector3f;
import com.jme3.texture.Image;
import com.jme3.texture.Texture;
import com.jme3.texture.image.ColorSpace;

/** Shared image-backed rock albedo detail for regional heightfield materials. */
final class RegionalRockDetail {
    static final String ASSET_PATH = "Textures/Terrain/Heightfield/bedrock-albedo-v1.png";
    private static final float SCALE = 1f / 16f;
    private static final float STRENGTH = .45f;
    private static final Vector3f MEAN = new Vector3f(.25377848f, .21698237f, .17156249f);

    private final Texture texture;
    private final long baseImageBytes;
    private final long estimatedMipChainRgb8Bytes;

    RegionalRockDetail(AssetManager assetManager) {
        if (assetManager == null) throw new IllegalArgumentException("asset manager is required");
        TextureKey key = new TextureKey(ASSET_PATH, false);
        key.setGenerateMips(true);
        texture = assetManager.loadTexture(key);
        if (texture == null || texture.getImage() == null) {
            throw new IllegalStateException("could not load regional rock detail image");
        }
        texture.getImage().setColorSpace(ColorSpace.sRGB);
        texture.setMinFilter(Texture.MinFilter.Trilinear);
        texture.setMagFilter(Texture.MagFilter.Bilinear);
        texture.setWrap(Texture.WrapMode.MirroredRepeat);
        Image image = texture.getImage();
        baseImageBytes = image.getData(0).capacity();
        estimatedMipChainRgb8Bytes = estimateRgb8MipChainBytes(image.getWidth(), image.getHeight());
    }

    /** Bind the shared texture and its fixed world-space sampling parameters. */
    void bind(Material material) {
        if (material == null) throw new IllegalArgumentException("material is required");
        material.setTexture("RockDetailMap", texture);
        material.setVector3("RockDetailMean", MEAN.clone());
        material.setFloat("RockDetailWorldScale", SCALE);
        material.setFloat("RockDetailStrength", STRENGTH);
    }

    long getBaseImageBytes() {
        return baseImageBytes;
    }

    /** Estimated RGB8 GPU bytes for the complete mip chain, including level zero. */
    long getEstimatedMipChainRgb8Bytes() {
        return estimatedMipChainRgb8Bytes;
    }

    private static long estimateRgb8MipChainBytes(int width, int height) {
        long total = 0;
        while (true) {
            total += (long) width * height * 3;
            if (width == 1 && height == 1) return total;
            width = Math.max(1, width / 2);
            height = Math.max(1, height / 2);
        }
    }
}
