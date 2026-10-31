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
import com.jme3.terrain.heightfield.HeightfieldPage;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Finite headless contract check for bounded asynchronous regional page preparation. */
public final class TestRegionalPagePreparation {
    private static final int PATCH_CELLS = 64;

    private TestRegionalPagePreparation() {
    }

    public static void main(String[] args) throws Exception {
        HeightfieldHierarchy revision0 = hierarchy(0);
        HeightfieldHierarchy revision1 = hierarchy(1);
        RegionalHeightfieldSource expected0 = new RegionalHeightfieldSource(0);
        RegionalHeightfieldSource expected1 = new RegionalHeightfieldSource(1);
        RegionalPagePreparation preparation = new RegionalPagePreparation(PATCH_CELLS, 2);
        try {
            Future<HeightfieldPage> root0 = preparation.submit(revision0.getRoot(), 0);
            Future<HeightfieldPage> queued = preparation.submit(revision0.getRoot(), 0);
            preparation.cancel(queued);
            require(queued.isDone() && queued.isCancelled(), "cancel removed queued page work");
            HeightfieldPage coarse = root0.get(10, TimeUnit.SECONDS);
            require(coarse.getCells() == PATCH_CELLS && coarse.getSampleSpacing() == 8f
                    && coarse.hasSourceGradients(), "coarse page dimensions, spacing, or canonical gradients");
            compareSample(coarse, expected0, 35, 27, 0, 0, 16);
            require(coarse.getHeight(-1, -1) == expected0.getHeight(-1, -1),
                    "low-side halo clamps to the source halo");
            require(coarse.getHeight(PATCH_CELLS + 1, PATCH_CELLS + 1)
                    == expected0.getHeight(1025, 1025), "high-side halo clamps to the source halo");

            HeightfieldPage edited = preparation.submit(revision1.getRoot(), 1).get(10, TimeUnit.SECONDS);
            compareSample(edited, expected1, 35, 27, 0, 0, 16);
            require(edited.getHeight(35, 27) > coarse.getHeight(35, 27),
                    "revision one contains the regional edit");

            List<HeightfieldHierarchy.Node> nodes = revision0.getNodes();
            HeightfieldHierarchy.Node fineNode = nodes.get(nodes.size() - 1);
            require(fineNode.getStride() == 1, "metadata fixture has a stride-one leaf");
            HeightfieldPage fine = preparation.submit(fineNode, 0).get(10, TimeUnit.SECONDS);
            require(fine.getSampleSpacing() == .5f && fine.hasSourceGradients(),
                    "fine page preserves canonical spacing and gradients");
            compareSample(fine, expected0, 23, 19, fineNode.getOriginX(), fineNode.getOriginZ(), 1);
            preparation.assertBounds();
            checkOwnerThreadGuard(preparation);
            preparation.printStats();
        } finally {
            preparation.close();
        }

        verifyCloseCompletesFutures();
        verifyBeyondDefaultBoundary();
        System.out.println("PASS regional page preparation");
    }

    private static void verifyBeyondDefaultBoundary() throws Exception {
        HeightfieldHierarchy hierarchy = HeightfieldHierarchy.fromMetadata(2048, 64, .5f, 9, 0,
                (x, z, span, stride) -> new HeightfieldHierarchy.NodeMetadata(0, 736,
                        stride == 1 ? 0 : 736, stride == 32 ? 0 : 736));
        HeightfieldHierarchy.Node target = null;
        for (HeightfieldHierarchy.Node node : hierarchy.getNodes()) {
            if (node.getOriginX() == 1024 && node.getOriginZ() == 0 && node.getStride() == 1) {
                target = node;
                break;
            }
        }
        require(target != null, "2048 metadata contains a stride-one node beyond x=1024");
        RegionalHeightfieldSource expected = new RegionalHeightfieldSource(2048, 9);
        try (RegionalPagePreparation preparation = new RegionalPagePreparation(64, 2, 2048)) {
            HeightfieldPage page = preparation.submit(target, 9).get(10, TimeUnit.SECONDS);
            require(page.getCells() == 64 && page.getSampleSpacing() == .5f
                    && page.hasSourceGradients(), "extended page dimensions and gradients are retained");
            require(page.getHeight(0, 0) == expected.getHeight(1024, 0)
                    && page.getHeight(64, 64) == expected.getHeight(1088, 64),
                    "worker source prepares a node page beyond the old 1024-cell extent");
            require(page.getHeight(65, 0) == expected.getHeight(1089, 0),
                    "extended worker page includes its outer halo");
            preparation.assertBounds();
        }
    }

    private static HeightfieldHierarchy hierarchy(long revision) {
        return HeightfieldHierarchy.fromMetadata(1024, PATCH_CELLS, .5f, revision, 0,
                (x, z, span, stride) -> new HeightfieldHierarchy.NodeMetadata(0, 400, 0, 0));
    }

    private static void compareSample(HeightfieldPage page, RegionalHeightfieldSource source,
            int x, int z, int originX, int originZ, int stride) {
        int globalX = originX + x * stride;
        int globalZ = originZ + z * stride;
        require(page.getHeight(x, z) == source.getHeight(globalX, globalZ), "page height matches source revision");
        float gradientX = source.getHeight(globalX - 1, globalZ) * .5f
                - source.getHeight(globalX + 1, globalZ) * .5f;
        float gradientZ = source.getHeight(globalX, globalZ - 1) * .5f
                - source.getHeight(globalX, globalZ + 1) * .5f;
        require(page.getSourceGradientX(x, z) == gradientX
                && page.getSourceGradientZ(x, z) == gradientZ, "page gradient matches canonical source differences");
    }

    private static void checkOwnerThreadGuard(RegionalPagePreparation preparation) throws InterruptedException {
        AtomicBoolean rejected = new AtomicBoolean();
        Thread wrongThread = new Thread(() -> {
            try {
                preparation.assertBounds();
            } catch (IllegalStateException expected) {
                rejected.set(true);
            }
        }, "regional-page-owner-guard-check");
        wrongThread.start();
        wrongThread.join(2000);
        require(!wrongThread.isAlive() && rejected.get(), "owner-only API rejected another thread");
    }

    private static void verifyCloseCompletesFutures() {
        HeightfieldHierarchy hierarchy = HeightfieldHierarchy.fromMetadata(1024, 256, .5f, 2, 0,
                (x, z, span, stride) -> new HeightfieldHierarchy.NodeMetadata(0, 400, 0, 0));
        RegionalPagePreparation preparation = new RegionalPagePreparation(256, 2);
        Future<HeightfieldPage> running = preparation.submit(hierarchy.getRoot(), 2);
        Future<HeightfieldPage> queued = preparation.submit(hierarchy.getRoot(), 2);
        preparation.close();
        require(running.isDone() && queued.isDone(), "close stops running and queued futures");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
