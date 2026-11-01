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
package jme3test.terrain;

import com.jme3.bounding.BoundingBox;
import com.jme3.math.Matrix4f;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;
import java.util.Arrays;
import jme3test.terrain.forest.ConiferImpostor;
import jme3test.terrain.forest.ConiferImpostorFrames;

/**
 * Conservative demand for the finite terrain/forest scene. Camera-visible cells
 * come first, then cells that can cast into any potentially visible terrain or
 * tree receiver, then background prefetch. Whole-scene height bounds deliberately
 * overestimate demand; this is not an optimized independent shadow LOD cover.
 */
final class RegionalForestDemand {
    private static final float PAGE_SIZE_METRES = RegionalForestPages.PAGE_SIZE_METRES;
    private static final float MAX_IMPOSTOR_SCALE = 1.15f;
    private static final float HORIZONTAL_RECEIVER_MARGIN = (float) Math.ceil(
            ConiferImpostor.BOUND_RADIUS * MAX_IMPOSTOR_SCALE);
    private static final float RECEIVER_HALF_EXTENT = PAGE_SIZE_METRES * .5f
            + HORIZONTAL_RECEIVER_MARGIN;
    private static final float RECEIVER_BELOW_ROOT = (float) Math.ceil(
            (ConiferImpostor.BOUND_RADIUS - ConiferImpostorFrames.CENTER_Y)
                    * MAX_IMPOSTOR_SCALE) + 2f;
    private static final float RECEIVER_ABOVE_ROOT = (float) Math.ceil(
            (ConiferImpostorFrames.CENTER_Y + ConiferImpostor.BOUND_RADIUS)
                    * MAX_IMPOSTOR_SCALE) + 2f;

    static final class Cell implements Comparable<Cell> {
        final int x;
        final int z;
        final int index;
        int tier;
        float distance;

        Cell(int x, int z, int index) {
            this.x = x;
            this.z = z;
            this.index = index;
        }

        @Override
        public int compareTo(Cell other) {
            int order = Integer.compare(tier, other.tier);
            if (order == 0) {
                order = Float.compare(distance, other.distance);
            }
            return order == 0 ? Integer.compare(index, other.index) : order;
        }
    }

    private final Cell[] cells;
    private final BoundingBox box = new BoundingBox();
    private final Matrix4f previousView = new Matrix4f();
    private final Vector3f previousSun = new Vector3f();
    private boolean initialized;
    private float previousOrigin;
    private float previousMin;
    private float previousMax;
    private int visibleCount;
    private int casterCount;

    RegionalForestDemand(int side) {
        if (side < 1 || side > 32) {
            throw new IllegalArgumentException("supported source has 1..32 cells per axis");
        }
        cells = new Cell[side * side];
        for (int z = 0; z < side; z++) {
            for (int x = 0; x < side; x++) {
                int index = z * side + x;
                cells[index] = new Cell(x, z, index);
            }
        }
    }

    Cell[] update(Camera camera, float origin, float minimumHeight, float maximumHeight,
            Vector3f sun) {
        Matrix4f view = camera.getViewProjectionMatrix();
        if (initialized && previousView.equals(view) && previousSun.equals(sun)
                && previousOrigin == origin && previousMin == minimumHeight
                && previousMax == maximumHeight) {
            return cells;
        }
        previousView.set(view);
        previousSun.set(sun);
        previousOrigin = origin;
        previousMin = minimumHeight;
        previousMax = maximumHeight;
        initialized = true;

        float minY = minimumHeight - RECEIVER_BELOW_ROOT;
        float maxY = maximumHeight + RECEIVER_ABOVE_ROOT;
        box.setYExtent((maxY - minY) * .5f);
        box.setXExtent(RECEIVER_HALF_EXTENT);
        box.setZExtent(RECEIVER_HALF_EXTENT);
        float receiverMinX = Float.POSITIVE_INFINITY;
        float receiverMaxX = Float.NEGATIVE_INFINITY;
        float receiverMinZ = Float.POSITIVE_INFINITY;
        float receiverMaxZ = Float.NEGATIVE_INFINITY;
        int oldState = camera.getPlaneState();
        visibleCount = 0;
        casterCount = 0;
        try {
            for (Cell cell : cells) {
                float x = origin + (cell.x + .5f) * PAGE_SIZE_METRES;
                float z = origin + (cell.z + .5f) * PAGE_SIZE_METRES;
                box.getCenter().set(x, (minY + maxY) * .5f, z);
                camera.setPlaneState(0);
                boolean visible = camera.contains(box) != Camera.FrustumIntersect.Outside;
                cell.tier = visible ? 0 : 2;
                float dx = Math.max(0, Math.abs(camera.getLocation().x - x)
                        - RECEIVER_HALF_EXTENT);
                float dz = Math.max(0, Math.abs(camera.getLocation().z - z)
                        - RECEIVER_HALF_EXTENT);
                cell.distance = dx * dx + dz * dz;
                if (visible) {
                    visibleCount++;
                    receiverMinX = Math.min(receiverMinX, x - RECEIVER_HALF_EXTENT);
                    receiverMaxX = Math.max(receiverMaxX, x + RECEIVER_HALF_EXTENT);
                    receiverMinZ = Math.min(receiverMinZ, z - RECEIVER_HALF_EXTENT);
                    receiverMaxZ = Math.max(receiverMaxZ, z + RECEIVER_HALF_EXTENT);
                }
            }
        } finally {
            camera.setPlaneState(oldState);
        }

        if (visibleCount > 0) {
            // Sweep the union of every possibly visible receiver along the
            // sun-ray direction across the full conservative vertical span.
            float reach = (maxY - minY) / Math.max(.0001f, -sun.y);
            float sweepX = -sun.x * reach;
            float sweepZ = -sun.z * reach;
            float minX = receiverMinX + Math.min(0, sweepX);
            float maxX = receiverMaxX + Math.max(0, sweepX);
            float minZ = receiverMinZ + Math.min(0, sweepZ);
            float maxZ = receiverMaxZ + Math.max(0, sweepZ);
            for (Cell cell : cells) {
                if (cell.tier == 0) {
                    continue;
                }
                float x = origin + (cell.x + .5f) * PAGE_SIZE_METRES;
                float z = origin + (cell.z + .5f) * PAGE_SIZE_METRES;
                if (x + RECEIVER_HALF_EXTENT >= minX && x - RECEIVER_HALF_EXTENT <= maxX
                        && z + RECEIVER_HALF_EXTENT >= minZ && z - RECEIVER_HALF_EXTENT <= maxZ) {
                    cell.tier = 1;
                    casterCount++;
                }
            }
        }
        Arrays.sort(cells);
        return cells;
    }

    int getVisibleCount() {
        return visibleCount;
    }

    int getCasterCount() {
        return casterCount;
    }
}
