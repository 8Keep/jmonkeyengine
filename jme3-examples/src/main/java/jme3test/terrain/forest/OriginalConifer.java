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
package jme3test.terrain.forest;

import com.jme3.bounding.BoundingBox;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.scene.Mesh;
import com.jme3.scene.VertexBuffer;
import com.jme3.util.BufferUtils;
import java.util.Random;

/** Original deterministic, two-LOD conifer geometry, measured in metres. */
public final class OriginalConifer {
    public static final float HEIGHT = 18f;
    public static final float CROWN_RADIUS = 4f;
    private static final int BRANCH_LEVELS = 14;
    private static final int NEAR_BRANCH_SIDES = 4;
    private static final ColorRGBA BARK = new ColorRGBA(.30f, .19f, .10f, 1f);
    private static final ColorRGBA BRANCH_BARK = new ColorRGBA(.35f, .22f, .12f, 1f);
    // Linear tints stay close to neutral so the sRGB frond image remains bright.
    private static final ColorRGBA NEEDLES_A = new ColorRGBA(.70f, .85f, .68f, 1f);
    private static final ColorRGBA NEEDLES_B = new ColorRGBA(.82f, .90f, .72f, 1f);
    private static final ColorRGBA NEEDLES_C = new ColorRGBA(.65f, .80f, .63f, 1f);

    private OriginalConifer() {}

    /** Build immutable near and mid meshes with a stable seed and shared pivot. */
    public static Asset build(long seed) {
        return new Asset(seed, buildNear(seed), buildMid(seed));
    }

    /** Holder for meshes and their shared conservative tree envelope. */
    public static final class Asset {
        private final long seed;
        private final Mesh near;
        private final Mesh mid;
        private final BoundingBox bounds = new BoundingBox(
                new Vector3f(0, HEIGHT * .5f, 0), CROWN_RADIUS, HEIGHT * .5f, CROWN_RADIUS);
        private Asset(long seed, Mesh near, Mesh mid) {
            this.seed = seed; this.near = near; this.mid = mid;
        }
        public long getSeed() { return seed; }
        public Mesh getNearMesh() { return near; }
        public Mesh getMidMesh() { return mid; }
        /** Returns a copy; both LODs use this same base-origin envelope. */
        public BoundingBox getBounds() { return (BoundingBox) bounds.clone(); }
    }

    private static Mesh buildNear(long seed) {
        MeshBuilder b = new MeshBuilder();
        addTrunk(b, 8, 8, new Random(seed));
        for (BranchSpec branch : branchSkeleton(seed)) addNearBranch(b, branch);
        addLeader(b, new Random(seed ^ 0x243f6a8885a308d3L));
        return b.finish();
    }

    private static Mesh buildMid(long seed) {
        MeshBuilder b = new MeshBuilder();
        addTrunk(b, 6, 4, new Random(seed));
        for (BranchSpec branch : branchSkeleton(seed)) addMidBranch(b, branch);
        addLeader(b, new Random(seed ^ 0x243f6a8885a308d3L));
        return b.finish();
    }

    private static BranchSpec[] branchSkeleton(long seed) {
        BranchSpec[] result = new BranchSpec[BRANCH_LEVELS * 6];
        int index = 0;
        for (int level = 0; level < BRANCH_LEVELS; level++) {
            double t = level / (double) (BRANCH_LEVELS - 1);
            Random random = new Random(seed ^ (0x9e3779b97f4a7c15L * (level + 1L)));
            float levelY = (float) (1.15 + t * 16.45 + (random.nextDouble() - .5) * .60);
            // A broad middle skirt narrows into the leader instead of ending as
            // equal-width stacked whorls. Keep the primary limb tips inside 4m.
            float radial = (float) (3.30 * Math.pow(1.0 - t, .88) + .13);
            double phase = random.nextDouble() * Math.PI * 2;
            int directions = level % 3 == 1 ? 6 : 5;
            for (int direction = 0; direction < directions; direction++) {
                double angle = phase + 2 * Math.PI * direction / directions
                        + (random.nextDouble() - .5) * .28;
                float length = radial * (float) (.94 + random.nextDouble() * .12);
                float droop = .12f + (float) random.nextDouble() * .34f;
                float y = Math.min(17.85f, Math.max(.7f,
                        levelY + (float) (random.nextDouble() - .5) * 1.20f));
                long branchSeed = random.nextLong();
                result[index++] = new BranchSpec(level, angle, y, length, droop, branchSeed);
            }
        }
        return java.util.Arrays.copyOf(result, index);
    }

    private static void addTrunk(MeshBuilder b, int sides, int rings, Random random) {
        float phase = (float) (random.nextDouble() * Math.PI * 2);
        for (int ring = 0; ring < rings; ring++) {
            float y0 = HEIGHT * ring / rings, y1 = HEIGHT * (ring + 1) / rings;
            float r0 = .245f * (1 - y0 / (HEIGHT * 1.12f)) + .032f;
            float r1 = .245f * (1 - y1 / (HEIGHT * 1.12f)) + .032f;
            addFrustum(b, new Vector3f(0, y0, 0), new Vector3f(0, y1, 0),
                    r0, r1, sides, phase, BARK, ring == 0, ring == rings - 1);
        }
    }

    private static void addNearBranch(MeshBuilder b, BranchSpec branch) {
        float dx = (float) Math.cos(branch.angle), dz = (float) Math.sin(branch.angle);
        Random stemRandom = new Random(branch.seed ^ 0x13198a2e03707344L);
        Vector3f root = new Vector3f(dx * .12f, branch.y, dz * .12f);
        Vector3f tip = new Vector3f(dx * branch.length,
                Math.max(.75f, branch.y - branch.droop), dz * branch.length);
        addFrustum(b, root, tip, .058f, .012f, NEAR_BRANCH_SIDES,
                stemRandom.nextFloat() * 6.28f, BRANCH_BARK, false, false);
        Vector3f[][] sprays = addMacroFoliage(b, branch, root, tip);
        for (int i = 0; i < sprays.length; i++) {
            Vector3f start = sprays[i][0], end = sprays[i][1];
            Random fine = new Random(branch.seed ^ (0x6a09e667f3bcc909L * (i + 1L)));
            addFrustum(b, start, end, .023f, .005f, 3,
                    fine.nextFloat() * 6.28f, BRANCH_BARK, false, false);
            // Fine near detail sits inside the shared macro spray silhouette.
            addFrondPair(b, lerp(start, end, .18f), lerp(start, end, .88f),
                    .58f - .12f * branch.level / (BRANCH_LEVELS - 1f),
                    fine, colorFor(fine));
        }
    }

    private static void addMidBranch(MeshBuilder b, BranchSpec branch) {
        float dx = (float) Math.cos(branch.angle), dz = (float) Math.sin(branch.angle);
        Vector3f root = new Vector3f(dx * .12f, branch.y, dz * .12f);
        Vector3f tip = new Vector3f(dx * branch.length,
                Math.max(.75f, branch.y - branch.droop), dz * branch.length);
        addMacroFoliage(b, branch, root, tip);
    }

    /** Four macro foliage sprays shared exactly by both geometry LODs. */
    private static Vector3f[][] addMacroFoliage(MeshBuilder b, BranchSpec branch,
            Vector3f root, Vector3f tip) {
        float t = branch.level / (float) (BRANCH_LEVELS - 1);
        Random main = new Random(branch.seed ^ 0x082efa98ec4e6c89L);
        addFrondPair(b, lerp(root, tip, .03f), lerp(root, tip, .98f),
                2.05f - .93f * t, main,
                colorFor(new Random(branch.seed ^ 0x3f84d5b5b5470917L)));

        Vector3f[][] sprays = new Vector3f[2][2];
        float dx = (float) Math.cos(branch.angle), dz = (float) Math.sin(branch.angle);
        for (int i = 0; i < sprays.length; i++) {
            int side = i == 0 ? -1 : 1;
            Random random = new Random(branch.seed ^ (0xbb67ae8584caa73bL * (i + 1L)));
            float forkT = .34f + i * .29f + (random.nextFloat() - .5f) * .12f;
            Vector3f start = lerp(root, tip, forkT);
            float radialStep = .14f + random.nextFloat() * .06f;
            float lateralStep = .12f + random.nextFloat() * .07f;
            float sideX = -dz * side, sideZ = dx * side;
            Vector3f end = new Vector3f(
                    start.x + dx * branch.length * radialStep + sideX * branch.length * lateralStep,
                    Math.max(.26f, start.y + (side > 0 ? .12f : -.08f)
                            - branch.droop * (.14f + random.nextFloat() * .22f)),
                    start.z + dz * branch.length * radialStep + sideZ * branch.length * lateralStep);
            sprays[i][0] = start;
            sprays[i][1] = end;
            float width = (1.68f - .66f * t) * (.94f + random.nextFloat() * .12f);
            addFrondPair(b, start, end, width, random, colorFor(random));
        }

        // A pendant spray drops through the gap below each whorl. Its start,
        // side, and fall vary per limb, so these overlapping curtains do not
        // read as another level ring.
        Random pendantRandom = new Random(branch.seed ^ 0x3c6ef372fe94f82bL);
        int pendantSide = pendantRandom.nextBoolean() ? -1 : 1;
        float pendantT = .17f + pendantRandom.nextFloat() * .13f;
        Vector3f pendantStart = lerp(root, tip, pendantT);
        float sideX = -dz * pendantSide, sideZ = dx * pendantSide;
        float fall = .42f + pendantRandom.nextFloat() * .38f + branch.droop * .35f;
        Vector3f pendantEnd = new Vector3f(
                pendantStart.x + dx * branch.length * (.28f + pendantRandom.nextFloat() * .08f)
                        + sideX * branch.length * (.08f + pendantRandom.nextFloat() * .05f),
                Math.max(.22f, pendantStart.y - fall),
                pendantStart.z + dz * branch.length * (.28f + pendantRandom.nextFloat() * .08f)
                        + sideZ * branch.length * (.08f + pendantRandom.nextFloat() * .05f));
        float pendantWidth = (1.56f - .62f * t) * (.94f + pendantRandom.nextFloat() * .12f);
        addFrondPair(b, pendantStart, pendantEnd, pendantWidth,
                pendantRandom, colorFor(pendantRandom));
        return sprays;
    }

    private static void addLeader(MeshBuilder b, Random random) {
        Vector3f root = new Vector3f(0, 14.2f, 0);
        Vector3f tip = new Vector3f((random.nextFloat() - .5f) * .28f, HEIGHT - .01f,
                (random.nextFloat() - .5f) * .28f);
        addFrustum(b, root, tip, .07f, .008f, 4,
                random.nextFloat() * 6.28f, BRANCH_BARK, false, false);
        addFrondPair(b, new Vector3f(0, 15.1f, 0), lerp(root, tip, .92f), .92f, random, NEEDLES_B);
    }

    private static void addFrondPair(MeshBuilder b, Vector3f start, Vector3f end,
            float width, Random random, ColorRGBA color) {
        Vector3f axis = end.subtract(start).normalizeLocal();
        Vector3f side = axis.cross(Vector3f.UNIT_Y);
        if (side.lengthSquared() < 1e-6f) side.set(Vector3f.UNIT_X);
        side.normalizeLocal();
        Vector3f up = axis.cross(side).normalizeLocal();
        float phase = random.nextFloat() * 6.2831855f;
        for (int card = 0; card < 2; card++) {
            float angle = phase + card * 1.5707963f;
            Vector3f across = side.mult((float) Math.cos(angle)).addLocal(up.mult((float) Math.sin(angle)));
            Vector3f cardStart = start.add(axis.mult(.01f + random.nextFloat() * .04f));
            Vector3f cardEnd = lerp(start, end, .96f + random.nextFloat() * .03f);
            float halfWidth = width * (.94f + random.nextFloat() * .06f);
            Vector3f normal = across.cross(cardEnd.subtract(cardStart)).normalizeLocal();
            // Art normals soften toward the crown volume/up. UV.z is a material
            // tag, not a texture-derived height or normal.
            normal.multLocal(.68f).addLocal(0, .42f, 0).normalizeLocal();
            b.addDoubleSidedCard(cardStart, cardEnd, across, halfWidth, normal, color);
        }
    }

    private static ColorRGBA colorFor(Random random) {
        switch (random.nextInt(3)) {
            case 0: return NEEDLES_A;
            case 1: return NEEDLES_B;
            default: return NEEDLES_C;
        }
    }

    private static Vector3f lerp(Vector3f a, Vector3f b, float t) {
        return new Vector3f(a.x + (b.x - a.x) * t,
                a.y + (b.y - a.y) * t,
                a.z + (b.z - a.z) * t);
    }

    private static final class BranchSpec {
        final int level;
        final double angle;
        final float y;
        final float length;
        final float droop;
        final long seed;

        BranchSpec(int level, double angle, float y, float length, float droop, long seed) {
            this.level = level;
            this.angle = angle;
            this.y = y;
            this.length = length;
            this.droop = droop;
            this.seed = seed;
        }
    }

    private static void addFrustum(MeshBuilder b, Vector3f start, Vector3f end,
            float radiusStart, float radiusEnd, int sides, float phase,
            ColorRGBA color, boolean capStart, boolean capEnd) {
        Vector3f axis = end.subtract(start).normalizeLocal();
        Vector3f basis = axis.cross(Vector3f.UNIT_Y);
        if (basis.lengthSquared() < 1e-6f) basis.set(Vector3f.UNIT_X);
        basis.normalizeLocal();
        Vector3f bitangent = basis.cross(axis).normalizeLocal();
        int base = b.vertexCount();
        float slope = radiusStart - radiusEnd;
        for (int ring = 0; ring < 2; ring++) {
            Vector3f center = ring == 0 ? start : end;
            float radius = ring == 0 ? radiusStart : radiusEnd;
            for (int i = 0; i < sides; i++) {
                float angle = phase + 6.283185307f * i / sides;
                float cs = (float) Math.cos(angle), sn = (float) Math.sin(angle);
                Vector3f radial = basis.mult(cs).addLocal(bitangent.mult(sn)).normalizeLocal();
                Vector3f normal = radial.mult(1f).addLocal(axis.mult(slope / Math.max(.001f,
                        start.distance(end)))).normalizeLocal();
                Vector3f p = center.add(radial.mult(radius));
                b.addVertex(p, normal, 0, ring, 0, color);
            }
        }
        for (int i = 0; i < sides; i++) {
            int n = (i + 1) % sides;
            b.triangle(base + i, base + sides + i, base + sides + n);
            b.triangle(base + i, base + sides + n, base + n);
        }
        if (capStart) b.addCap(start, axis.mult(-1), sides, color, base, false);
        if (capEnd) b.addCap(end, axis, sides, color, base + sides, true);
    }

    private static final class MeshBuilder {
        private final Floats positions = new Floats(4096);
        private final Floats normals = new Floats(4096);
        private final Floats texCoords = new Floats(4096);
        private final Floats colors = new Floats(4096);
        private final Ints indices = new Ints(4096);
        int vertexCount() { return positions.size / 3; }
        void addVertex(Vector3f p, Vector3f n, float u, float v, float leaf, ColorRGBA c) {
            positions.add(p.x, p.y, p.z); normals.add(n.x, n.y, n.z);
            texCoords.add(u, v, leaf); colors.add(c.r, c.g, c.b, 1f);
        }
        void triangle(int a, int b, int c) { indices.add(a, b, c); }
        void addCap(Vector3f center, Vector3f normal, int sides,
                ColorRGBA color, int ringBase, boolean top) {
            int centerIndex = vertexCount();
            addVertex(center, normal, .5f, .5f, 0, color);
            for (int i = 0; i < sides; i++) {
                int current = ringBase + i, next = ringBase + (i + 1) % sides;
                if (top) triangle(centerIndex, next, current);
                else triangle(centerIndex, current, next);
            }
        }
        void addDoubleSidedCard(Vector3f start, Vector3f end, Vector3f across,
                float halfWidth, Vector3f normal, ColorRGBA color) {
            float startWidth = limitedHalfWidth(start, across, halfWidth);
            float endWidth = limitedHalfWidth(end, across, halfWidth * .72f);
            Vector3f s0 = start.subtract(across.mult(startWidth));
            Vector3f s1 = start.add(across.mult(startWidth));
            Vector3f e0 = end.subtract(across.mult(endWidth));
            Vector3f e1 = end.add(across.mult(endWidth));
            int base = vertexCount();
            addVertex(s0, normal, 0, 0, 1, color); addVertex(s1, normal, 0, 1, 1, color);
            addVertex(e1, normal, 1, 1, 1, color); addVertex(e0, normal, 1, 0, 1, color);
            triangle(base, base + 1, base + 2); triangle(base, base + 2, base + 3);
            Vector3f back = normal.mult(-1); int reverse = vertexCount();
            addVertex(s0, back, 0, 0, 1, color); addVertex(e0, back, 1, 0, 1, color);
            addVertex(e1, back, 1, 1, 1, color); addVertex(s1, back, 0, 1, 1, color);
            triangle(reverse, reverse + 1, reverse + 2); triangle(reverse, reverse + 2, reverse + 3);
        }

        private float limitedHalfWidth(Vector3f center, Vector3f across, float requested) {
            float limit = requested;
            float ax = Math.abs(across.x), ay = Math.abs(across.y), az = Math.abs(across.z);
            if (ax > 1e-6f) limit = Math.min(limit, Math.max(0f, CROWN_RADIUS - Math.abs(center.x)) / ax);
            if (az > 1e-6f) limit = Math.min(limit, Math.max(0f, CROWN_RADIUS - Math.abs(center.z)) / az);
            if (ay > 1e-6f) {
                float verticalRoom = Math.max(0f, Math.min(center.y, HEIGHT - center.y));
                limit = Math.min(limit, verticalRoom / ay);
            }
            return Math.max(0f, limit - .001f);
        }
        Mesh finish() {
            Mesh mesh = new Mesh(); mesh.setMode(Mesh.Mode.Triangles);
            mesh.setBuffer(VertexBuffer.Type.Position, 3, BufferUtils.createFloatBuffer(positions.toArray()));
            mesh.setBuffer(VertexBuffer.Type.Normal, 3, BufferUtils.createFloatBuffer(normals.toArray()));
            mesh.setBuffer(VertexBuffer.Type.TexCoord, 3, BufferUtils.createFloatBuffer(texCoords.toArray()));
            mesh.setBuffer(VertexBuffer.Type.Color, 4, BufferUtils.createFloatBuffer(colors.toArray()));
            mesh.setBuffer(VertexBuffer.Type.Index, 3, BufferUtils.createIntBuffer(indices.toArray()));
            mesh.setStatic(); mesh.updateBound(); return mesh;
        }
    }

    private static final class Floats {
        float[] values; int size;
        Floats(int capacity) { values = new float[capacity]; }
        void add(float a, float b, float c) {
            ensure(3); values[size++] = a; values[size++] = b; values[size++] = c;
        }
        void add(float a, float b, float c, float d) {
            ensure(4); values[size++] = a; values[size++] = b; values[size++] = c; values[size++] = d;
        }
        void ensure(int count) {
            if (size + count > values.length) {
                float[] grown = new float[Math.max(values.length * 2, size + count)];
                System.arraycopy(values, 0, grown, 0, size); values = grown;
            }
        }
        float[] toArray() {
            float[] result = new float[size]; System.arraycopy(values, 0, result, 0, size); return result;
        }
    }

    private static final class Ints {
        int[] values; int size;
        Ints(int capacity) { values = new int[capacity]; }
        void add(int a, int b, int c) {
            if (size + 3 > values.length) {
                int[] grown = new int[Math.max(values.length * 2, size + 3)];
                System.arraycopy(values, 0, grown, 0, size); values = grown;
            }
            values[size++] = a; values[size++] = b; values[size++] = c;
        }
        int[] toArray() {
            int[] result = new int[size]; System.arraycopy(values, 0, result, 0, size); return result;
        }
    }
}
