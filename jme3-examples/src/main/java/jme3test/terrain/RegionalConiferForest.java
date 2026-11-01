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
import com.jme3.asset.AssetManager;
import com.jme3.renderer.RenderManager;
import java.util.function.Consumer;
import jme3test.terrain.forest.ConiferImpostor;
import jme3test.terrain.forest.ConiferImpostorBaker;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;
import com.jme3.renderer.Renderer;
import com.jme3.scene.Node;
import com.jme3.terrain.heightfield.HeightfieldHierarchy;
import com.jme3.terrain.heightfield.HeightfieldLod.Patch;
import com.jme3.terrain.heightfield.HeightfieldPage;
import com.jme3.terrain.heightfield.HeightfieldSurfaceSampler;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import jme3test.terrain.forest.InstancedConifers;
import jme3test.terrain.forest.OriginalConifer;

/** Bounded decorative groves attached to the currently displayed terrain surface. */
final class RegionalConiferForest implements AutoCloseable {
    private static final float SPACING = RegionalHeightfieldSource.SPACING;
    private static final float NEAR_DISTANCE_SQUARED = 140f * 140f;
    private static final int PAGE_BUILDS_PER_UPDATE = 4;
    private final InstancedConifers instances;
    private final OriginalConifer.Asset asset;
    private final Renderer renderer;
    private final Node parent;
    private final Vector3f cameraDirection = new Vector3f();
    private final HeightfieldSurfaceSampler sampler = new HeightfieldSurfaceSampler();
    // Fine source XZ, scale, cosine, sine and tint. Source revision changes only
    // suitability admission, never the seeded position or random identity.
    private final float[] sites;
    private final float[] packed;
    private final float[] weights;
    private final int[] lods;
    private final HeightfieldPage[] pages;
    private int[] patchAtCell;
    private int siteCount, sourceCells, lookupSide;
    private long siteRevision = -1, queries, updateNanos, maximumUpdateNanos;
    private float previewHeight, worldOrigin;
    private boolean closed;
    private ConiferImpostorBaker baker;
    private final RenderManager manager;
    private final boolean farMode;
    private final float siteStep;
    private final int maxTrees, pageBudget;
    private RegionalForestPages placement;
    private RegionalForestDemand demand;
    private long copiedLoads = -1, copiedEvictions = -1, rootCacheHits;
    private int pendingPlacement, maxPageBuilds;
    private boolean preservePreviousPlacement;
    private long admissionRevision = -1;
    private final HeightfieldHierarchy.Node[] rootNodes;
    private final float[] rootHeights, rootMorphs;
    private long bakeNanos;
    private int bakeCalls;
    private final AssetManager assets;
    private final Material template;
    private final Consumer<Material> addReceiver;
    private ConiferImpostor farAsset;
    private int farCount, transitionCopies, peakFarCount, peakTransitionCopies, atlasReadyUpdate;
    private long bakeStarted;
    private double bakeWallMs;
    private long activeInstanceTriangles;
    private int updates, nearCount, coarseHidden, revisionBuilds, peakGroups;
    private final long staticMeshBytes, frondMipBytes;

    RegionalConiferForest(RenderManager manager, AssetManager assets, Node parent, Material template,
            int pageCapacity, boolean farMode, float siteStep, int pageBudget, Consumer<Material> addReceiver) {
        this.manager = manager;
        this.farMode = farMode;
        this.siteStep = siteStep;
        this.pageBudget = pageBudget;
        maxTrees = siteStep < 14 ? 8192 : 2048;
        sites = new float[maxTrees * 6];
        packed = new float[maxTrees * 2 * 7];
        weights = new float[maxTrees * 2];
        lods = new int[maxTrees * 2];
        rootNodes = new HeightfieldHierarchy.Node[maxTrees];
        rootHeights = new float[maxTrees];
        rootMorphs = new float[maxTrees * 5];
        this.renderer = manager.getRenderer();
        this.assets = assets;
        this.template = template;
        this.addReceiver = addReceiver;
        this.parent = parent;
        asset = OriginalConifer.build(20261031L);
        frondMipBytes = template.getTextureParam("FrondTexture").getTextureValue().getImage().getData(0).capacity();
        staticMeshBytes = meshBytes(asset.getNearMesh()) + meshBytes(asset.getMidMesh());
        instances = new InstancedConifers(renderer, asset, template, parent, maxTrees * 2);
        pages = new HeightfieldPage[pageCapacity];
    }

    Material getMaterial() { return instances.getMaterial(); }
    Material getFarMaterial() { return instances.getFarMaterial(); }

    void update(RegionalHeightfieldSource source, HeightfieldHierarchy hierarchy, long revision,
            List<Patch> patches, Function<HeightfieldHierarchy.Node, HeightfieldPage> pageOf, Camera camera, boolean coverSettled) {
        long begin = System.nanoTime();
        if (siteRevision != revision || sourceCells != source.getCells()) resetPlacement(source, revision);
        advancePlacement(source, hierarchy, camera);
        long bakeElapsed = 0;
        if (farMode && farAsset == null && siteCount > 0) {
            long bakeBegin = System.nanoTime();
            if (baker == null) {
                baker = new ConiferImpostorBaker(manager, assets, asset, template);
                bakeStarted = System.nanoTime();
            }
            if (baker.update()) {
                farAsset = new ConiferImpostor(renderer, assets, template,
                        baker.getColorFrames(), baker.getNormalDepthFrames());
                instances.installImpostor(farAsset);
                atlasReadyUpdate = updates;
                addReceiver.accept(instances.getFarMaterial());
                bakeWallMs = (System.nanoTime() - bakeStarted) / 1e6;
                System.out.println("HEIGHTFIELD_FOREST_BAKE_PASS logicalBytes=" + baker.getLogicalBytes()
                        + " metadataBytes=" + farAsset.getMetadataBytes() + " wallMs=" + bakeWallMs);
            }
            bakeElapsed = System.nanoTime() - bakeBegin;
            bakeNanos += bakeElapsed;
            bakeCalls++;
        }
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
        nearCount = coarseHidden = farCount = transitionCopies = 0;
        activeInstanceTriangles = 0;
        float origin = source.getWorldOrigin();
        Vector3f direction = camera.getDirection(cameraDirection);
        Vector3f eye = camera.getLocation();
        int viewportHeight = camera.getHeight();
        float projectionY = Math.abs(camera.getProjectionMatrix().m11);
        float sunUp = -((Vector3f) template.getParam("SunDirection").getValue()).y;
        float readyFraction = (updates - atlasReadyUpdate) / 20f;
        boolean parallel = camera.isParallelProjection();
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
            float y = groundHeight(i, patch, pages[index], fineX, fineZ);
            if (!Float.isFinite(y)) throw new IllegalStateException("forest root height must be finite");
            float x = fineX * SPACING, z = fineZ * SPACING;
            float dx = x + origin - eye.x;
            float dy = y - eye.y;
            float dz = z + origin - eye.z;
            int lod = dx * dx + dy * dy + dz * dz < NEAR_DISTANCE_SQUARED ? 0 : 1;
            float meshWeight = 1;
            if (farAsset != null) {
                float depth = dx * direction.x + dy * direction.y + dz * direction.z;
                float pixels = 18 * sites[offset + 2] * viewportHeight * projectionY * .5f;
                if (!parallel) pixels /= Math.max(1, depth);
                float centerDy = dy + 9 * sites[offset + 2];
                float distance = (float) Math.sqrt(dx * dx + centerDy * centerDy + dz * dz);
                float observerUp = distance > 0 ? -centerDy / distance : 0;
                meshWeight = ConiferImpostor.meshWeight(pixels, distance / sites[offset + 2], observerUp,
                        sunUp, readyFraction);
            }
            if (meshWeight > 0) {
                append(count++, offset, x, y, z, lod, meshWeight);
                if (lod == 0) nearCount++;
            }
            if (meshWeight < 1) {
                append(count++, offset, x, y, z, 2, 1 - meshWeight);
                farCount++;
                if (meshWeight > 0) transitionCopies++;
            }
        }
        if (farAsset == null) instances.update(packed, lods, count);
        else instances.update(packed, lods, weights, count);
        peakGroups = Math.max(peakGroups, instances.getGroupCount());
        peakFarCount = Math.max(peakFarCount, farCount);
        peakTransitionCopies = Math.max(peakTransitionCopies, transitionCopies);
        long elapsed = System.nanoTime() - begin - bakeElapsed;
        updateNanos += elapsed;
        maximumUpdateNanos = Math.max(maximumUpdateNanos, elapsed);
        updates++;
    }

    private void append(int count, int siteOffset, float x, float y, float z, int lod, float weight) {
        activeInstanceTriangles += lod == 2 ? 2 : lod == 0
                ? asset.getNearMesh().getTriangleCount() : asset.getMidMesh().getTriangleCount();
        int out = count * 7;
        packed[out] = x; packed[out + 1] = y; packed[out + 2] = z;
        System.arraycopy(sites, siteOffset + 2, packed, out + 3, 4);
        lods[count] = lod;
        weights[count] = weight;
    }

    private void resetPlacement(RegionalHeightfieldSource source, long revision) {
        preservePreviousPlacement = siteCount > 0 && sourceCells == source.getCells();
        if (sourceCells != source.getCells()) siteCount = 0;
        Arrays.fill(rootNodes, null);
        sourceCells = source.getCells();
        siteRevision = revision;
        worldOrigin = source.getWorldOrigin();
        placement = new RegionalForestPages(source, siteStep, pageBudget, maxTrees);
        demand = new RegionalForestDemand(placement.getPageCountPerAxis());
        copiedLoads = copiedEvictions = -1;
        revisionBuilds++;
        System.out.println("HEIGHTFIELD_FOREST_PAGER revision=" + revision + " spacing=" + siteStep
                + " pageBudget=" + pageBudget + " treeBudget=" + maxTrees);
    }

    private void advancePlacement(RegionalHeightfieldSource source, HeightfieldHierarchy hierarchy, Camera camera) {
        RegionalForestDemand.Cell[] order = demand.update(camera, source.getWorldOrigin(),
                hierarchy.getRoot().getMetadata().getMinimumHeight(), hierarchy.getRoot().getMetadata().getMaximumHeight(),
                (Vector3f) template.getParam("SunDirection").getValue());
        placement.beginDemand();
        long before = placement.getLoads();
        // A priority-ordered pass protects higher-ranked cells first. A new
        // close cell may evict a lower-ranked cached cell, even if both are
        // technically camera-visible. Cached unrequested cells remain casters.
        for (RegionalForestDemand.Cell cell : order) {
            if (!placement.mayContainTrees(cell.x, cell.z)) continue;
            if (placement.peek(cell.x, cell.z) == null
                    && placement.getLoads() - before >= PAGE_BUILDS_PER_UPDATE) continue;
            placement.request(cell.x, cell.z);
        }
        maxPageBuilds = Math.max(maxPageBuilds, (int) (placement.getLoads() - before));
        pendingPlacement = 0;
        for (RegionalForestDemand.Cell cell : order) {
            if (placement.mayContainTrees(cell.x, cell.z) && placement.peek(cell.x, cell.z) == null) pendingPlacement++;
        }
        // Keep the previous eligibility layout while a new revision prepares.
        // Roots already sample the newly displayed terrain; eligibility swaps
        // atomically when replacement pages are ready (or explicitly limited).
        if ((!preservePreviousPlacement || pendingPlacement == 0 || placement.getBudgetLimitedCount() > 0)
                && (copiedLoads != placement.getLoads() || copiedEvictions != placement.getEvictions())) {
            siteCount = 0;
            // Retain cached off-camera cells as real shadow casters. Only pages
            // evicted by the explicit budget leave the scene.
            placement.forEachResidentPage(page -> {
                page.copyRecords(sites, siteCount * 6);
                siteCount += page.getTreeCount();
            });
            Arrays.fill(rootNodes, null);
            admissionRevision = siteRevision;
            preservePreviousPlacement = false;
            copiedLoads = placement.getLoads(); copiedEvictions = placement.getEvictions();
            if (siteCount > 0) previewHeight = source.pageSamples(2).getHeight((int) sites[0], (int) sites[1]);
        }
    }

    private float groundHeight(int site, Patch patch, HeightfieldPage page, float fineX, float fineZ) {
        int offset = site * 5;
        if (rootNodes[site] == patch.getNode() && rootMorphs[offset] == patch.getMorph()
                && rootMorphs[offset + 1] == patch.getEdgeMorph(0)
                && rootMorphs[offset + 2] == patch.getEdgeMorph(1)
                && rootMorphs[offset + 3] == patch.getEdgeMorph(2)
                && rootMorphs[offset + 4] == patch.getEdgeMorph(3)) {
            rootCacheHits++;
            return rootHeights[site];
        }
        float height = (float) sampler.height(patch, page, fineX, fineZ);
        rootNodes[site] = patch.getNode(); rootHeights[site] = height;
        rootMorphs[offset] = patch.getMorph();
        for (int edge = 0; edge < 4; edge++) rootMorphs[offset + edge + 1] = patch.getEdgeMorph(edge);
        queries++;
        return height;
    }

    int getInstanceCount() { return instances.getInstanceCount(); }
    int getSiteCount() { return siteCount; }
    Vector3f previewFocus(Vector3f store) {
        return siteCount == 0 ? null : store.set(sites[0] * SPACING + worldOrigin,
                previewHeight, sites[1] * SPACING + worldOrigin);
    }
    long getRevision() { return siteRevision; }
    void printStats() {
        if (farMode && farAsset == null && siteCount > 0) throw new IllegalStateException("far atlas did not finish");
        if (maxPageBuilds > PAGE_BUILDS_PER_UPDATE || placement.getResidentPages() > pageBudget
                || placement.getResidentTrees() > maxTrees) throw new IllegalStateException("placement budget exceeded");
        System.out.println("HEIGHTFIELD_FOREST_PAGING_PASS pages=" + placement.getResidentPages()
                + " trees=" + placement.getResidentTrees() + " pageLimit=" + pageBudget + " treeLimit=" + maxTrees
                + " payloadBytes=" + placement.getPrimitivePayloadBytes() + " loads=" + placement.getLoads()
                + " evictions=" + placement.getEvictions() + " pending=" + pendingPlacement
                + " budgetLimited=" + placement.getBudgetLimitedCount() + " maxBuilds=" + maxPageBuilds
                + " admissionRevision=" + admissionRevision + " rootCacheHits=" + rootCacheHits + " bakeMeanMs=" + bakeNanos / 1e6 / Math.max(1,bakeCalls));
        System.out.println("HEIGHTFIELD_FOREST_FAR_PASS enabled=" + farMode + " trees=" + farCount
                + " transitionCopies=" + transitionCopies + " peakFar=" + peakFarCount
                + " peakTransitionCopies=" + peakTransitionCopies + " activeInstanceTriangles=" + activeInstanceTriangles + " textureBytes=" + (baker == null ? 0 : baker.getLogicalBytes()));
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
        if (farAsset != null) farAsset.close();
        if (baker != null) baker.close();
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
}
