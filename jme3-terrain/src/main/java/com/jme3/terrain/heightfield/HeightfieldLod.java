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

import com.jme3.renderer.Camera;
import com.jme3.terrain.heightfield.HeightfieldHierarchy.Node;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Bounded CPU correctness oracle for screen-error selection and crack-free
 * transitions. Covers the whole source page, including invisible patches, and
 * balances touching edges AND corners to 2:1. Pairwise checks are intended for
 * small reference scenes, not a production large-world selection accelerator.
 * Call on one thread; a Selection captures readiness for that selection only.
 */
public final class HeightfieldLod {
    private final HeightfieldHierarchy hierarchy;

    public HeightfieldLod(HeightfieldHierarchy hierarchy) {
        if (hierarchy == null) throw new IllegalArgumentException("hierarchy required");
        this.hierarchy = hierarchy;
    }

    /**
     * Select in the hierarchy's physical page-coordinate frame. The camera must
     * project that frame directly: for a translated page, its owner supplies a
     * page-relative camera. This reference does not apply Geometry world
     * transforms or scale the hierarchy's world-error allowance.
     *
     * @param camera camera interpreting the page's bounds and error units
     * @param maximumPixels positive requested screen-error limit
     * @param maximumPatches finite cover capacity, including invisible patches
     * @param ready stable, total predicate for every node in this selection
     * @return desired ready cover and its quality limitations
     */
    public Selection select(Camera camera, double maximumPixels, int maximumPatches, Predicate<Node> ready) {
        if (camera == null || !Double.isFinite(maximumPixels) || maximumPixels <= 0
                || maximumPatches < 1 || ready == null) {
            throw new IllegalArgumentException("camera, positive finite error/budget and readiness required");
        }
        Map<Node, Boolean> readiness = new HashMap<>();
        // Capture ancestors too: coarsening must not depend on which nodes
        // happened to be visited by this frame's refinement traversal.
        for (Node node : hierarchy.getNodes()) readiness.put(node, ready.test(node));
        Predicate<Node> snapshot = n -> readiness.computeIfAbsent(n, ready::test);
        if (!snapshot.test(hierarchy.getRoot())) {
            throw new IllegalStateException("a ready root must remain pinned as a fallback");
        }
        List<Node> leaves = new ArrayList<>(Collections.singletonList(hierarchy.getRoot()));
        Set<Node> attempted = new HashSet<>();
        // Each projection depends only on this call's camera and immutable
        // node bounds/errors. A dense pair per node avoids repeated hash lookups;
        // nothing is retained for a later camera or selection.
        double[] projections = new double[2 * hierarchy.getNodes().size()];
        Arrays.fill(projections, Double.NaN);
        boolean budget = false, waiting = false, source = false;
        while (true) {
            Node worst = null;
            double error = maximumPixels;
            for (Node leaf : leaves) {
                double pixels = pixels(camera, leaf, hasCoarserNeighbor(leaf, leaves), projections);
                if (!attempted.contains(leaf) && pixels > error) {
                    worst = leaf;
                    error = pixels;
                }
            }
            if (worst == null) break;
            attempted.add(worst);
            Node split = worst;
            if (split.getChildren().isEmpty()) {
                split = null;
                for (Node neighbor : leaves) {
                    if (touches(worst, neighbor) && neighbor.getLevel() < worst.getLevel()) {
                        split = neighbor;
                        break;
                    }
                }
                if (split == null) { source = true; continue; }
            }
            List<Node> proposal = new ArrayList<>(leaves);
            int failure = split(proposal, split, maximumPatches, snapshot);
            while (failure == 0) {
                Node coarse = unbalancedCoarse(proposal);
                if (coarse == null) break;
                failure = split(proposal, coarse, maximumPatches, snapshot);
            }
            if (failure == 0) {
                leaves = proposal;
                attempted.clear();
            } else {
                budget |= failure == 1;
                waiting |= failure == 2;
            }
        }
        double achieved = maximumPixels(camera, leaves, projections);
        boolean limited = achieved > maximumPixels;
        Set<Node> knownReady = new HashSet<>();
        readiness.forEach((n, r) -> { if (r) knownReady.add(n); });
        return new Selection(hierarchy, leaves, knownReady, achieved,
                limited && budget, limited && waiting, limited && source);
    }

    private static int split(List<Node> cover, Node node, int budget, Predicate<Node> ready) {
        if (cover.size() + 3 > budget) return 1;
        for (Node child : node.getChildren()) if (!ready.test(child)) return 2;
        cover.remove(node);
        cover.addAll(node.getChildren());
        return 0;
    }

    private static double pixels(Camera camera, Node node, boolean morphed) {
        return HeightfieldProjection.pixels(camera, node.getBound(),
                morphed ? node.getMorphError() : node.getStableError());
    }

    private static double pixels(Camera camera, Node node, boolean morphed, double[] projections) {
        int index = 2 * node.getIndex() + (morphed ? 1 : 0);
        double value = projections[index];
        if (Double.isNaN(value)) {
            value = pixels(camera, node, morphed);
            projections[index] = value;
        }
        return value;
    }

    private static double maximumPixels(Camera camera, List<Node> leaves, double[] projections) {
        double maximum = 0;
        for (Node node : leaves) {
            maximum = Math.max(maximum, pixels(camera, node, hasCoarserNeighbor(node, leaves), projections));
        }
        return maximum;
    }

    private static boolean touches(Node a, Node b) {
        return a != b && a.getOriginX() <= b.getOriginX() + b.getSpan()
                && b.getOriginX() <= a.getOriginX() + a.getSpan()
                && a.getOriginZ() <= b.getOriginZ() + b.getSpan()
                && b.getOriginZ() <= a.getOriginZ() + a.getSpan();
    }

    private static Node unbalancedCoarse(List<Node> leaves) {
        for (Node a : leaves) for (Node b : leaves) {
            if (touches(a, b) && a.getLevel() + 1 < b.getLevel()) return a;
        }
        return null;
    }

    private static boolean hasCoarserNeighbor(Node node, List<Node> leaves) {
        for (Node other : leaves) if (touches(node, other) && other.getLevel() < node.getLevel()) return true;
        return false;
    }

    /** Immutable desired cover and truthful quality saturation indicators. */
    public static final class Selection {
        private final HeightfieldHierarchy hierarchy;
        private final List<Node> leaves;
        private final Set<Node> ready;
        private final double maximum;
        private final boolean budget, readiness, source;
        private Selection(HeightfieldHierarchy h, List<Node> leaves, Set<Node> ready, double maximum,
                boolean budget, boolean readiness, boolean source) {
            hierarchy = h;
            this.leaves = Collections.unmodifiableList(new ArrayList<>(leaves));
            this.ready = Collections.unmodifiableSet(new HashSet<>(ready));
            this.maximum = maximum;
            this.budget = budget;
            this.readiness = readiness;
            this.source = source;
        }
        public List<Node> getLeaves() { return leaves; }
        public double getMaximumPixels() { return maximum; }
        public boolean isBudgetLimited() { return budget; }
        public boolean isReadinessLimited() { return readiness; }
        public boolean isSourceLimited() { return source; }
    }

    /**
     * One sibling transaction at a time. Neighbors at the same level share
     * their maximum edge morph. Finer edges against a stable coarser patch
     * stay fully collapsed. No coarser patch is allowed to morph next to a
     * finer outside patch; merging is postponed until that neighbor coarsens.
     * Streaming must retain all referenced source/parent data until completion.
     */
    public static final class Transition {
        private final HeightfieldHierarchy hierarchy;
        private final List<Node> leaves = new ArrayList<>();
        private Node active;
        private boolean merging;
        private float alpha;

        public Transition(HeightfieldHierarchy hierarchy) {
            if (hierarchy == null) throw new IllegalArgumentException("hierarchy required");
            this.hierarchy = hierarchy;
            leaves.add(hierarchy.getRoot());
        }

        public void advance(Selection desired, float step) {
            if (desired == null || desired.hierarchy != hierarchy || !Float.isFinite(step) || step < 0 || step > 1) {
                throw new IllegalArgumentException("matching selection and step in [0,1] required");
            }
            if (active != null) {
                alpha = merging ? Math.min(1, alpha + step) : Math.max(0, alpha - step);
                if ((merging && alpha == 1) || (!merging && alpha == 0)) {
                    if (merging) {
                        leaves.removeAll(active.getChildren());
                        leaves.add(active);
                    }
                    active = null;
                }
                return;
            }
            List<Node> ordered = new ArrayList<>(leaves);
            ordered.sort(Comparator.comparingInt(Node::getLevel));
            for (Node node : ordered) {
                boolean finer = desired.leaves.stream().anyMatch(n -> node.contains(n) && n.getLevel() > node.getLevel());
                if (!finer || !desired.ready.containsAll(node.getChildren())) continue;
                List<Node> after = new ArrayList<>(leaves);
                after.remove(node);
                after.addAll(node.getChildren());
                if (unbalancedCoarse(after) != null) continue;
                active = node;
                merging = false;
                alpha = 1;
                leaves.clear();
                leaves.addAll(after);
                return;
            }
            List<Node> parents = new ArrayList<>(hierarchy.getNodes());
            parents.sort(Comparator.comparingInt(Node::getLevel).reversed());
            for (Node parent : parents) {
                if (parent.getChildren().isEmpty() || !leaves.containsAll(parent.getChildren())
                        || !desired.ready.contains(parent)
                        || desired.leaves.stream().noneMatch(n -> n.contains(parent))) continue;
                List<Node> after = new ArrayList<>(leaves);
                after.removeAll(parent.getChildren());
                after.add(parent);
                if (unbalancedCoarse(after) != null) continue;
                active = parent;
                merging = true;
                alpha = 0;
                return;
            }
        }

        public boolean isTransitioning() { return active != null; }

        public List<Patch> getPatches() {
            List<Patch> result = new ArrayList<>();
            for (Node node : leaves) {
                float interior = interior(node);
                float[] edges = {interior, interior, interior, interior};
                for (Node other : leaves) {
                    int side = side(node, other);
                    if (side < 0) continue;
                    if (other.getLevel() < node.getLevel()) edges[side] = 1;
                    else if (other.getLevel() == node.getLevel()) edges[side] = Math.max(edges[side], interior(other));
                }
                result.add(new Patch(node, interior, edges));
            }
            return Collections.unmodifiableList(result);
        }

        public double getMaximumPixels(Camera camera) {
            double maximum = 0;
            for (Patch patch : getPatches()) {
                boolean morph = patch.morph > 0;
                for (float edge : patch.edges) morph |= edge > 0;
                maximum = Math.max(maximum, pixels(camera, patch.node, morph));
            }
            return maximum;
        }

        private float interior(Node node) { return active != null && node.getParent() == active ? alpha : 0; }

        private static int side(Node a, Node b) {
            if (a == b) return -1;
            boolean zOverlap = Math.max(a.getOriginZ(), b.getOriginZ()) < Math.min(a.getOriginZ() + a.getSpan(), b.getOriginZ() + b.getSpan());
            boolean xOverlap = Math.max(a.getOriginX(), b.getOriginX()) < Math.min(a.getOriginX() + a.getSpan(), b.getOriginX() + b.getSpan());
            if (zOverlap && a.getOriginX() == b.getOriginX() + b.getSpan()) return 0;
            if (zOverlap && a.getOriginX() + a.getSpan() == b.getOriginX()) return 1;
            if (xOverlap && a.getOriginZ() == b.getOriginZ() + b.getSpan()) return 2;
            if (xOverlap && a.getOriginZ() + a.getSpan() == b.getOriginZ()) return 3;
            return -1;
        }
    }

    /** Per-draw uniforms; edge order is left, right, back, front. */
    public static final class Patch {
        private final Node node;
        private final float morph;
        private final float[] edges;
        private Patch(Node node, float morph, float[] edges) { this.node = node; this.morph = morph; this.edges = edges; }
        public Node getNode() { return node; }
        public float getMorph() { return morph; }
        public float getEdgeMorph(int side) {
            if (side < 0 || side > 3) throw new IllegalArgumentException("edge index must be 0..3");
            return edges[side];
        }
    }
}
