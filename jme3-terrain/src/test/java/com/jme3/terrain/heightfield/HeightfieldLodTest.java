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

import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;
import com.jme3.terrain.heightfield.HeightfieldHierarchy.Node;
import com.jme3.terrain.heightfield.HeightfieldLod.Patch;
import com.jme3.terrain.heightfield.HeightfieldLod.Selection;
import com.jme3.terrain.heightfield.HeightfieldLod.Transition;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeightfieldLodTest {
    private static final int CELLS = 16;
    private static final int PATCH_CELLS = 4;

    @Test void selectedLeavesCoverThePageOnceAndBalanceEdgesAndCorners() {
        HeightfieldHierarchy hierarchy = spikeHierarchy();
        HeightfieldLod lod = new HeightfieldLod(hierarchy);
        Camera camera = camera();
        Node hotChild = hierarchy.getNodes().stream()
                .filter(n -> n.getOriginX() == 0 && n.getOriginZ() == 0 && n.getSpan() == CELLS / 2)
                .findFirst().orElseThrow(AssertionError::new);
        double rootPixels = HeightfieldProjection.pixels(camera, hierarchy.getRoot().getBound(),
                hierarchy.getRoot().getStableError());
        double hotPixels = HeightfieldProjection.pixels(camera, hotChild.getBound(), hotChild.getStableError());
        assertTrue(rootPixels > 0 && hotPixels > 0);
        // One isolated fine spike makes the northwest child alone exceed this limit.
        double threshold = Math.min(rootPixels, hotPixels) * .5;
        Selection mixed = lod.select(camera, threshold, 1000, node -> true);
        assertTrue(mixed.getLeaves().stream().map(Node::getLevel).distinct().count() > 1,
                "isolated spike should produce a mixed-level cover");
        assertCoverAndBalance(mixed.getLeaves(), CELLS);
        assertTrue(mixed.getLeaves().stream().anyMatch(a -> mixed.getLeaves().stream()
                .anyMatch(b -> touches(a, b) && Math.abs(a.getLevel() - b.getLevel()) == 1)),
                "fixture should exercise a 2:1 neighbor");
        assertMaximumMatchesNodes(mixed, camera);
    }

    @Test void readinessBudgetAndSourceLimitsReportUnmetQuality() {
        Camera camera = camera();
        HeightfieldHierarchy hierarchy = hierarchy(.25);
        HeightfieldLod lod = new HeightfieldLod(hierarchy);
        double tight = 1e-6;

        Selection waiting = lod.select(camera, tight, 1000,
                node -> node == hierarchy.getRoot());
        assertEquals(Collections.singletonList(hierarchy.getRoot()), waiting.getLeaves());
        assertTrue(waiting.isReadinessLimited());
        assertFalse(waiting.isBudgetLimited());
        assertFalse(waiting.isSourceLimited());
        assertTrue(waiting.getMaximumPixels() > tight);
        assertCoverAndBalance(waiting.getLeaves(), CELLS);
        assertThrows(IllegalStateException.class, () -> lod.select(camera, tight, 1000, node -> false));

        Selection budget = lod.select(camera, tight, 1, node -> true);
        assertEquals(Collections.singletonList(hierarchy.getRoot()), budget.getLeaves());
        assertTrue(budget.isBudgetLimited());
        assertFalse(budget.isReadinessLimited());
        assertFalse(budget.isSourceLimited());
        assertTrue(budget.getMaximumPixels() > tight);

        Selection source = lod.select(camera, tight, 1000, node -> true);
        assertTrue(source.isSourceLimited());
        assertFalse(source.isBudgetLimited());
        assertFalse(source.isReadinessLimited());
        assertTrue(source.getMaximumPixels() > tight);
        assertCoverAndBalance(source.getLeaves(), CELLS);
        assertMaximumMatchesNodes(source, camera);
    }

    @Test void projectionCacheIsScopedToOneCameraAndReadinessSnapshot() {
        HeightfieldHierarchy hierarchy = hierarchy(.25);
        HeightfieldLod lod = new HeightfieldLod(hierarchy);
        Camera camera = camera();
        Map<Node, Integer> firstReadiness = new HashMap<>();
        Selection first = lod.select(camera, 1e-6, 1000, node -> {
            firstReadiness.merge(node, 1, Integer::sum);
            return true;
        });
        assertEquals(hierarchy.getNodes().size(), firstReadiness.size());
        assertTrue(firstReadiness.values().stream().allMatch(count -> count == 1),
                "readiness is evaluated once per node in a selection");
        assertMaximumMatchesNodes(first, camera);

        camera.setLocation(new Vector3f(180, 900, 220));
        camera.lookAt(new Vector3f(8, 20, 8), Vector3f.UNIT_Z);
        Map<Node, Integer> secondReadiness = new HashMap<>();
        Selection second = lod.select(camera, 1e-6, 1000, node -> {
            secondReadiness.merge(node, 1, Integer::sum);
            return true;
        });
        assertEquals(hierarchy.getNodes().size(), secondReadiness.size());
        assertTrue(secondReadiness.values().stream().allMatch(count -> count == 1),
                "a new camera selection captures readiness independently");
        assertMaximumMatchesNodes(second, camera);
        assertTrue(first.getMaximumPixels() > second.getMaximumPixels(),
                "a moved camera must not reuse projections from the prior select call");
    }

    @Test void automaticFloatAllowanceCoversNonbinarySteepAndExtremeReconstruction() {
        HeightfieldPage[] pages = {
            page(8, .1f, (x, z) -> ((x + z) & 1) == 0 ? 1.0e20f : -1.0e20f),
            page(8, .3f, (x, z) -> x == 3 ? Float.MAX_VALUE : Float.MAX_VALUE * .5f),
            page(8, Float.MIN_VALUE, (x, z) -> x == 3 ? Float.MIN_VALUE : 0f),
            page(8, 1f, (x, z) -> Float.MAX_VALUE)
        };
        float[] alphas = {0f, 0x1.0p-25f, .17f, .5f, .91f, 1f};
        for (HeightfieldPage page : pages) {
            HeightfieldHierarchy hierarchy = new HeightfieldHierarchy(page, 2, 0);
            for (Node leaf : hierarchy.getNodes()) {
                if (!leaf.getChildren().isEmpty()) continue;
                double automatic = leaf.getStableError();
                assertTrue(automatic > 0, "even an exact leaf includes float reconstruction uncertainty");
                assertTrue(leaf.getMorphError() >= automatic,
                        "morph error includes at least the local reconstruction allowance");
                int stride = leaf.getStride();
                int parentStride = stride * 2;
                for (int z = leaf.getOriginZ(); z <= leaf.getOriginZ() + leaf.getSpan(); z += stride) {
                    for (int x = leaf.getOriginX(); x <= leaf.getOriginX() + leaf.getSpan(); x += stride) {
                        int parentX = (x / parentStride) * parentStride;
                        int parentZ = (z / parentStride) * parentStride;
                        for (float alpha : alphas) {
                            Vector3f actual = HeightfieldReconstruction.morphVertex(
                                    page, x, z, stride, alpha, null);
                            assertTrue(Float.isFinite(actual.x) && Float.isFinite(actual.y)
                                    && Float.isFinite(actual.z), "finite page inputs reconstruct finitely");
                            double a = alpha;
                            double expectedX = (x * (double) page.getSampleSpacing()) * (1 - a)
                                    + (parentX * (double) page.getSampleSpacing()) * a;
                            double expectedY = page.getHeight(x, z) * (1 - a)
                                    + page.getHeight(parentX, parentZ) * a;
                            double expectedZ = (z * (double) page.getSampleSpacing()) * (1 - a)
                                    + (parentZ * (double) page.getSampleSpacing()) * a;
                            double dx = actual.x - expectedX;
                            double dy = actual.y - expectedY;
                            double dz = actual.z - expectedZ;
                            double reconstructionError = Math.sqrt(dx * dx + dy * dy + dz * dz);
                            assertTrue(reconstructionError <= automatic,
                                    () -> "automatic error " + automatic + " must cover float reconstruction "
                                            + reconstructionError + " for spacing " + page.getSampleSpacing()
                                            + " and alpha " + alpha);
                        }
                    }
                }
            }
        }
    }

    @Test void automaticFloatAllowanceUsesFarZMagnitudeAsWellAsFarX() {
        float spacing = .1f;
        HeightfieldPage page = page(8, spacing, (x, z) -> .001f);
        HeightfieldHierarchy hierarchy = new HeightfieldHierarchy(page, 2, 0);
        Node farZ = hierarchy.getNodes().stream()
                .filter(n -> n.getOriginX() == 0 && n.getOriginZ() == 6 && n.getSpan() == 2)
                .findFirst().orElseThrow(AssertionError::new);
        Node swappedX = hierarchy.getNodes().stream()
                .filter(n -> n.getOriginX() == 6 && n.getOriginZ() == 0 && n.getSpan() == 2)
                .findFirst().orElseThrow(AssertionError::new);

        assertEquals(farZ.getStableError(), swappedX.getStableError(), 0,
                "swapping a far coordinate from X to Z must preserve its ULP allowance");
        double fullExtentBound = 16.0 * (Math.ulp(.001f) + Math.ulp(8 * spacing))
                + 2.0 * Float.MIN_NORMAL;
        double xOnlyExtentBound = 16.0 * (Math.ulp(.001f) + Math.ulp(2 * spacing))
                + 2.0 * Float.MIN_NORMAL;
        assertEquals(fullExtentBound, farZ.getStableError(), 0,
                "the exact flat leaf error is the local float-reconstruction allowance");
        assertTrue(farZ.getStableError() > xOnlyExtentBound,
                "far-Z extent must not be bounded using only its near-X extent");

        int x = 0, z = 7;
        float alpha = .37f;
        int parentZ = 6;
        Vector3f actual = HeightfieldReconstruction.morphVertex(page, x, z, 1, alpha, null);
        double expectedZ = (z * (double) spacing) * (1 - (double) alpha)
                + (parentZ * (double) spacing) * alpha;
        assertTrue(Math.abs(actual.z - expectedZ) <= farZ.getStableError(),
                "far-Z morph coordinate conversion must fit the automatic allowance");
    }

    @Test void splitAndMergeTransactionsStayDeterministicAndSeamContinuous() {
        HeightfieldHierarchy hierarchy = hierarchy(0);
        Camera camera = camera();
        HeightfieldLod lod = new HeightfieldLod(hierarchy);
        Selection fine = lod.select(camera, 1e-12, 1000, node -> true);
        Selection coarse = lod.select(camera, 1e-12, 1, node -> true);
        assertEquals(Collections.singletonList(hierarchy.getRoot()), coarse.getLeaves());
        assertTrue(fine.getLeaves().stream().allMatch(n -> n.getChildren().isEmpty()));
        assertCoverAndBalance(fine.getLeaves(), CELLS);

        List<String> firstRun = exerciseTransactions(hierarchy, camera, fine, coarse, 1729);
        List<String> secondRun = exerciseTransactions(hierarchy, camera, fine, coarse, 1729);
        assertEquals(firstRun, secondRun, "same transaction sequence must produce identical patch uniforms");
        assertEquals(firstRun.get(0), firstRun.get(firstRun.size() - 1),
                "a complete split/merge cycle returns to the initial root cover");
        assertTrue(firstRun.subList(1, firstRun.size() - 1).stream()
                .anyMatch(state -> !state.equals(firstRun.get(0))),
                "the transaction trace must include an intermediate distinct cover or morph state");
    }

    private static List<String> exerciseTransactions(HeightfieldHierarchy hierarchy, Camera camera,
            Selection fine, Selection coarse, long seed) {
        Transition transition = new Transition(hierarchy);
        List<String> trace = new ArrayList<>();
        trace.add(snapshot(transition));
        driveTo(transition, hierarchy, fine, camera, seed, trace);
        assertEquals(keys(fine.getLeaves()), keysFromPatches(transition.getPatches()));
        driveTo(transition, hierarchy, coarse, camera, seed + 1, trace);
        assertEquals(keys(coarse.getLeaves()), keysFromPatches(transition.getPatches()));
        return trace;
    }

    private static void driveTo(Transition transition, HeightfieldHierarchy hierarchy,
            Selection target, Camera camera,
            long seed, List<String> trace) {
        Random random = new Random(seed);
        int guard = 0;
        while (true) {
            List<Patch> before = transition.getPatches();
            String beforeSnapshot = snapshot(before);
            assertThrows(UnsupportedOperationException.class, () -> before.add(before.get(0)));
            transition.advance(target, 0);
            assertEquals(beforeSnapshot, snapshot(before), "previous patch list remains an immutable snapshot");
            assertSeams(transition.getPatches(), hierarchy);
            trace.add(snapshot(transition));
            if (!transition.isTransitioning() && keysFromPatches(transition.getPatches()).equals(keys(target.getLeaves()))) {
                break;
            }
            if (transition.isTransitioning()) {
                // Random intermediate alpha steps exercise the whole transaction path.
                float step = .025f + random.nextFloat() * .19f;
                transition.advance(target, step);
                assertSeams(transition.getPatches(), hierarchy);
                trace.add(snapshot(transition));
            }
            assertTrue(++guard < 300, "transition should converge to requested cover");
        }
        assertTrue(!Double.isNaN(transition.getMaximumPixels(camera))
                && transition.getMaximumPixels(camera) >= 0);
    }

    private static void assertCoverAndBalance(List<Node> leaves, int cells) {
        for (int z = 0; z < cells; z++) {
            for (int x = 0; x < cells; x++) {
                int covers = 0;
                for (Node leaf : leaves) {
                    if (x >= leaf.getOriginX() && x + 1 <= leaf.getOriginX() + leaf.getSpan()
                            && z >= leaf.getOriginZ() && z + 1 <= leaf.getOriginZ() + leaf.getSpan()) {
                        covers++;
                    }
                }
                assertEquals(1, covers, "cell " + x + "," + z + " must have exactly one leaf");
            }
        }
        for (int i = 0; i < leaves.size(); i++) {
            Node a = leaves.get(i);
            for (int j = i + 1; j < leaves.size(); j++) {
                Node b = leaves.get(j);
                if (touches(a, b)) {
                    assertTrue(Math.abs(a.getLevel() - b.getLevel()) <= 1,
                            () -> "touching leaves differ by more than 2:1: " + key(a) + " and " + key(b));
                }
            }
        }
    }

    private static void assertMaximumMatchesNodes(Selection selection, Camera camera) {
        double maximum = 0;
        List<Node> leaves = selection.getLeaves();
        for (Node node : leaves) {
            boolean coarserNeighbor = false;
            for (Node other : leaves) {
                if (touches(node, other) && other.getLevel() < node.getLevel()) {
                    coarserNeighbor = true;
                    break;
                }
            }
            double error = coarserNeighbor ? node.getMorphError() : node.getStableError();
            maximum = Math.max(maximum, HeightfieldProjection.pixels(camera, node.getBound(), error));
        }
        assertEquals(maximum, selection.getMaximumPixels(), Math.max(1e-9, maximum * 1e-10));
    }

    private static void assertSeams(List<Patch> patches, HeightfieldHierarchy hierarchy) {
        HeightfieldPage page = hierarchy.getPage();
        Map<String, Float> cornerSamples = new HashMap<>();
        for (int i = 0; i < patches.size(); i++) {
            Patch a = patches.get(i);
            Node node = a.getNode();
            int[][] corners = {
                {node.getOriginX(), node.getOriginZ()},
                {node.getOriginX() + node.getSpan(), node.getOriginZ()},
                {node.getOriginX(), node.getOriginZ() + node.getSpan()},
                {node.getOriginX() + node.getSpan(), node.getOriginZ() + node.getSpan()}
            };
            for (int[] corner : corners) {
                String cornerKey = corner[0] + "," + corner[1];
                float canonical = page.getHeight(corner[0], corner[1]);
                Float prior = cornerSamples.putIfAbsent(cornerKey, canonical);
                if (prior != null) assertEquals(prior, canonical, 0, "shared corners retain the canonical source sample");
                Vector3f sourceVertex = HeightfieldReconstruction.morphVertex(
                        page, corner[0], corner[1], node.getStride(), 0, null);
                assertEquals(canonical, sourceVertex.y, 0);
            }
            for (int j = i + 1; j < patches.size(); j++) {
                Patch b = patches.get(j);
                int sideA = touchingSide(a.getNode(), b.getNode());
                if (sideA < 0) continue;
                int sideB = opposite(sideA);
                Node na = a.getNode(), nb = b.getNode();
                int stride = Math.min(na.getStride(), nb.getStride());
                int overlapStart = sideA <= 1
                        ? Math.max(na.getOriginZ(), nb.getOriginZ())
                        : Math.max(na.getOriginX(), nb.getOriginX());
                int overlapEnd = sideA <= 1
                        ? Math.min(na.getOriginZ() + na.getSpan(), nb.getOriginZ() + nb.getSpan())
                        : Math.min(na.getOriginX() + na.getSpan(), nb.getOriginX() + nb.getSpan());
                if (na.getLevel() == nb.getLevel()) {
                    assertEquals(Math.max(a.getMorph(), b.getMorph()), a.getEdgeMorph(sideA), 1e-6f,
                            "same-level edge uses the larger patch morph");
                    assertEquals(a.getEdgeMorph(sideA), b.getEdgeMorph(sideB), 1e-6f,
                            "equal-level neighbors share the larger edge morph");
                    for (int t = overlapStart; t <= overlapEnd; t += stride) {
                        int x = sideA <= 1 ? (sideA == 0 ? na.getOriginX() : na.getOriginX() + na.getSpan()) : t;
                        int z = sideA <= 1 ? t : (sideA == 2 ? na.getOriginZ() : na.getOriginZ() + na.getSpan());
                        Vector3f pa = HeightfieldReconstruction.morphVertex(
                                page, x, z, na.getStride(), a.getEdgeMorph(sideA), null);
                        Vector3f pb = HeightfieldReconstruction.morphVertex(
                                page, x, z, nb.getStride(), b.getEdgeMorph(sideB), null);
                        assertVectorEquals(pa, pb, 1e-5f, "same-level edge samples must meet");
                    }
                } else {
                    Patch fine = na.getStride() < nb.getStride() ? a : b;
                    Patch coarse = fine == a ? b : a;
                    int fineSide = fine == a ? sideA : sideB;
                    assertEquals(1, fine.getEdgeMorph(fineSide), 1e-6f,
                            "fine edge against stable coarse patch stays collapsed");
                    Node nf = fine.getNode(), nc = coarse.getNode();
                    int fs = nf.getStride();
                    for (int t = overlapStart; t <= overlapEnd; t += fs) {
                        int x = fineSide <= 1 ? (fineSide == 0 ? nf.getOriginX() : nf.getOriginX() + nf.getSpan()) : t;
                        int z = fineSide <= 1 ? t : (fineSide == 2 ? nf.getOriginZ() : nf.getOriginZ() + nf.getSpan());
                        Vector3f collapsed = HeightfieldReconstruction.morphVertex(
                                page, x, z, nf.getStride(), fine.getEdgeMorph(fineSide), null);
                        boolean onCoarseEdge = false;
                        int coarseSide = fine == a ? sideB : sideA;
                        int cStart = coarseSide <= 1 ? nc.getOriginZ() : nc.getOriginX();
                        int cEnd = cStart + nc.getSpan();
                        for (int q = cStart; q <= cEnd; q += nc.getStride()) {
                            int cx = coarseSide <= 1 ? (coarseSide == 0 ? nc.getOriginX() : nc.getOriginX() + nc.getSpan()) : q;
                            int cz = coarseSide <= 1 ? q : (coarseSide == 2 ? nc.getOriginZ() : nc.getOriginZ() + nc.getSpan());
                            Vector3f coarseVertex = HeightfieldReconstruction.morphVertex(
                                    page, cx, cz, nc.getStride(), 0, null);
                            if (samePoint(collapsed, coarseVertex, 1e-5f)) {
                                onCoarseEdge = true;
                                break;
                            }
                        }
                        assertTrue(onCoarseEdge, "fine boundary vertex must collapse onto the coarse edge mesh");
                    }
                }
            }
        }
    }

    private static HeightfieldHierarchy hierarchy(double extraError) {
        float[] samples = new float[(CELLS + 3) * (CELLS + 3)];
        for (int z = -1; z <= CELLS + 1; z++) {
            for (int x = -1; x <= CELLS + 1; x++) {
                float value = .17f * x * x + .11f * z * z + .073f * x * z
                        + (x % 3 == 0 ? 2.7f : -.4f) + (z % 4 == 1 ? -1.9f : .2f);
                samples[(z + 1) * (CELLS + 3) + x + 1] = value;
            }
        }
        return new HeightfieldHierarchy(new HeightfieldPage(CELLS, 1, samples), PATCH_CELLS, extraError);
    }

    private static HeightfieldHierarchy spikeHierarchy() {
        float[] samples = new float[(CELLS + 3) * (CELLS + 3)];
        samples[(1 + 1) * (CELLS + 3) + 1 + 1] = 4;
        return new HeightfieldHierarchy(new HeightfieldPage(CELLS, 1, samples), PATCH_CELLS, 0);
    }

    private static HeightfieldPage page(int cells, float spacing, HeightFunction function) {
        float[] samples = new float[(cells + 3) * (cells + 3)];
        for (int z = -1; z <= cells + 1; z++) {
            for (int x = -1; x <= cells + 1; x++) {
                samples[(z + 1) * (cells + 3) + x + 1] = function.height(x, z);
            }
        }
        return new HeightfieldPage(cells, spacing, samples);
    }

    @FunctionalInterface
    private interface HeightFunction {
        float height(int x, int z);
    }

    private static Camera camera() {
        Camera camera = new Camera(1000, 700);
        camera.setFrustumPerspective(65, 1000f / 700, .5f, 500);
        camera.setLocation(new Vector3f(8, 150, 8));
        camera.lookAt(new Vector3f(8, 40, 8), Vector3f.UNIT_Z);
        return camera;
    }

    private static boolean touches(Node a, Node b) {
        return a != b && a.getOriginX() <= b.getOriginX() + b.getSpan()
                && b.getOriginX() <= a.getOriginX() + a.getSpan()
                && a.getOriginZ() <= b.getOriginZ() + b.getSpan()
                && b.getOriginZ() <= a.getOriginZ() + a.getSpan();
    }

    private static int touchingSide(Node a, Node b) {
        boolean zOverlap = Math.max(a.getOriginZ(), b.getOriginZ()) < Math.min(a.getOriginZ() + a.getSpan(), b.getOriginZ() + b.getSpan());
        boolean xOverlap = Math.max(a.getOriginX(), b.getOriginX()) < Math.min(a.getOriginX() + a.getSpan(), b.getOriginX() + b.getSpan());
        if (zOverlap && a.getOriginX() == b.getOriginX() + b.getSpan()) return 0;
        if (zOverlap && a.getOriginX() + a.getSpan() == b.getOriginX()) return 1;
        if (xOverlap && a.getOriginZ() == b.getOriginZ() + b.getSpan()) return 2;
        if (xOverlap && a.getOriginZ() + a.getSpan() == b.getOriginZ()) return 3;
        return -1;
    }

    private static int opposite(int side) { return side ^ 1; }

    private static void assertVectorEquals(Vector3f a, Vector3f b, float epsilon, String message) {
        assertEquals(a.x, b.x, epsilon, message + " x");
        assertEquals(a.y, b.y, epsilon, message + " y");
        assertEquals(a.z, b.z, epsilon, message + " z");
    }

    private static boolean samePoint(Vector3f a, Vector3f b, float epsilon) {
        return Math.abs(a.x - b.x) <= epsilon && Math.abs(a.y - b.y) <= epsilon && Math.abs(a.z - b.z) <= epsilon;
    }

    private static String snapshot(Transition transition) { return snapshot(transition.getPatches()); }

    private static String snapshot(List<Patch> patches) {
        List<String> rows = new ArrayList<>();
        for (Patch patch : patches) {
            StringBuilder row = new StringBuilder(key(patch.getNode())).append(':')
                    .append(Float.floatToIntBits(patch.getMorph()));
            for (int side = 0; side < 4; side++) row.append(':').append(Float.floatToIntBits(patch.getEdgeMorph(side)));
            rows.add(row.toString());
        }
        rows.sort(String::compareTo);
        return rows.toString();
    }

    private static Set<String> keys(List<Node> nodes) {
        Set<String> keys = new HashSet<>();
        for (Node node : nodes) keys.add(key(node));
        return keys;
    }

    private static Set<String> keysFromPatches(List<Patch> patches) {
        Set<String> keys = new HashSet<>();
        for (Patch patch : patches) keys.add(key(patch.getNode()));
        return keys;
    }

    private static String key(Node node) {
        return node.getOriginX() + "," + node.getOriginZ() + "," + node.getSpan() + "," + node.getStride();
    }
}
