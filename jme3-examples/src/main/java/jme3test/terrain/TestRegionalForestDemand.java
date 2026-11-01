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
import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;

/** Finite CPU checks for regional visible/caster page demand ordering. */
public final class TestRegionalForestDemand {
    private TestRegionalForestDemand() { }

    public static void main(String[] args) {
        Vector3f sun = new Vector3f(-.98f, -.2f, 0).normalizeLocal();
        RegionalForestDemand demand = new RegionalForestDemand(8);
        Camera camera = overview(192, 192);
        camera.setPlaneState(0x35);
        RegionalForestDemand.Cell[] first = demand.update(camera, 0, 0, 0, sun);
        check(camera.getPlaneState() == 0x35, "camera plane state is restored");
        check(demand.getVisibleCount() > 0, "overview finds visible receiver cells");
        check(find(first, 1, 1).tier == 0, "camera-visible cell has highest priority");
        check(find(first, 3, 1).tier == 1,
                "off-camera page up-sun from visible receiver is retained as a caster");
        check(isOutside(camera, 3, 1), "caster fixture is outside the camera frustum");
        check(find(first, 7, 7).tier == 2, "remote page remains background demand");
        checkSorted(first);
        check(positionOf(first, 0) < positionOf(first, 2),
                "equal-priority, equal-distance pages use stable index tie order");

        CellSnapshot[] stable = snapshot(first);
        RegionalForestDemand.Cell[] repeated = demand.update(camera, 0, 0, 0, sun);
        check(first == repeated, "same view reuses the demand array");
        checkSame(stable, repeated, "same-view result remains stable");

        checkOriginTranslation(sun, stable);
        RegionalForestDemand.Cell[] changedSun = demand.update(camera, 0, 0, 0,
                new Vector3f(.98f, -.2f, 0).normalizeLocal());
        check(find(changedSun, 3, 1).tier == 2,
                "reversing sunlight removes the previous up-sun caster demand");
        check(find(changedSun, 0, 1).tier == 1,
                "reversing sunlight adds the opposite up-sun caster demand");

        Camera moved = overview(704, 704);
        RegionalForestDemand.Cell[] changedCamera = demand.update(moved, 0, 0, 0, sun);
        check(find(changedCamera, 5, 5).tier == 0, "changed camera refreshes visible-cell demand");
        check(find(changedCamera, 1, 1).tier != 0, "previous view cell is no longer visible");
        checkSorted(changedCamera);
        System.out.println("REGIONAL_FOREST_DEMAND_CPU_PASS visible=" + demand.getVisibleCount()
                + " casters=" + demand.getCasterCount() + " cells=" + changedCamera.length);
    }

    private static void checkOriginTranslation(Vector3f sun, CellSnapshot[] expected) {
        float origin = -2048;
        RegionalForestDemand shifted = new RegionalForestDemand(8);
        Camera camera = overview(origin + 192, origin + 192);
        RegionalForestDemand.Cell[] actual = shifted.update(camera, origin, 0, 0, sun);
        checkSame(expected, actual, "moving the source and camera together preserves page demand");
    }

    private static Camera overview(float centerX, float centerZ) {
        Camera camera = new Camera(640, 640);
        camera.setParallelProjection(true);
        camera.setFrustum(1, 220, -32, 32, 32, -32);
        camera.setLocation(new Vector3f(centerX, 100, centerZ));
        camera.lookAtDirection(new Vector3f(0, -1, 0), new Vector3f(0, 0, -1));
        camera.update();
        return camera;
    }

    private static boolean isOutside(Camera camera, int cellX, int cellZ) {
        int oldState = camera.getPlaneState();
        try {
            camera.setPlaneState(0);
            BoundingBox bounds = new BoundingBox(new Vector3f((cellX + .5f) * 128, 10,
                    (cellZ + .5f) * 128), 84, 22, 84);
            return camera.contains(bounds) == Camera.FrustumIntersect.Outside;
        } finally {
            camera.setPlaneState(oldState);
        }
    }

    private static RegionalForestDemand.Cell find(RegionalForestDemand.Cell[] cells, int x, int z) {
        int index = z * 8 + x;
        for (RegionalForestDemand.Cell cell : cells) if (cell.index == index) return cell;
        throw new IllegalStateException("missing cell " + x + "," + z);
    }

    private static int positionOf(RegionalForestDemand.Cell[] cells, int index) {
        for (int i = 0; i < cells.length; i++) if (cells[i].index == index) return i;
        throw new IllegalStateException("missing cell index " + index);
    }

    private static CellSnapshot[] snapshot(RegionalForestDemand.Cell[] cells) {
        CellSnapshot[] result = new CellSnapshot[cells.length];
        for (int i = 0; i < cells.length; i++) result[i] = new CellSnapshot(cells[i]);
        return result;
    }

    private static void checkSame(CellSnapshot[] expected, RegionalForestDemand.Cell[] actual, String message) {
        check(expected.length == actual.length, message + " count");
        for (int i = 0; i < actual.length; i++) {
            check(expected[i].index == actual[i].index && expected[i].tier == actual[i].tier
                    && Float.floatToIntBits(expected[i].distance) == Float.floatToIntBits(actual[i].distance),
                    message + " at result " + i);
        }
    }

    private static void checkSorted(RegionalForestDemand.Cell[] cells) {
        for (int i = 1; i < cells.length; i++) {
            check(cells[i - 1].compareTo(cells[i]) <= 0, "stable tier/distance/index order");
        }
    }

    private static void check(boolean valid, String message) {
        if (!valid) throw new IllegalStateException(message);
    }

    private static final class CellSnapshot {
        final int index, tier;
        final float distance;
        CellSnapshot(RegionalForestDemand.Cell cell) {
            index = cell.index; tier = cell.tier; distance = cell.distance;
        }
    }
}
