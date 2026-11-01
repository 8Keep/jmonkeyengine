/*
 * Copyright (c) 2009-2026 jMonkeyEngine
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
 * * Neither the name of 'jMonkeyEngine' nor the names of its contributors may
 *   be used to endorse or promote products derived from this software without
 *   specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDER AND CONTRIBUTORS "AS IS"
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

import com.jme3.math.Vector3f;

/** Finite CPU checks for frame topology, selection, and bake bases. */
public final class TestConiferImpostorFrames {
    private static final double TOLERANCE = 2e-6;

    private TestConiferImpostorFrames() { }

    public static void main(String[] args) {
        check(ConiferImpostorFrames.COUNT == 65, "65 layers include explicit zenith");
        check(ConiferImpostorFrames.WIDTH == 128 && ConiferImpostorFrames.HEIGHT == 256,
                "frame dimensions");
        check(ConiferImpostorFrames.CENTER_Y == 9f && ConiferImpostorFrames.EXTENT_Y == 9f,
                "base-origin tree pivot");
        checkGridNodes();
        checkDirectionsAndBases();
        checkReconstruction();
        checkEdgesAndYaw();
        checkInvalidInputs();
        checkLodContinuity();
        System.out.println("CONIFER_IMPOSTOR_FRAMES_CPU_PASS count=" + ConiferImpostorFrames.COUNT);
    }

    private static void checkLodContinuity() {
        check(ConiferImpostor.meshWeight(24, 500, .5f, .5f, 1) == 0, "small distant tree is fully far");
        check(ConiferImpostor.meshWeight(40, 500, .5f, .5f, 1) == 1, "large tree stays geometry");
        check(ConiferImpostor.meshWeight(10, 104, .5f, .5f, 1) == 1, "close perspective stays geometry");
        check(ConiferImpostor.meshWeight(10, 500, 0, .5f, 1) == 1, "horizon observer uses mesh");
        check(ConiferImpostor.meshWeight(10, 500, .5f, 0, 1) == 1, "horizon light uses mesh");
        check(ConiferImpostor.meshWeight(10, 500, .5f, .5f, 0) == 1, "new atlas starts on mesh");
        float previous = 1;
        for (int distance = 0; distance <= 600; distance++) {
            float weight = ConiferImpostor.meshWeight(11000f / Math.max(1,distance), distance, .5f, .5f, 1);
            check(weight >= 0 && weight <= 1 && weight <= previous + 1e-6, "distance transition is bounded and monotone");
            check(Math.abs(weight - previous) < .02f, "no abrupt 400-metre eligibility jump");
            previous = weight;
        }
    }

    private static void checkGridNodes() {
        Vector3f direction = new Vector3f();
        int[] ids = new int[3];
        float[] weights = new float[3];
        for (int row = 0; row < ConiferImpostorFrames.GRID_HEIGHT; row++) {
            for (int column = 0; column < ConiferImpostorFrames.GRID_WIDTH; column++) {
                int index = row * ConiferImpostorFrames.GRID_WIDTH + column;
                ConiferImpostorFrames.direction(index, direction);
                check(ConiferImpostorFrames.select(direction, ids, weights), "grid direction is selectable");
                check(oneHot(ids, weights, index), "grid node selects its exact layer " + index);
            }
        }
        ConiferImpostorFrames.direction(ConiferImpostorFrames.ZENITH_INDEX, direction);
        check(ConiferImpostorFrames.select(direction, ids, weights), "zenith is selectable");
        check(oneHot(ids, weights, ConiferImpostorFrames.ZENITH_INDEX), "zenith selects its explicit layer");
    }

    private static void checkDirectionsAndBases() {
        Vector3f direction = new Vector3f();
        Vector3f right = new Vector3f();
        Vector3f up = new Vector3f();
        Vector3f cross = new Vector3f();
        for (int index = 0; index < ConiferImpostorFrames.COUNT; index++) {
            ConiferImpostorFrames.direction(index, direction);
            ConiferImpostorFrames.right(index, right);
            ConiferImpostorFrames.up(index, up);
            check(direction.isUnitVector(), "unit direction " + index);
            check(right.isUnitVector() && up.isUnitVector(), "unit bake axes " + index);
            check(Math.abs(direction.dot(right)) < TOLERANCE
                    && Math.abs(direction.dot(up)) < TOLERANCE
                    && Math.abs(right.dot(up)) < TOLERANCE, "orthogonal frame " + index);
            right.cross(up, cross);
            check(cross.distance(direction) < TOLERANCE, "right-handed basis " + index);
            float halfWidth = ConiferImpostorFrames.halfWidth(index);
            float halfHeight = ConiferImpostorFrames.halfHeight(index);
            check(halfWidth > 0 && halfHeight > 0 && halfWidth <= 11 && halfHeight <= 11,
                    "finite conservative bake extents " + index);
            checkProjectedBox(index, halfWidth, halfHeight);
        }
        ConiferImpostorFrames.right(ConiferImpostorFrames.ZENITH_INDEX, right);
        check(right.equals(Vector3f.UNIT_X), "zenith has deterministic +X right axis");
    }

    private static void checkProjectedBox(int index, float halfWidth, float halfHeight) {
        Vector3f right = ConiferImpostorFrames.right(index, new Vector3f());
        Vector3f up = ConiferImpostorFrames.up(index, new Vector3f());
        for (int ix = -1; ix <= 1; ix += 2) for (int iy = -1; iy <= 1; iy += 2) {
            for (int iz = -1; iz <= 1; iz += 2) {
                double projectedX = Math.abs(ix * ConiferImpostorFrames.EXTENT_X * right.x
                        + iy * ConiferImpostorFrames.EXTENT_Y * right.y
                        + iz * ConiferImpostorFrames.EXTENT_Z * right.z);
                double projectedY = Math.abs(ix * ConiferImpostorFrames.EXTENT_X * up.x
                        + iy * ConiferImpostorFrames.EXTENT_Y * up.y
                        + iz * ConiferImpostorFrames.EXTENT_Z * up.z);
                check(projectedX <= halfWidth + TOLERANCE && projectedY <= halfHeight + TOLERANCE,
                        "projected box fits frame " + index);
            }
        }
    }

    private static void checkReconstruction() {
        int[] ids = new int[3];
        float[] weights = new float[3];
        Vector3f direction = new Vector3f();
        Vector3f reconstructed = new Vector3f();
        // Deterministic directional lattice exercises both hemispheric edges,
        // octahedral diagonals, the center fan, and ordinary cells.
        for (int yi = 0; yi <= 16; yi++) {
            float elevation = (float) (Math.PI * .5 * yi / 16.0);
            for (int az = 0; az < 32; az++) {
                float angle = (float) (Math.PI * 2 * az / 32.0);
                direction.set((float) (Math.cos(elevation) * Math.cos(angle)),
                        (float) Math.sin(elevation),
                        (float) (Math.cos(elevation) * Math.sin(angle)));
                check(ConiferImpostorFrames.select(direction, ids, weights), "upper direction selected");
                checkWeights(ids, weights);
                reconstructL1(ids, weights, reconstructed);
                reconstructed.normalizeLocal();
                direction.normalizeLocal();
                check(reconstructed.dot(direction) > 1f - 3e-6f, "barycentric direction reconstruction");
            }
        }
        Vector3f[] aroundPole = {
            new Vector3f(1e-7f, 1f, 0), new Vector3f(0, 1f, 1e-7f),
            new Vector3f(-1e-7f, 1f, 0), new Vector3f(0, 1f, -1e-7f),
            new Vector3f(1e-7f, 1f, 1e-7f)
        };
        for (Vector3f nearPole : aroundPole) {
            check(ConiferImpostorFrames.select(nearPole, ids, weights), "near-pole direction selected");
            checkWeights(ids, weights);
            check(contains(ids, ConiferImpostorFrames.ZENITH_INDEX), "near-pole fan uses zenith sample");
        }
    }

    private static void checkEdgesAndYaw() {
        int[] ids = new int[3], rotatedIds = new int[3];
        float[] weights = new float[3], rotatedWeights = new float[3];
        Vector3f[] horizon = {
            new Vector3f(1, 0, 0), new Vector3f(-1, 0, 0),
            new Vector3f(0, 0, 1), new Vector3f(0, 0, -1),
            new Vector3f(1, 0, 1), new Vector3f(-1, 0, -1)
        };
        for (Vector3f direction : horizon) {
            check(ConiferImpostorFrames.select(direction, ids, weights), "horizon is included");
            checkWeights(ids, weights);
        }
        check(!ConiferImpostorFrames.select(new Vector3f(.1f, -1e-7f, .2f), ids, weights),
                "below horizon selects mesh fallback");
        check(ids[0] == -1 && weights[0] == 0, "fallback clears prior output data");

        Vector3f original = new Vector3f(.37f, .63f, -.41f).normalizeLocal();
        float yaw = 2.31f, cos = (float) Math.cos(yaw), sin = (float) Math.sin(yaw);
        Vector3f rotated = new Vector3f(cos * original.x + sin * original.z, original.y,
                -sin * original.x + cos * original.z);
        // Convert the rotated world ray back to the instance's source-local frame.
        Vector3f local = new Vector3f(cos * rotated.x - sin * rotated.z, rotated.y,
                sin * rotated.x + cos * rotated.z);
        check(ConiferImpostorFrames.select(original, ids, weights), "original yaw ray selected");
        check(ConiferImpostorFrames.select(local, rotatedIds, rotatedWeights), "inverse-yaw ray selected");
        for (int i = 0; i < 3; i++) {
            check(ids[i] == rotatedIds[i] && Math.abs(weights[i] - rotatedWeights[i]) < 2e-6,
                    "yaw-local selection stability");
        }
    }

    private static void reconstructL1(int[] ids, float[] weights, Vector3f store) {
        store.zero();
        Vector3f direction = new Vector3f();
        for (int i = 0; i < 3; i++) {
            ConiferImpostorFrames.direction(ids[i], direction);
            float l1 = Math.abs(direction.x) + Math.abs(direction.y) + Math.abs(direction.z);
            store.x += weights[i] * direction.x / l1;
            store.y += weights[i] * direction.y / l1;
            store.z += weights[i] * direction.z / l1;
        }
    }

    private static void checkWeights(int[] ids, float[] weights) {
        float sum = 0;
        for (int i = 0; i < 3; i++) {
            check(ids[i] >= 0 && ids[i] < ConiferImpostorFrames.COUNT, "valid selected layer");
            check(Float.isFinite(weights[i]) && weights[i] >= 0 && weights[i] <= 1,
                    "finite nonnegative barycentric weight");
            sum += weights[i];
        }
        check(Math.abs(sum - 1f) < TOLERANCE, "weights sum to one");
    }

    private static boolean oneHot(int[] ids, float[] weights, int layer) {
        for (int i = 0; i < 3; i++) {
            if (ids[i] == layer && weights[i] > 1f - TOLERANCE) {
                for (int j = 0; j < 3; j++) if (i != j && weights[j] > TOLERANCE) return false;
                return true;
            }
        }
        return false;
    }

    private static boolean contains(int[] ids, int value) {
        return ids[0] == value || ids[1] == value || ids[2] == value;
    }

    private static void checkInvalidInputs() {
        expectFailure(() -> ConiferImpostorFrames.select(new Vector3f(), new int[3], new float[3]),
                "zero vector");
        expectFailure(() -> ConiferImpostorFrames.select(new Vector3f(Float.NaN, 1, 0), new int[3], new float[3]),
                "non-finite vector");
        expectFailure(() -> ConiferImpostorFrames.select(Vector3f.UNIT_Y, new int[2], new float[3]),
                "short ID output");
        expectFailure(() -> ConiferImpostorFrames.direction(-1, new Vector3f()), "bad frame index");
        expectFailure(() -> ConiferImpostorFrames.right(65, new Vector3f()), "bad basis index");
    }

    private static void expectFailure(Runnable action, String label) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("expected invalid input failure: " + label);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
