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
import com.jme3.asset.TextureKey;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.texture.Image;
import com.jme3.texture.Texture;
import com.jme3.texture.image.ColorSpace;
import com.jme3.util.MipMapGenerator;

/** Material factory for the original conifer's opaque wood and alpha-tested fronds. */
public final class OriginalConiferMaterial {
    public static final String DEFINITION = "Common/MatDefs/Terrain/Forest/OriginalConifer.j3md";
    public static final String FROND_TEXTURE = "Textures/Terrain/Forest/conifer-frond-v1.png";
    public static final float FROND_ALPHA_THRESHOLD = .42f;

    private OriginalConiferMaterial() {}

    public static Material create(AssetManager assets) {
        return create(assets, new Vector3f(-.35f, -.82f, -.45f).normalizeLocal(),
                new ColorRGBA(1.3f, 1.18f, .96f, 1f), new ColorRGBA(.34f, .39f, .31f, 1f));
    }

    public static Material create(AssetManager assets, Vector3f sunDirection,
            ColorRGBA sunColor, ColorRGBA ambientColor) {
        Material material = new Material(assets, DEFINITION);
        TextureKey key = new TextureKey(FROND_TEXTURE, true);
        key.setGenerateMips(false);
        key.setAnisotropy(4);
        Texture texture = assets.loadTexture(key);
        Image image = texture.getImage().clone();
        image.setColorSpace(ColorSpace.sRGB);
        MipMapGenerator.generateMipMaps(image, true, true);
        texture.setImage(image);
        texture.setWrap(Texture.WrapMode.EdgeClamp);
        texture.setMagFilter(Texture.MagFilter.Bilinear);
        texture.setMinFilter(Texture.MinFilter.Trilinear);
        material.setTexture("FrondTexture", texture);
        material.setFloat("AlphaDiscardThreshold", FROND_ALPHA_THRESHOLD);
        material.setColor("SunColor", sunColor);
        material.setColor("AmbientColor", ambientColor);
        material.setVector3("SunDirection", sunDirection.normalize());
        return material;
    }
}
