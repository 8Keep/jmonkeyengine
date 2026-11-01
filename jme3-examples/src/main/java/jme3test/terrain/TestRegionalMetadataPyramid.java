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
 * * Redistributions in binary form must reproduce the above copyright
 *   notice, this list of conditions and the following disclaimer in the
 *   documentation and/or other materials provided with the distribution.
 * * Neither the name of 'jMonkeyEngine' nor the names of its contributors may
 *   be used to endorse or promote products derived from this software
 *   without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDER AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package jme3test.terrain;

import com.jme3.terrain.heightfield.HeightfieldHierarchy;
import com.jme3.terrain.heightfield.HeightfieldReconstruction;
import com.jme3.terrain.heightfield.HeightfieldHierarchy.Node;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;

/** Finite headless checks for conservative connected-world metadata. */
public final class TestRegionalMetadataPyramid {
    private static final int PATCH_CELLS = 64;
    private static final int EXPECTED_NODES = 21845;

    private TestRegionalMetadataPyramid() {
    }

    public static void main(String[] args) {
        for (long revision : new long[]{0, 1}) verifyRevision(revision);
        verifyNodeCountLimit();
        verifyPromptCancellation();
        System.out.println("REGIONAL_METADATA_PYRAMID_PASS nodesPerRevision=" + EXPECTED_NODES
                + " revisions=2 measuredReferencePatches=7 denseWorldScan=false");
    }

    private static void verifyRevision(long revision) {
        RegionalHeightfieldSource source = new RegionalHeightfieldSource(
                RegionalHeightfieldSource.FAR_CELLS, revision);
        HeightfieldHierarchy hierarchy = RegionalMetadataPyramid.build(source, PATCH_CELLS);
        require(hierarchy.getCells() == RegionalHeightfieldSource.FAR_CELLS,
                "metadata hierarchy uses the four-kilometre lattice");
        require(hierarchy.getSourceRevision() == revision, "metadata revision is preserved");
        require(hierarchy.getNodes().size() == EXPECTED_NODES,
                "64-cell four-kilometre hierarchy has exactly 21845 nodes");

        Map<String, Node> nodes = new HashMap<>(EXPECTED_NODES * 2);
        for (Node node : hierarchy.getNodes()) {
            nodes.put(key(node.getOriginX(), node.getOriginZ(), node.getSpan()), node);
            require(Float.isFinite(node.getMetadata().getMinimumHeight())
                            && Float.isFinite(node.getMetadata().getMaximumHeight())
                            && Double.isFinite(node.getMetadata().getStableSurfaceError())
                            && Double.isFinite(node.getMetadata().getParentSurfaceError()),
                    "all metadata fields are finite");
            if (node.getParent() != null) {
                require(node.getMetadata().getMinimumHeight() >= node.getParent().getMetadata().getMinimumHeight()
                                && node.getMetadata().getMaximumHeight() <= node.getParent().getMetadata().getMaximumHeight(),
                        "child range stays inside its ancestor range");
            }
        }

        // One representative node from the original legacy patch, the active
        // blend boundary, and a pure-Gaussian region. Each dense check is local.
        Node legacy = find(nodes, 4096, 4096, 64);
        Node edit = find(nodes, 4608, 4480, 64);
        Node blend = find(nodes, 3584, 4096, 64);
        Node pureFar = find(nodes, 0, 0, 256);
        require(source.getFarTerrain().bounds(4096, 4096, 64, 1) == null,
                "legacy-area metadata uses measured nested surfaces");
        require(source.getFarTerrain().bounds(3584, 4096, 64, 1) == null,
                "blend-boundary metadata uses measured nested surfaces");
        require(source.getFarTerrain().bounds(0, 0, 256, 4) != null,
                "aligned pure far node uses analytic Gaussian metadata");

        verifyNode(source, legacy);
        verifyNode(source, edit);
        verifyNode(source, blend);
        verifyNode(source, pureFar);
        verifyNode(source, find(nodes, 4096, 4096, 128));
        verifyNode(source, find(nodes, 3584, 4096, 128));
        verifyNode(source, find(nodes, 0, 0, 128));

        require(source.getCachedFloatBytes() <= RegionalHeightfieldSource.maximumCacheBytes(
                        RegionalHeightfieldSource.FAR_CELLS),
                "fine source tile cache remains within its bounded budget");
        System.out.println("REGIONAL_METADATA_REFERENCE revision=" + revision
                + " checked=7 cachedFineBytes=" + source.getCachedFloatBytes());
    }

    private static void verifyNode(RegionalHeightfieldSource source, Node node) {
        int x = node.getOriginX(), z = node.getOriginZ(), span = node.getSpan();
        int stride = node.getStride();
        float minimum = Float.POSITIVE_INFINITY;
        float maximum = Float.NEGATIVE_INFINITY;
        for (int j = z; j <= z + span; j++) {
            for (int i = x; i <= x + span; i++) {
                float height = source.getHeight(i, j);
                minimum = Math.min(minimum, height);
                maximum = Math.max(maximum, height);
            }
        }
        require(minimum >= node.getMetadata().getMinimumHeight()
                        && maximum <= node.getMetadata().getMaximumHeight(),
                "node height metadata contains its dense canonical samples at " + x + "," + z + "," + span);

        double exactStable = HeightfieldReconstruction.maximumError(source, x, z, span, stride);
        require(exactStable <= node.getMetadata().getStableSurfaceError() + 1.0e-8,
                "stable metadata bounds dense nested P1 error at " + x + "," + z + "," + span
                        + " exact=" + exactStable + " bound=" + node.getMetadata().getStableSurfaceError());
        if (node.getParent() != null) {
            double exactParent = HeightfieldReconstruction.maximumError(source, x, z,
                    span, stride * 2);
            require(exactParent <= node.getMetadata().getParentSurfaceError() + 1.0e-8,
                    "parent metadata bounds dense nested P1 error at " + x + "," + z + "," + span
                            + " exact=" + exactParent + " bound=" + node.getMetadata().getParentSurfaceError());
        } else {
            require(node.getMetadata().getParentSurfaceError() == 0,
                    "root parent error remains unused zero");
        }
    }

    private static void verifyPromptCancellation() {
        RegionalHeightfieldSource source = new RegionalHeightfieldSource(
                RegionalHeightfieldSource.FAR_CELLS, 0);
        Thread.currentThread().interrupt();
        boolean cancelled = false;
        try {
            RegionalMetadataPyramid.build(source, PATCH_CELLS);
        } catch (CancellationException expected) {
            cancelled = true;
        } finally {
            Thread.interrupted();
        }
        require(cancelled, "metadata construction observes worker interruption before scanning");
    }

    private static void verifyNodeCountLimit() {
        RegionalHeightfieldSource source = new RegionalHeightfieldSource(
                RegionalHeightfieldSource.FAR_CELLS, 0);
        boolean rejected = false;
        try {
            RegionalMetadataPyramid.build(source, 2);
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        require(rejected, "patch size two is rejected by the hierarchy node-count limit");
        require(source.getHeightRequestCount() == 0,
                "oversized hierarchy is rejected before source sampling or draft allocation");
    }

    private static Node find(Map<String, Node> nodes, int x, int z, int span) {
        Node result = nodes.get(key(x, z, span));
        if (result == null) throw new AssertionError("missing hierarchy node " + x + "," + z + "," + span);
        return result;
    }

    private static String key(int x, int z, int span) {
        return x + ":" + z + ":" + span;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
