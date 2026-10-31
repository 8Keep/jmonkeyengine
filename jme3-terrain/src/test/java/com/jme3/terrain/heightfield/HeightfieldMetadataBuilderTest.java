package com.jme3.terrain.heightfield;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeightfieldMetadataBuilderTest {
    @Test
    void metadataMatchesExhaustiveOracleAcrossSurfaceShapesAndBoundaries() {
        for (int cells : new int[]{8, 16, 32}) {
            for (int patch : new int[]{2, 4, 8}) {
                if (patch > cells) continue;
                for (int shape = 0; shape < 5; shape++) {
                    HeightfieldSource source = source(cells, shape);
                    HeightfieldHierarchy oracle = HeightfieldHierarchy.fromSource(source,
                            patch, 27, .125);
                    HeightfieldHierarchy actual = HeightfieldMetadataBuilder.build(source,
                            patch, 27, .125);
                    compare(oracle, actual);
                }
            }
        }
    }

    @Test
    void separatePointSourceReadsOnlyCoarseGridsAndFineSamplesOncePerLeaf() {
        int cells = 16, patch = 4;
        AtomicLong fineReads = new AtomicLong();
        AtomicLong pointReads = new AtomicLong();
        HeightfieldSource fine = countingSource(cells, fineReads);
        HeightfieldSource points = countingSource(cells, pointReads);
        HeightfieldHierarchy hierarchy = HeightfieldMetadataBuilder.build(fine, points,
                patch, 4, 0);
        long leaves = 16;
        assertEquals(leaves * (patch + 1L) * (patch + 1L), fineReads.get());
        // Five coarse grids, plus three source probes per duplicated leaf
        // sample on the right/top outer edges for exact stride-one arithmetic.
        assertEquals(5L * (patch + 1L) * (patch + 1L) + 39L * 3, pointReads.get());
        assertEquals(21, hierarchy.getNodes().size());
    }

    @Test
    void validatesDimensionsSourcesSamplesAndBoundedScratchBeforeScanning() {
        HeightfieldSource source = source(16, 0);
        assertThrows(IllegalArgumentException.class,
                () -> HeightfieldMetadataBuilder.build(null, 4, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> HeightfieldMetadataBuilder.build(source, source(8, 0), 4, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> HeightfieldMetadataBuilder.build(source, 512, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> HeightfieldMetadataBuilder.build(source, 4, -1, 0));
        assertThrows(IllegalArgumentException.class,
                () -> HeightfieldMetadataBuilder.build(source, 4, 0, Double.NaN));
        assertTrue(HeightfieldMetadataBuilder.maximumGridScratchBytes(1 << 17, 256)
                <= 4L * 1024 * 1024, "active ancestor grids have a fixed scratch ceiling");

        AtomicLong reads = new AtomicLong();
        HeightfieldSource large = countingSource(1 << 20, reads);
        assertThrows(IllegalArgumentException.class,
                () -> HeightfieldMetadataBuilder.build(large, 2, 0, 0));
        assertEquals(0, reads.get(), "node count is rejected before source sampling or scratch allocation");

        HeightfieldSource invalid = new HeightfieldSource() {
            @Override public int getCells() { return 4; }
            @Override public float getSampleSpacing() { return 1; }
            @Override public float getHeight(int x, int z) { return Float.NaN; }
        };
        assertThrows(IllegalArgumentException.class,
                () -> HeightfieldMetadataBuilder.build(invalid, 2, 0, 0));
    }

    @Test
    void cancellationIsObservedBeforeSampling() {
        AtomicLong reads = new AtomicLong();
        HeightfieldSource source = countingSource(16, reads);
        Thread.currentThread().interrupt();
        try {
            assertThrows(CancellationException.class,
                    () -> HeightfieldMetadataBuilder.build(source, 4, 0, 0));
        } finally {
            Thread.interrupted();
        }
        assertEquals(0, reads.get());
    }

    private static void compare(HeightfieldHierarchy expected, HeightfieldHierarchy actual) {
        assertEquals(expected.getCells(), actual.getCells());
        assertEquals(expected.getPatchCells(), actual.getPatchCells());
        assertEquals(expected.getSampleSpacing(), actual.getSampleSpacing());
        assertEquals(expected.getSourceRevision(), actual.getSourceRevision());
        assertTrue(!actual.hasCanonicalPage());
        List<HeightfieldHierarchy.Node> a = expected.getNodes(), b = actual.getNodes();
        assertEquals(a.size(), b.size());
        for (int i = 0; i < a.size(); i++) {
            HeightfieldHierarchy.Node left = a.get(i), right = b.get(i);
            assertEquals(left.getOriginX(), right.getOriginX());
            assertEquals(left.getOriginZ(), right.getOriginZ());
            assertEquals(left.getSpan(), right.getSpan());
            assertEquals(left.getStride(), right.getStride());
            HeightfieldHierarchy.NodeMetadata lm = left.getMetadata(), rm = right.getMetadata();
            assertEquals(lm.getMinimumHeight(), rm.getMinimumHeight());
            assertEquals(lm.getMaximumHeight(), rm.getMaximumHeight());
            assertEquals(lm.getStableSurfaceError(), rm.getStableSurfaceError(), 0,
                    "stable error at " + left.getOriginX() + "," + left.getOriginZ());
            assertEquals(lm.getParentSurfaceError(), rm.getParentSurfaceError(), 0,
                    "parent error at " + left.getOriginX() + "," + left.getOriginZ());
        }
    }

    private static HeightfieldSource source(int cells, int shape) {
        return new HeightfieldSource() {
            @Override public int getCells() { return cells; }
            @Override public float getSampleSpacing() { return .375f; }
            @Override public float getHeight(int x, int z) {
                switch (shape) {
                    case 0: return (float) (2.25 + x * .375 - z * .625);
                    case 1: return (float) (Math.sin(x * .83) * Math.cos(z * .47) * 19);
                    case 2: return ((x * 37 + z * 19 + x * z * 3) % 31 - 15) * 1.125f;
                    case 3:
                        return ((x + 2 * z) & 1) == 0 ? 1.0e20f + x * 1.0e13f
                                : -1.0e20f + z * 1.0e13f;
                    default:
                        if (x == cells - 1) return 1.0e20f;
                        if (x == cells) return 1f;
                        return (x - z) * .5f;
                }
            }
        };
    }

    private static HeightfieldSource countingSource(int cells, AtomicLong reads) {
        return new HeightfieldSource() {
            @Override public int getCells() { return cells; }
            @Override public float getSampleSpacing() { return 1; }
            @Override public float getHeight(int x, int z) {
                reads.incrementAndGet();
                return (x * 3 - z * 2 + x * z) * .25f;
            }
        };
    }
}
