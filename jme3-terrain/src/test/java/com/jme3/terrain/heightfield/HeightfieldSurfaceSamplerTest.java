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
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
 * TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A
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
import com.jme3.renderer.Camera;
import com.jme3.terrain.heightfield.HeightfieldHierarchy.Node;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeightfieldSurfaceSamplerTest {
    private static final int SOURCE_CELLS = 16;
    private static final int PATCH_CELLS = 8;

    @Test
    void fixedSourcePointsMatchBruteForceMorphedTrianglesAcrossTransitionAlphas() {
        HeightfieldPage source = sourcePage();
        HeightfieldHierarchy hierarchy = new HeightfieldHierarchy(source, PATCH_CELLS, 0);
        HeightfieldLod lod = new HeightfieldLod(hierarchy);
        HeightfieldLod.Selection desired = lod.select(camera(), 1e-5, 64, node -> true);
        assertTrue(desired.getLeaves().size() > 1, "fixture must select a refined cover");

        HeightfieldLod.Transition transition = new HeightfieldLod.Transition(hierarchy);
        List<HeightfieldLod.Patch> stableRoot = transition.getPatches();
        HeightfieldSurfaceSampler sampler = new HeightfieldSurfaceSampler();
        Scratch scratch = new Scratch();
        for (HeightfieldLod.Patch patch : stableRoot) {
            compareNodeSamples(sampler, scratch, patch, nodePage(source, patch.getNode()));
        }

        transition.advance(desired, 0f); // begin root split at alpha 1
        float[] expectedAlphas = {1f, .9999999f, .75f, .5f, .25f, 0f};
        float current = 1f;
        for (int i = 0; i < expectedAlphas.length; i++) {
            float alpha = expectedAlphas[i];
            if (i > 0) {
                transition.advance(desired, current - alpha);
                current = alpha;
            }
            List<HeightfieldLod.Patch> patches = transition.getPatches();
            assertEquals(4, patches.size());
            for (HeightfieldLod.Patch patch : patches) {
                assertEquals(alpha, patch.getMorph(), 0f);
                compareNodeSamples(sampler, scratch, patch, nodePage(source, patch.getNode()));
            }
        }
    }

    @Test
    void matchesMixedEdgeMorphsBesideStableCoarserPatches() {
        HeightfieldPage source = pageFor(32, .5f, 0, 0, 1);
        HeightfieldHierarchy hierarchy = new HeightfieldHierarchy(source, PATCH_CELLS, 0);
        HeightfieldLod lod = new HeightfieldLod(hierarchy);
        HeightfieldLod.Selection desired = lod.select(camera(), 1e-5, 64, node -> true);
        HeightfieldLod.Transition transition = new HeightfieldLod.Transition(hierarchy);
        transition.advance(desired, 0f); // start root split
        transition.advance(desired, 1f); // settle root split
        transition.advance(desired, 0f); // start first child split
        transition.advance(desired, .5f); // expose its edges against stable coarser siblings

        List<HeightfieldLod.Patch> patches = transition.getPatches();
        assertTrue(patches.size() > 4, "fixture must contain a refined child and stable siblings");
        boolean hasDifferentEdgeAndInterior = false;
        HeightfieldSurfaceSampler sampler = new HeightfieldSurfaceSampler();
        Scratch scratch = new Scratch();
        for (HeightfieldLod.Patch patch : patches) {
            HeightfieldPage page = nodePage(source, patch.getNode());
            for (int side = 0; side < 4; side++) {
                hasDifferentEdgeAndInterior |= patch.getEdgeMorph(side) != patch.getMorph();
            }
            compareNodeSamples(sampler, scratch, patch, page);
        }
        assertTrue(hasDifferentEdgeAndInterior, "fixture must exercise edge morphs independently");
    }

    @Test
    void rejectsMismatchedPagesAndPointsOutsideTheNode() {
        HeightfieldPage source = sourcePage();
        HeightfieldHierarchy hierarchy = new HeightfieldHierarchy(source, PATCH_CELLS, 0);
        HeightfieldLod.Patch root = new HeightfieldLod.Transition(hierarchy).getPatches().get(0);
        HeightfieldSurfaceSampler sampler = new HeightfieldSurfaceSampler();
        HeightfieldPage correct = nodePage(source, root.getNode());
        HeightfieldPage wrong = pageFor(4, correct.getSampleSpacing(), 0, 0, 1);
        assertThrows(IllegalArgumentException.class, () -> sampler.height(root, wrong, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> sampler.height(root, correct, -1e-4, 0));
        assertThrows(IllegalArgumentException.class, () -> sampler.height(root, correct, 0, Double.NaN));
    }

    @Test
    void nonBinarySpacingUsesRenderedFloatTrianglesForStableAndMorphedPatches() {
        HeightfieldPage source = pageFor(SOURCE_CELLS, 1.3f, 0, 0, 1);
        HeightfieldHierarchy hierarchy = new HeightfieldHierarchy(source, PATCH_CELLS, 0);
        HeightfieldLod lod = new HeightfieldLod(hierarchy);
        HeightfieldLod.Selection desired = lod.select(camera(), 1e-5, 64, node -> true);
        HeightfieldLod.Transition transition = new HeightfieldLod.Transition(hierarchy);
        HeightfieldSurfaceSampler sampler = new HeightfieldSurfaceSampler();
        Scratch scratch = new Scratch();
        compareNodeSamples(sampler, scratch, transition.getPatches().get(0),
                nodePage(source, transition.getPatches().get(0).getNode()));

        transition.advance(desired, 0f);
        float[] alphas = {1f, .5f, 0f};
        float current = 1f;
        for (int i = 0; i < alphas.length; i++) {
            if (i > 0) {
                transition.advance(desired, current - alphas[i]);
                current = alphas[i];
            }
            for (HeightfieldLod.Patch patch : transition.getPatches()) {
                compareNodeSamples(sampler, scratch, patch, nodePage(source, patch.getNode()));
            }
        }
    }

    private static void compareNodeSamples(HeightfieldSurfaceSampler sampler, Scratch scratch,
            HeightfieldLod.Patch patch, HeightfieldPage page) {
        Node node = patch.getNode();
        int stride = node.getStride();
        int cells = page.getCells();
        double[] local = {0, 1e-7, .125, .4999999, .5, .5000001, 1, 1.25,
            2.5, 4.75, cells - 1.0000001, cells - 1, cells - .125, cells - 1e-7, cells};
        for (double z : local) {
            for (double x : local) {
                double sourceFineX = node.getOriginX() + x * stride;
                double sourceFineZ = node.getOriginZ() + z * stride;
                double expected = bruteForceHeight(patch, page, x * page.getSampleSpacing(),
                        z * page.getSampleSpacing(), scratch);
                double actual = sampler.height(patch, page, sourceFineX, sourceFineZ);
                assertEquals(expected, actual, 2e-4,
                        "node=" + node.getOriginX() + "," + node.getOriginZ()
                        + " alpha=" + patch.getMorph() + " local=" + x + "," + z);
            }
        }
    }

    /** Full-cell reference: inspect every deformed grid triangle, without the sampler's local search. */
    private static double bruteForceHeight(HeightfieldLod.Patch patch, HeightfieldPage page,
            double queryX, double queryZ, Scratch scratch) {
        int cells = page.getCells();
        float interior = patch.getMorph();
        float west = patch.getEdgeMorph(0), east = patch.getEdgeMorph(1);
        float south = patch.getEdgeMorph(2), north = patch.getEdgeMorph(3);
        double tolerance = 1e-12;
        for (int z = 0; z < cells; z++) {
            for (int x = 0; x < cells; x++) {
                moved(page, cells, x, z, interior, west, east, south, north, scratch.a);
                moved(page, cells, x + 1, z + 1, interior, west, east, south, north, scratch.d);
                moved(page, cells, x + 1, z, interior, west, east, south, north, scratch.b);
                double result = triangle(scratch.a, scratch.d, scratch.b, queryX, queryZ,
                        tolerance);
                if (Double.isFinite(result)) return result;
                moved(page, cells, x, z + 1, interior, west, east, south, north, scratch.c);
                result = triangle(scratch.a, scratch.c, scratch.d, queryX, queryZ,
                        tolerance);
                if (Double.isFinite(result)) return result;
            }
        }
        throw new AssertionError("brute-force mesh has no triangle at query " + queryX + "," + queryZ);
    }

    private static void moved(HeightfieldPage page, int cells, int x, int z,
            float interior, float west, float east, float south, float north, Vector3f store) {
        float alpha = interior;
        if (x == 0) alpha = Math.max(alpha, west);
        if (x == cells) alpha = Math.max(alpha, east);
        if (z == 0) alpha = Math.max(alpha, south);
        if (z == cells) alpha = Math.max(alpha, north);
        if ((x == 0 || x == cells) && (z == 0 || z == cells)) alpha = 0f;
        HeightfieldReconstruction.morphVertex(page, x, z, 1, alpha, store);
    }

    private static double triangle(Vector3f a, Vector3f b, Vector3f c,
            double x, double z, double tolerance) {
        double abX = (double) b.x - a.x, abZ = (double) b.z - a.z;
        double acX = (double) c.x - a.x, acZ = (double) c.z - a.z;
        double denominator = abX * acZ - abZ * acX;
        if (denominator == 0.0) return Double.NaN;
        double apX = x - a.x, apZ = z - a.z;
        double wb = (apX * acZ - apZ * acX) / denominator;
        double wc = (abX * apZ - abZ * apX) / denominator;
        double wa = 1 - wb - wc;
        if (wa < -tolerance || wb < -tolerance || wc < -tolerance) return Double.NaN;
        wa = Math.max(0, wa); wb = Math.max(0, wb); wc = Math.max(0, wc);
        double sum = wa + wb + wc;
        return sum > 0 ? (wa * a.y + wb * b.y + wc * c.y) / sum : Double.NaN;
    }

    private static HeightfieldPage sourcePage() {
        return pageFor(SOURCE_CELLS, .5f, 0, 0, 1);
    }

    private static HeightfieldPage nodePage(HeightfieldPage source, Node node) {
        int cells = node.getSpan() / node.getStride();
        int side = cells + 3;
        float spacing = source.getSampleSpacing() * node.getStride();
        float[] heights = new float[side * side];
        for (int z = -1; z <= cells + 1; z++) {
            for (int x = -1; x <= cells + 1; x++) {
                heights[(z + 1) * side + x + 1] = height(
                        node.getOriginX() + x * node.getStride(),
                        node.getOriginZ() + z * node.getStride());
            }
        }
        return new HeightfieldPage(cells, spacing, heights);
    }

    private static HeightfieldPage pageFor(int cells, float spacing, int originX, int originZ, int stride) {
        int side = cells + 3;
        float[] heights = new float[side * side];
        for (int z = -1; z <= cells + 1; z++) {
            for (int x = -1; x <= cells + 1; x++) {
                heights[(z + 1) * side + x + 1] = height(originX + x * stride, originZ + z * stride);
            }
        }
        return new HeightfieldPage(cells, spacing, heights);
    }

    private static float height(int x, int z) {
        float analytic = .025f * x * x - .017f * z * z + .031f * x * z;
        return analytic + (x == 3 && z == 5 ? 7f : 0f);
    }

    private static Camera camera() {
        Camera camera = new Camera(800, 600);
        camera.setFrustumPerspective(60, 800f / 600, .1f, 100);
        camera.setLocation(new Vector3f(8, 35, 8));
        camera.lookAt(new Vector3f(8, 0, 8), Vector3f.UNIT_Y);
        return camera;
    }

    private static final class Scratch {
        final Vector3f a = new Vector3f(), b = new Vector3f();
        final Vector3f c = new Vector3f(), d = new Vector3f();
    }
}
