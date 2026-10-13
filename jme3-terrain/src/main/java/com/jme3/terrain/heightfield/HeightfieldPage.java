/*
 * Copyright (c) 2009-2024 jMonkeyEngine
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
package com.jme3.terrain.heightfield;

import com.jme3.bounding.BoundingBox;
import com.jme3.math.Vector3f;
import com.jme3.texture.Image;
import com.jme3.texture.image.ColorSpace;
import com.jme3.util.BufferUtils;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Immutable CPU height samples for one GPU-rendered regular patch. A patch has
 * {@code cells + 1} vertices per edge and one neighbor sample on each side,
 * hence {@code (cells + 3)^2} row-major samples. Heights and spacing share the
 * caller's local units. Adjacent pages must sample the same source lattice.
 * This render payload does not own generation, edits, collision or persistence.
 */
public final class HeightfieldPage {
    private final int cells;
    private final float spacing;
    private final float[] samples;
    private final float minimum;
    private final float maximum;

    public HeightfieldPage(int cells, float spacing, float[] samples) {
        if (cells < 2 || cells > 256) {
            throw new IllegalArgumentException("cells must be between 2 and 256");
        }
        if (!Float.isFinite(spacing) || spacing <= 0 || !Float.isFinite(cells * spacing)) {
            throw new IllegalArgumentException("spacing and patch extent must be positive and finite");
        }
        int side = cells + 3;
        if (samples == null || samples.length != side * side) {
            throw new IllegalArgumentException("height page must include its complete one-sample halo");
        }
        this.cells = cells;
        this.spacing = spacing;
        this.samples = samples.clone();
        float low = Float.POSITIVE_INFINITY;
        float high = Float.NEGATIVE_INFINITY;
        for (int z = -1; z <= cells + 1; z++) {
            for (int x = -1; x <= cells + 1; x++) {
                float value = getHeight(x, z);
                if (!Float.isFinite(value)) {
                    throw new IllegalArgumentException("height samples must be finite, including halos");
                }
                if (x >= 0 && x <= cells && z >= 0 && z <= cells) {
                    low = Math.min(low, value);
                    high = Math.max(high, value);
                }
            }
        }
        // Compute the bounds in double precision to avoid overflow when two
        // individually finite heights have opposite signs near Float.MAX_VALUE.
        if (!Float.isFinite((float) (((double) high - low) * .5))) {
            throw new IllegalArgumentException("height range cannot be represented by finite bounds");
        }
        minimum = low;
        maximum = high;
    }

    public int getCells() {
        return cells;
    }

    public int getTextureSize() {
        return cells + 3;
    }

    public float getSampleSpacing() {
        return spacing;
    }

    public float getHeight(int x, int z) {
        if (x < -1 || x > cells + 1 || z < -1 || z > cells + 1) {
            throw new IndexOutOfBoundsException("sample lies outside the page and its halo");
        }
        return samples[(z + 1) * getTextureSize() + x + 1];
    }

    /** Returns a new complete R32F image, with independent direct CPU storage. */
    public Image createImage() {
        ByteBuffer data = BufferUtils.createByteBuffer(samples.length * Float.BYTES)
                .order(ByteOrder.nativeOrder());
        for (float value : samples) {
            data.putFloat(value);
        }
        data.flip();
        return new Image(Image.Format.Luminance32F, getTextureSize(), getTextureSize(),
                data, ColorSpace.Linear);
    }

    /** Conservative bounds for vertex displacement and its triangle interiors. */
    public BoundingBox createBound() {
        float endpoint = cells * spacing;
        float centerXZ = endpoint * .5f;
        float centerY = (float) (((double) minimum + maximum) * .5);
        // Centers can round toward either endpoint, including for subnormal
        // spacing. Size each extent from its actual float center, then outward.
        float halfXZ = outwardExtent(centerXZ, 0, endpoint);
        float halfY = outwardExtent(centerY, minimum, maximum);
        return new BoundingBox(new Vector3f(centerXZ, centerY, centerXZ),
                halfXZ, halfY, halfXZ);
    }

    private static float outwardExtent(float center, float low, float high) {
        double required = Math.max((double) center - low, (double) high - center);
        float extent = (float) required;
        if (extent < required) {
            extent = Math.nextUp(extent);
        }
        return extent;
    }
}
