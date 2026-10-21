package com.jme3.terrain.heightfield;

import com.jme3.bounding.BoundingBox;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeightfieldHierarchyMetadataTest {
    @Test void metadataTreeMatchesDenseTopologyErrorsAndBounds() {
        int cells = 16, patch = 4;
        float spacing = .3f;
        float[] samples = new float[(cells + 3) * (cells + 3)];
        for (int z = -1; z <= cells + 1; z++) for (int x = -1; x <= cells + 1; x++) {
            samples[(z + 1) * (cells + 3) + x + 1] = (float) (Math.sin(x * .7) * Math.cos(z * .4) * 12);
        }
        HeightfieldPage page = new HeightfieldPage(cells, spacing, samples);
        HeightfieldHierarchy dense = new HeightfieldHierarchy(page, patch, .125);
        HeightfieldHierarchy measured = HeightfieldHierarchy.fromSource(page, patch, 73, .125);
        List<HeightfieldHierarchy.Node> saved = measured.getNodes();
        AtomicInteger calls = new AtomicInteger();
        HeightfieldHierarchy metadata = HeightfieldHierarchy.fromMetadata(cells, patch, spacing, 73, .125,
                (x, z, span, stride) -> {
                    HeightfieldHierarchy.Node savedNode = saved.get(calls.getAndIncrement());
                    assertEquals(x, savedNode.getOriginX());
                    assertEquals(z, savedNode.getOriginZ());
                    assertEquals(span, savedNode.getSpan());
                    assertEquals(stride, savedNode.getStride());
                    return savedNode.getMetadata();
                });
        assertEquals(dense.getNodes().size(), metadata.getNodes().size());
        for (int i = 0; i < dense.getNodes().size(); i++) {
            HeightfieldHierarchy.Node a = dense.getNodes().get(i), b = metadata.getNodes().get(i);
            assertEquals(a.getOriginX(), b.getOriginX());
            assertEquals(a.getOriginZ(), b.getOriginZ());
            assertEquals(a.getSpan(), b.getSpan());
            assertEquals(a.getStableError(), b.getStableError());
            assertEquals(a.getMorphError(), b.getMorphError());
            BoundingBox ab = a.getBound(), bb = b.getBound();
            assertEquals(ab.getCenter(), bb.getCenter());
            assertEquals(ab.getXExtent(), bb.getXExtent());
            assertEquals(ab.getYExtent(), bb.getYExtent());
            assertEquals(ab.getZExtent(), bb.getZExtent());
        }
        int constructedCalls = calls.get();
        metadata.getNodes();
        assertEquals(constructedCalls, calls.get());
        assertTrue(dense.hasCanonicalPage());
        assertEquals(page, dense.getPage());
        assertTrue(!measured.hasCanonicalPage() && !metadata.hasCanonicalPage());
        assertThrows(IllegalStateException.class, metadata::getPage);
        assertEquals(0, dense.getSourceRevision());
        assertEquals(73, metadata.getSourceRevision());
    }

    @Test void metadataOnlyPageAndInvalidMetadataAreRejectedClearly() {
        HeightfieldHierarchy hierarchy = HeightfieldHierarchy.fromMetadata(8, 2, 1, 9, 0,
                (x, z, span, stride) -> new HeightfieldHierarchy.NodeMetadata(0, 1, 0, 0));
        assertTrue(!hierarchy.hasCanonicalPage());
        assertThrows(IllegalStateException.class, hierarchy::getPage);
        assertThrows(IllegalArgumentException.class, () -> HeightfieldHierarchy.fromMetadata(8, 2, 1, 0, 0,
                (x, z, span, stride) -> new HeightfieldHierarchy.NodeMetadata(2, 1, 0, 0)));
        assertThrows(IllegalArgumentException.class, () -> HeightfieldHierarchy.fromMetadata(8, 2, 1, 0, 0,
                (x, z, span, stride) -> new HeightfieldHierarchy.NodeMetadata(0, 1, Double.NaN, 0)));
        assertThrows(IllegalArgumentException.class, () -> HeightfieldHierarchy.fromMetadata(8, 2, 1, 0, 0,
                (x, z, span, stride) -> new HeightfieldHierarchy.NodeMetadata(x == 0 && z == 0 && span == 8 ? 0 : 2,
                        x == 0 && z == 0 && span == 8 ? 1 : 2, 0, 0)));
        assertThrows(IllegalArgumentException.class, () -> HeightfieldHierarchy.fromMetadata(1 << 20, 2, 1, 0, 0,
                (x, z, span, stride) -> new HeightfieldHierarchy.NodeMetadata(0, 0, 0, 0)));
        assertThrows(IllegalArgumentException.class, () -> HeightfieldHierarchy.fromMetadata(8, 2, 1, -1, 0,
                (x, z, span, stride) -> new HeightfieldHierarchy.NodeMetadata(0, 0, 0, 0)));
    }
}
