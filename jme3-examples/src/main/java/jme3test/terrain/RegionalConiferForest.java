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

import com.jme3.material.Material;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;
import com.jme3.renderer.Renderer;
import com.jme3.scene.Node;
import com.jme3.terrain.heightfield.HeightfieldHierarchy;
import com.jme3.terrain.heightfield.HeightfieldLod.Patch;
import com.jme3.terrain.heightfield.HeightfieldPage;
import com.jme3.terrain.heightfield.HeightfieldSource;
import com.jme3.terrain.heightfield.HeightfieldSurfaceSampler;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import jme3test.terrain.forest.InstancedConifers;
import jme3test.terrain.forest.OriginalConifer;

/** Bounded decorative groves attached to the currently displayed terrain surface. */
final class RegionalConiferForest implements AutoCloseable {
    private static final int MAX_TREES = 2048;
    private static final float SPACING = RegionalHeightfieldSource.SPACING;
    private static final float SITE_STEP = 14f;
    private static final float NEAR_DISTANCE_SQUARED = 140f * 140f;
    private static final float[] GROVES = {
        .17f, .22f, .34f, .55f, .64f, .26f, .80f, .72f,
        .42f, .84f, .72f, .48f, .20f, .78f, .53f, .42f
    };
    private final InstancedConifers instances;
    private final OriginalConifer.Asset asset;
    private final Renderer renderer;
    private final Node parent;
    private final HeightfieldSurfaceSampler sampler = new HeightfieldSurfaceSampler();
    // Fine source XZ, scale, cosine, sine and tint. Source revision changes only
    // suitability admission, never the seeded position or random identity.
    private final float[] sites = new float[MAX_TREES * 6];
    private final float[] packed = new float[MAX_TREES * 7];
    private final int[] lods = new int[MAX_TREES];
    private final HeightfieldPage[] pages;
    private int[] patchAtCell;
    private int siteCount, sourceCells, lookupSide;
    private long siteRevision = -1, queries, updateNanos, maximumUpdateNanos;
    private float previewHeight, worldOrigin;
    private boolean closed;
    private int updates, nearCount, coarseHidden, revisionBuilds, peakGroups;
    private final long staticMeshBytes, frondMipBytes;

    RegionalConiferForest(Renderer renderer, Node parent, Material template, int pageCapacity) {
        this.renderer = renderer;
        this.parent = parent;
        asset = OriginalConifer.build(20261031L);
        frondMipBytes = template.getTextureParam("FrondTexture").getTextureValue().getImage().getData(0).capacity();
        staticMeshBytes = meshBytes(asset.getNearMesh()) + meshBytes(asset.getMidMesh());
        instances = new InstancedConifers(renderer, asset, template, parent, MAX_TREES);
        pages = new HeightfieldPage[pageCapacity];
    }

    Material getMaterial() { return instances.getMaterial(); }

    void update(RegionalHeightfieldSource source, HeightfieldHierarchy hierarchy, long revision,
            List<Patch> patches, Function<HeightfieldHierarchy.Node, HeightfieldPage> pageOf, Camera camera, boolean coverSettled) {
        long begin = System.nanoTime();
        if (siteRevision != revision || sourceCells != source.getCells()) rebuildSites(source, revision);
        int side = hierarchy.getCells() / hierarchy.getPatchCells();
        if (side != lookupSide) {
            lookupSide = side;
            patchAtCell = new int[side * side];
        }
        Arrays.fill(patchAtCell, -1);
        Arrays.fill(pages, null);
        if (patches.size() > pages.length) throw new IllegalStateException("forest cover exceeds page budget");
        for (int i = 0; i < patches.size(); i++) {
            HeightfieldHierarchy.Node node = patches.get(i).getNode();
            pages[i] = pageOf.apply(node);
            if (pages[i] == null) throw new IllegalStateException("forest ground requires retained ready terrain");
            int x0 = node.getOriginX() / hierarchy.getPatchCells();
            int z0 = node.getOriginZ() / hierarchy.getPatchCells();
            int extent = node.getSpan() / hierarchy.getPatchCells();
            for (int z = z0; z < z0 + extent; z++) {
                Arrays.fill(patchAtCell, z * side + x0, z * side + x0 + extent, i);
            }
        }
        int count = 0;
        nearCount = coarseHidden = 0;
        float origin = source.getWorldOrigin();
        for (int i = 0; i < siteCount; i++) {
            int offset = i * 6;
            float fineX = sites[offset], fineZ = sites[offset + 1];
            int index = patchAtCell[(int) fineZ / hierarchy.getPatchCells() * side
                    + (int) fineX / hierarchy.getPatchCells()];
            if (index < 0) throw new IllegalStateException("forest point lies outside the complete displayed cover");
            Patch patch = patches.get(index);
            // Hide imprecise fallback until its display cover settles. An
            // accurate flat patch may remain coarse and still support trees.
            if (!coverSettled && patch.getNode().getMorphError() > OriginalConifer.HEIGHT * .25f) {
                coarseHidden++;
                continue;
            }
            float y = (float) sampler.height(patch, pages[index], fineX, fineZ);
            if (!Float.isFinite(y)) throw new IllegalStateException("forest root height must be finite");
            queries++;
            float x = fineX * SPACING, z = fineZ * SPACING;
            float dx = x + origin - camera.getLocation().x;
            float dy = y - camera.getLocation().y;
            float dz = z + origin - camera.getLocation().z;
            int lod = dx * dx + dy * dy + dz * dz < NEAR_DISTANCE_SQUARED ? 0 : 1;
            if (lod == 0) nearCount++;
            int out = count * 7;
            packed[out] = x; packed[out + 1] = y; packed[out + 2] = z;
            System.arraycopy(sites, offset + 2, packed, out + 3, 4);
            lods[count++] = lod;
        }
        instances.update(packed, lods, count);
        peakGroups = Math.max(peakGroups, instances.getGroupCount());
        long elapsed = System.nanoTime() - begin;
        updateNanos += elapsed;
        maximumUpdateNanos = Math.max(maximumUpdateNanos, elapsed);
        updates++;
    }

    private void rebuildSites(RegionalHeightfieldSource source, long revision) {
        sourceCells = source.getCells();
        siteCount = 0;
        float size = sourceCells * SPACING;
        float radius = Math.max(28f, size * .035f);
        float radius2 = radius * radius;
        HeightfieldSource samples = source.pageSamples(2); // Exact point view; do not thrash fine tile cache.
        int cells = (int) Math.ceil(size / SITE_STEP);
        for (int z = 0; z < cells && siteCount < MAX_TREES; z++) {
            for (int x = 0; x < cells && siteCount < MAX_TREES; x++) {
                long seed = mix(((long) x << 32) ^ (z & 0xffffffffL));
                if (unit(seed) > .60f) continue;
                float px = (x + .2f + .6f * unit(mix(seed + 1))) * SITE_STEP;
                float pz = (z + .2f + .6f * unit(mix(seed + 2))) * SITE_STEP;
                if (px < 4 || pz < 4 || px > size - 4 || pz > size - 4) continue;
                boolean inGrove = false;
                for (int g = 0; g < GROVES.length; g += 2) {
                    float dx = px - GROVES[g] * size, dz = pz - GROVES[g + 1] * size;
                    if (dx * dx + dz * dz < radius2) { inGrove = true; break; }
                }
                if (!inGrove) continue;
                int fx = Math.round(px / SPACING), fz = Math.round(pz / SPACING);
                float height = samples.getHeight(fx, fz);
                float slopeX = (samples.getHeight(fx + 2, fz) - samples.getHeight(fx - 2, fz)) / (4 * SPACING);
                float slopeZ = (samples.getHeight(fx, fz + 2) - samples.getHeight(fx, fz - 2)) / (4 * SPACING);
                // A simple original demo rule, not an ecological simulation.
                if (height < 12 || height > 185 || slopeX * slopeX + slopeZ * slopeZ > .65f * .65f) continue;
                if (siteCount == 0) previewHeight = height;
                int out = siteCount++ * 6;
                sites[out] = fx; sites[out + 1] = fz;
                sites[out + 2] = .8f + .35f * unit(mix(seed + 3));
                double yaw = unit(mix(seed + 4)) * Math.PI * 2;
                sites[out + 3] = (float) Math.cos(yaw);
                sites[out + 4] = (float) Math.sin(yaw);
                sites[out + 5] = .85f + .25f * unit(mix(seed + 5));
            }
        }
        siteRevision = revision;
        worldOrigin = source.getWorldOrigin();
        revisionBuilds++;
        System.out.println("HEIGHTFIELD_FOREST_SITES revision=" + revision + " cells=" + sourceCells
                + " candidates=" + siteCount + " limit=" + MAX_TREES + " seed=stable-world-cells");
    }

    int getInstanceCount() { return instances.getInstanceCount(); }
    int getSiteCount() { return siteCount; }
    Vector3f previewFocus(Vector3f store) {
        return siteCount == 0 ? null : store.set(sites[0] * SPACING + worldOrigin,
                previewHeight, sites[1] * SPACING + worldOrigin);
    }
    long getRevision() { return siteRevision; }
    void printStats() {
        System.out.printf(Locale.ROOT,
                "HEIGHTFIELD_FOREST_PASS revision=%d builds=%d sites=%d instances=%d near=%d hiddenCoarse=%d groups=%d peakGroups=%d groundQueries=%d updateMeanMs=%.3f updateMaxMs=%.3f instanceBufferBytes=%d staticMeshBytes=%d sharedFrondMipBytes=%d%n",
                siteRevision, revisionBuilds, siteCount, instances.getInstanceCount(), nearCount, coarseHidden,
                instances.getGroupCount(), peakGroups, queries, updateNanos / 1e6 / Math.max(1, updates),
                maximumUpdateNanos / 1e6, instances.getInstanceBufferBytes(), staticMeshBytes, frondMipBytes);
    }
    void validateWorldOrigin(float origin) {
        for (com.jme3.scene.Spatial child : parent.getChildren()) {
            if (!child.getName().startsWith("conifer-cell-")) continue;
            com.jme3.math.Vector3f world = child.getWorldTranslation();
            if (world.x != origin || world.y != 0 || world.z != origin) {
                throw new IllegalStateException("forest instances must apply the source world origin exactly once");
            }
        }
    }
    @Override public void close() {
        if (closed) return;
        closed = true;
        instances.close();
        // This controller created these two static assets. Groups merely borrow
        // them, and all opaque/caster users have drained before this close.
        releaseMesh(asset.getNearMesh());
        releaseMesh(asset.getMidMesh());
    }
    private void releaseMesh(com.jme3.scene.Mesh mesh) {
        for (com.jme3.scene.VertexBuffer buffer : mesh.getBufferList().getArray()) {
            renderer.deleteBuffer(buffer);
            com.jme3.util.BufferUtils.destroyDirectBuffer(buffer.getData());
        }
    }
    private static long meshBytes(com.jme3.scene.Mesh mesh) {
        long bytes = 0;
        for (com.jme3.scene.VertexBuffer buffer : mesh.getBufferList().getArray()) {
            bytes += (long) buffer.getData().capacity() * buffer.getFormat().getComponentSize();
        }
        return bytes;
    }
    private static float unit(long value) { return (float) ((value >>> 40) * 0x1.0p-24); }
    private static long mix(long x) {
        x = (x ^ (x >>> 30)) * 0xbf58476d1ce4e5b9L;
        x = (x ^ (x >>> 27)) * 0x94d049bb133111ebL;
        return x ^ (x >>> 31);
    }
}
