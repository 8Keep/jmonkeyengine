/*
 * Copyright (c) 2009-2024 jMonkeyEngine
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 * * Redistributions of source code must retain the above copyright notice,
 *   this list of conditions and the following disclaimer.
 * * Redistributions in binary form must reproduce the above copyright
 *   notice, this list of conditions and the following disclaimer in the
 *   documentation and/or other materials provided with the distribution.
 *
 * Neither the name of jMonkeyEngine nor the names of its contributors may be
 * used to endorse or promote products derived from this software without
 * specific prior written permission.
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
package com.jme3.terrain.heightfield;

import com.jme3.math.FastMath;
import com.jme3.texture.Image;
import com.jme3.texture.image.ColorSpace;
import com.jme3.util.BufferUtils;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Immutable, CPU-composed material payload for one canonical terrain page. */
public final class HeightfieldSurfacePage {
    /** Logical primitive-array payload per composed texel; excludes Java object headers. */
    public static final int CPU_BYTES_PER_TEXEL = 8 * Float.BYTES + 1;

    public static final int GUTTER = 2;
    public static final int MAX_FILTER_FOOTPRINT = 32;

    /** Revisions of the three independently editable source domains. */
    public static final class Revision {
        private final long height;
        private final long appearance;
        private final long semantics;

        public Revision(long height, long appearance, long semantics) {
            if (height < 0 || appearance < 0 || semantics < 0) {
                throw new IllegalArgumentException("revision components must be nonnegative");
            }
            this.height = height;
            this.appearance = appearance;
            this.semantics = semantics;
        }
        public long getHeight() {
            return height;
        }

        public long getAppearance() {
            return appearance;
        }

        public long getSemantics() {
            return semantics;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Revision)) {
                return false;
            }
            Revision r = (Revision) other;
            return height == r.height && appearance == r.appearance && semantics == r.semantics;
        }

        @Override
        public int hashCode() {
            long h = height * 31 + appearance;
            return (int) (h * 31 + semantics ^ ((h * 31 + semantics) >>> 32));
        }
    }

    /** Mutable allocation-free output record supplied to the sampling callback. */
    public static final class Sample {
        public float albedoR, albedoG, albedoB;
        public float normalX, normalY, normalZ;
        public float roughness;
        /** Fractional strength of optional fine material detail; defaults to enabled. */
        public float detailWeight = 1f;
        public int semanticId;
    }

    /** Supplies material values for a canonical integer texel cell. */
    public interface Source {
        void sample(long x, long z, Sample store);
    }

    private static final class Level {
        final int interior;
        final int side;
        final float[] albedo;
        final float[] normalMoment;
        final float[] roughnessSquared;
        final float[] detailWeight;
        final byte[] semantic;

        Level(int interior) {
            this.interior = interior;
            this.side = interior + 2 * GUTTER;
            int count = Math.multiplyExact(side, side);
            albedo = new float[Math.multiplyExact(count, 3)];
            normalMoment = new float[Math.multiplyExact(count, 3)];
            roughnessSquared = new float[count];
            detailWeight = new float[count];
            semantic = new byte[count];
        }
        int index(int x, int z) {
            if (x < -GUTTER || x >= interior + GUTTER
                    || z < -GUTTER || z >= interior + GUTTER) {
                throw new IndexOutOfBoundsException("coordinate outside surface level gutter");
            }
            return (z + GUTTER) * side + x + GUTTER;
        }
    }

    private final Revision revision;
    private final long originX, originZ;
    private final int resolution, baseStride;
    private final Level[] levels;
    private final long sourceSampleCount;
    private final long encodedByteCount;
    private final long cpuArrayByteCount;

    public HeightfieldSurfacePage(Revision revision, long originX, long originZ,
            int resolution, int baseStride, int levelCount, Source source) {
        if (revision == null || source == null) {
            throw new IllegalArgumentException("revision and source are required");
        }
        if (resolution < 8 || resolution > 64 || (resolution & (resolution - 1)) != 0) {
            throw new IllegalArgumentException("resolution must be a power of two from 8 through 64");
        }
        if (baseStride < 1 || baseStride > 8 || (baseStride & (baseStride - 1)) != 0) {
            throw new IllegalArgumentException("base stride must be a power of two from 1 through 8");
        }
        int maxLevels = 1;
        while ((resolution >> maxLevels) >= 1
                && ((long) baseStride << maxLevels) <= MAX_FILTER_FOOTPRINT) {
            maxLevels++;
        }
        maxLevels = Math.min(maxLevels, 4);
        if (levelCount < 1 || levelCount > maxLevels) {
            throw new IllegalArgumentException("level count exceeds interior or filter footprint limits");
        }
        long pageExtent = Math.multiplyExact((long) resolution, baseStride);
        if (Math.floorMod(originX, pageExtent) != 0 || Math.floorMod(originZ, pageExtent) != 0) {
            throw new IllegalArgumentException("page origins must align to the canonical page extent");
        }
        long largestFootprint = (long) baseStride << (levelCount - 1);
        long gutterExtent = Math.multiplyExact((long) GUTTER, largestFootprint);
        long maxSourceOffset = Math.addExact(pageExtent, gutterExtent);
        Math.addExact(originX, -gutterExtent);
        Math.addExact(originZ, -gutterExtent);
        Math.addExact(originX, maxSourceOffset - 1);
        Math.addExact(originZ, maxSourceOffset - 1);

        this.revision = revision;
        this.originX = originX;
        this.originZ = originZ;
        this.resolution = resolution;
        this.baseStride = baseStride;
        this.levels = new Level[levelCount];
        Sample sample = new Sample();
        long samples = 0;
        long encodedBytes = 0;
        long arrayBytes = 0;
        for (int mip = 0; mip < levelCount; mip++) {
            int interior = resolution >> mip;
            Level level = new Level(interior);
            levels[mip] = level;
            int footprint = baseStride << mip;
            for (int z = -GUTTER; z < interior + GUTTER; z++) {
                for (int x = -GUTTER; x < interior + GUTTER; x++) {
                    long startX = Math.addExact(originX, Math.multiplyExact((long) x, footprint));
                    long startZ = Math.addExact(originZ, Math.multiplyExact((long) z, footprint));
                    double ar = 0;
                    double ag = 0;
                    double ab = 0;
                    double nx = 0;
                    double ny = 0;
                    double nz = 0;
                    double rr = 0;
                    double detail = 0;
                    long area = Math.multiplyExact((long) footprint, footprint);
                    int centerOffset = (footprint - 1) / 2;
                    int semanticId = -1;
                    for (int dz = 0; dz < footprint; dz++) {
                        long cz = Math.addExact(startZ, dz);
                        for (int dx = 0; dx < footprint; dx++) {
                            long cx = Math.addExact(startX, dx);
                            // Legacy sources need not know this channel. Reset it
                            // because one mutable Sample is reused for every call.
                            sample.detailWeight = 1f;
                            source.sample(cx, cz, sample);
                            validate(sample);
                            ar += sample.albedoR;
                            ag += sample.albedoG;
                            ab += sample.albedoB;
                            nx += sample.normalX;
                            ny += sample.normalY;
                            nz += sample.normalZ;
                            rr += (double) sample.roughness * sample.roughness;
                            detail += sample.detailWeight;
                            if (dx == centerOffset && dz == centerOffset) {
                                semanticId = sample.semanticId;
                            }
                            samples = Math.addExact(samples, 1);
                        }
                    }
                    int index = level.index(x, z);
                    int c = index * 3;
                    level.albedo[c] = (float) (ar / area);
                    level.albedo[c + 1] = (float) (ag / area);
                    level.albedo[c + 2] = (float) (ab / area);
                    level.normalMoment[c] = (float) (nx / area);
                    level.normalMoment[c + 1] = (float) (ny / area);
                    level.normalMoment[c + 2] = (float) (nz / area);
                    level.roughnessSquared[index] = (float) (rr / area);
                    level.detailWeight[index] = (float) (detail / area);
                    level.semantic[index] = (byte) semanticId;
                }
            }
            long texels = (long) level.side * level.side;
            encodedBytes = Math.addExact(encodedBytes, Math.multiplyExact(texels, 8L + 16L + 1L));
            arrayBytes = Math.addExact(arrayBytes, Math.multiplyExact(texels,
                    CPU_BYTES_PER_TEXEL));
        }
        sourceSampleCount = samples;
        encodedByteCount = encodedBytes;
        cpuArrayByteCount = arrayBytes;
    }

    private static void validate(Sample s) {
        if (!unit(s.albedoR) || !unit(s.albedoG) || !unit(s.albedoB)
                || !Float.isFinite(s.normalX) || !Float.isFinite(s.normalY) || !Float.isFinite(s.normalZ)
                || !Float.isFinite(s.roughness) || s.roughness < 0 || s.roughness > 1
                || !unit(s.detailWeight)
                || s.semanticId < 0 || s.semanticId > 255) {
            throw new IllegalArgumentException("source sample is outside the surface value ranges");
        }
        double length = Math.sqrt((double) s.normalX * s.normalX + (double) s.normalY * s.normalY + (double) s.normalZ * s.normalZ);
        if (!Double.isFinite(length) || Math.abs(length - 1.0) > 1e-4) {
            throw new IllegalArgumentException("source tangent normal must be unit length");
        }
    }
    private static boolean unit(float x) {
        return Float.isFinite(x) && x >= 0 && x <= 1;
    }

    public Revision getRevision() {
        return revision;
    }

    public long getOriginX() {
        return originX;
    }

    public long getOriginZ() {
        return originZ;
    }

    public int getResolution() {
        return resolution;
    }

    public int getBaseStride() {
        return baseStride;
    }

    public int getLevelCount() {
        return levels.length;
    }

    public int getInteriorSize(int mip) {
        return level(mip).interior;
    }

    public int getTextureSize(int mip) {
        return level(mip).side;
    }

    public long getSourceSampleCount() {
        return sourceSampleCount;
    }

    /** Returns bytes occupied by the owned float and byte arrays. */
    public long getCpuArrayByteCount() {
        return cpuArrayByteCount;
    }

    /** Returns bytes in the three encoded Image formats for every logical level. */
    public long getEncodedByteCount() {
        return encodedByteCount;
    }

    public float getAlbedo(int mip, int x, int z, int channel) {
        if (channel < 0 || channel > 2) {
            throw new IndexOutOfBoundsException("albedo channel");
        }
        Level l = level(mip);
        return l.albedo[l.index(x, z) * 3 + channel];
    }

    public float getNormalMoment(int mip, int x, int z, int channel) {
        if (channel < 0 || channel > 2) {
            throw new IndexOutOfBoundsException("normal channel");
        }
        Level l = level(mip);
        return l.normalMoment[l.index(x, z) * 3 + channel];
    }

    public float getRoughnessSquared(int mip, int x, int z) {
        Level l = level(mip);
        return l.roughnessSquared[l.index(x, z)];
    }

    /** Returns the filtered optional-detail weight in the inclusive range [0, 1]. */
    public float getDetailWeight(int mip, int x, int z) {
        Level l = level(mip);
        return l.detailWeight[l.index(x, z)];
    }

    public int getSemanticId(int mip, int x, int z) {
        Level l = level(mip);
        return l.semantic[l.index(x, z)] & 0xff;
    }

    private Level level(int mip) {
        if (mip < 0 || mip >= levels.length) {
            throw new IndexOutOfBoundsException("mip level");
        }
        return levels[mip];
    }

    /** Creates linear RGBA16F albedo RGB and continuous detail weight in alpha. */
    public Image createAlbedoImage(int mip) {
        Level l = level(mip);
        ByteBuffer data = BufferUtils.createByteBuffer(l.side * l.side * 8).order(ByteOrder.nativeOrder());
        for (int i = 0; i < l.side * l.side; i++) {
            data.putShort(FastMath.convertFloatToHalf(l.albedo[i * 3]));
            data.putShort(FastMath.convertFloatToHalf(l.albedo[i * 3 + 1]));
            data.putShort(FastMath.convertFloatToHalf(l.albedo[i * 3 + 2]));
            data.putShort(FastMath.convertFloatToHalf(l.detailWeight[i]));
        }
        data.flip();
        return new Image(Image.Format.RGBA16F, l.side, l.side, data, ColorSpace.Linear);
    }
    /** Creates independent linear RGBA32F normal moment and roughness-squared storage. */
    public Image createNormalImage(int mip) {
        Level l = level(mip);
        ByteBuffer data = BufferUtils.createByteBuffer(l.side * l.side * 16).order(ByteOrder.nativeOrder());
        for (int i = 0; i < l.side * l.side; i++) {
            data.putFloat(l.normalMoment[i * 3]);
            data.putFloat(l.normalMoment[i * 3 + 1]);
            data.putFloat(l.normalMoment[i * 3 + 2]);
            data.putFloat(l.roughnessSquared[i]);
        }
        data.flip();
        return new Image(Image.Format.RGBA32F, l.side, l.side, data, ColorSpace.Linear);
    }
    /** Creates independent single-channel semantic ID storage for one logical level. */
    public Image createSemanticImage(int mip) {
        Level l = level(mip);
        ByteBuffer data = BufferUtils.createByteBuffer(l.side * l.side);
        data.put(l.semantic).flip();
        return new Image(Image.Format.Luminance8, l.side, l.side, data, ColorSpace.Linear);
    }
}
