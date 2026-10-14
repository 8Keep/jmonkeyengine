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
package jme3test.terrain;

import com.jme3.app.SimpleApplication;
import com.jme3.light.DirectionalLight;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Ray;
import com.jme3.math.Vector2f;
import com.jme3.math.Vector3f;
import com.jme3.post.SceneProcessor;
import com.jme3.profile.AppProfiler;
import com.jme3.renderer.Camera;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.ViewPort;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.Geometry;
import com.jme3.scene.shape.Box;
import com.jme3.shadow.CompareMode;
import com.jme3.shadow.DirectionalLightVisibilityRenderer;
import com.jme3.system.AppSettings;
import com.jme3.terrain.heightfield.HeightfieldGrid;
import com.jme3.terrain.heightfield.HeightfieldHierarchy;
import com.jme3.terrain.heightfield.HeightfieldPage;
import com.jme3.terrain.heightfield.HeightfieldReconstruction;
import com.jme3.texture.FrameBuffer;
import com.jme3.texture.Image;
import com.jme3.texture.Texture;
import com.jme3.texture.TextureArray;
import com.jme3.util.BufferUtils;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;

/**
 * Finite original displaced-caster controls. CPU rays through the exact morphed
 * triangles predict hard shadows on a flat ordinary PBR receiver. Tests use
 * paired linear HDR renders and exclude a one-unit shadow-edge band, since
 * cascaded filtered maps have finite resolution and comparison bias. This is a
 * small synthetic visibility contract, not a regional scenic/performance gate.
 * Run with assertions, renderer, output directory and optional "software".
 */
public final class TestDirectionalVisibilityTerrain extends SimpleApplication {
    private static final ColorRGBA FILL = new ColorRGBA(.035f, .045f, .055f, 1);
    private static final String[] CASES = {"overview-fine", "close-fine", "close-mid-morph",
        "close-parent", "offscreen-low-sun", "low-sun-overview", "perspective-cascades", "bias-control", "close-subpixel-pan"};
    private final Path output;
    private final boolean software;
    private final CountDownLatch finished = new CountDownLatch(1);
    private volatile Throwable failure;
    private final DirectionalLight sun = new DirectionalLight();
    private final List<Vector3f[]> moundTriangles = new ArrayList<>();
    private final List<Vector3f[]> blockTriangles = new ArrayList<>();
    private HeightfieldPage page;
    private Geometry mound;
    private Material moundMaterial;
    private DirectionalLightVisibilityRenderer visibility;
    private TextureArray heights;
    private FrameBuffer target;
    private ByteBuffer pixels;
    private float[] shadowed;
    private int frame, totalRays, totalShadowed, totalLit;

    private TestDirectionalVisibilityTerrain(Path output, boolean software) {
        this.output = output;
        this.software = software;
    }

    public static void main(String[] args) throws Exception {
        boolean assertions = false;
        assert assertions = true;
        if (!assertions || args.length < 2 || args.length > 3
                || (args.length == 3 && !"software".equals(args[2]))) {
            throw new IllegalArgumentException("Use -ea, renderer, output directory, optional software");
        }
        TestDirectionalVisibilityTerrain app = new TestDirectionalVisibilityTerrain(
                Paths.get(args[1]), args.length == 3);
        AppSettings settings = new AppSettings(true);
        settings.setRenderer(args[0]);
        settings.setResolution(640, 480);
        settings.setAudioRenderer(null);
        settings.setGammaCorrection(true);
        settings.setVSync(false);
        app.setSettings(settings);
        app.setShowSettings(false);
        app.start();
        if (!app.finished.await(60, TimeUnit.SECONDS)) {
            app.stop(false);
            throw new IllegalStateException("Displaced visibility timeout", app.failure);
        }
        app.stop(true);
        if (app.failure != null) throw new IllegalStateException("Displaced visibility failed", app.failure);
    }

    @Override public void handleError(String message, Throwable error) {
        fail(error == null ? new IllegalStateException(message) : error);
    }

    private void fail(Throwable error) {
        failure = error;
        error.printStackTrace();
        finished.countDown();
    }

    @Override public void simpleInitApp() {
        try {
            flyCam.setEnabled(false);
            HeightfieldGrid.requireSupported(renderer);
            float[] source = new float[35 * 35];
            for (int z = -1; z <= 33; z++) for (int x = -1; x <= 33; x++) {
                double radius = Math.hypot(x * .5 - 4, z * .5 - 4) / 3.8;
                source[(z + 1) * 35 + x + 1] = radius >= 1 ? 0
                        : (float) (6 * Math.pow(1 - radius * radius, 2)
                        * (1 + .14 * Math.sin(x * 1.3) * Math.cos(z * .7)));
            }
            page = new HeightfieldPage(32, .5f, source);
            HeightfieldHierarchy hierarchy = new HeightfieldHierarchy(page, 16, 0);
            HeightfieldHierarchy.Node node = hierarchy.getRoot().getChildren().get(0);
            heights = new TextureArray(Collections.singletonList(page.createImage()));
            heights.setMinFilter(Texture.MinFilter.NearestNoMipMaps);
            heights.setMagFilter(Texture.MagFilter.Nearest);
            heights.setWrap(Texture.WrapMode.EdgeClamp);
            mound = new Geometry("original-displaced-morph-caster", new HeightfieldGrid(16).createLodPatchMesh(node));
            mound.setLocalTranslation(18, 0, 20);
            mound.setShadowMode(RenderQueue.ShadowMode.CastAndReceive);
            moundMaterial = new Material(assetManager, "Common/MatDefs/Terrain/Heightfield/Heightfield.j3md");
            moundMaterial.setTexture("HeightPages", heights);
            moundMaterial.setFloat("SampleSpacing", .5f);
            moundMaterial.setBoolean("UseHeightfieldLod", true);
            moundMaterial.setInt("PageCells", 32);
            moundMaterial.setInt("PatchGridCells", 16);
            moundMaterial.setInt("SampleStride", 1);
            moundMaterial.setFloat("SnowLine", 1000);
            mound.setMaterial(moundMaterial);
            rootNode.attachChild(mound);

            Geometry ground = new Geometry("ordinary-pbr-flat-receiver", new Box(32, .05f, 32));
            ground.setLocalTranslation(32, -.05f, 32);
            ground.setShadowMode(RenderQueue.ShadowMode.Receive);
            Material groundMaterial = pbr(new ColorRGBA(.2f, .24f, .28f, 1));
            groundMaterial.setColor("Emissive", FILL);
            groundMaterial.setFloat("EmissiveIntensity", 1);
            ground.setMaterial(groundMaterial);
            rootNode.attachChild(ground);
            Geometry block = new Geometry("ordinary-pbr-caster", new Box(2, 3, 2));
            block.setLocalTranslation(42, 3, 28);
            block.setShadowMode(RenderQueue.ShadowMode.CastAndReceive);
            Material blockMaterial = pbr(new ColorRGBA(.32f, .17f, .08f, 1));
            block.setMaterial(blockMaterial);
            rootNode.attachChild(block);
            addBlockTriangles(blockTriangles, new Vector3f(40, 0, 26), new Vector3f(44, 6, 30));
            sun.setColor(new ColorRGBA(1.4f, 1.3f, 1.15f, 1));
            rootNode.addLight(sun);
            visibility = new DirectionalLightVisibilityRenderer(assetManager, 1024, 3);
            visibility.setLight(sun);
            visibility.setLambda(0);
            visibility.setShadowIntensity(1);
            if (software) visibility.setShadowCompareMode(CompareMode.Software);
            visibility.addReceiver(moundMaterial);
            visibility.addReceiver(groundMaterial);
            visibility.addReceiver(blockMaterial);
            viewPort.addProcessor(visibility);
            target = new FrameBuffer(cam.getWidth(), cam.getHeight(), 1);
            target.addColorTarget(FrameBuffer.FrameBufferTarget.newTarget(Image.Format.RGBA16F));
            target.setDepthTarget(FrameBuffer.FrameBufferTarget.newTarget(Image.Format.Depth24));
            pixels = BufferUtils.createByteBuffer(cam.getWidth() * cam.getHeight() * 16).order(ByteOrder.nativeOrder());
            viewPort.setOutputFrameBuffer(target);
            viewPort.setBackgroundColor(new ColorRGBA(.12f, .2f, .3f, 1));
            viewPort.addProcessor(new Capture());
        } catch (Throwable error) { fail(error); }
    }

    private Material pbr(ColorRGBA color) {
        Material material = new Material(assetManager, "Common/MatDefs/Light/PBRLighting.j3md");
        material.setColor("BaseColor", color);
        material.setFloat("Roughness", .85f);
        return material;
    }

    @Override public void simpleUpdate(float tpf) {
        if (failure != null) return;
        frame++;
        int index = (frame - 1) / 10;
        if (index >= CASES.length) return;
        if (frame % 10 == 1) configure(index);
        visibility.setShadowIntensity((frame - 1) % 10 < 5 ? 1 : 0);
    }

    private void configure(int index) {
        float morph = index == 2 ? .5f : index == 3 ? 1 : 0;
        moundMaterial.setFloat("InteriorMorph", morph);
        moundMaterial.setFloat("EdgeMorphWest", morph);
        moundMaterial.setFloat("EdgeMorphEast", morph);
        moundMaterial.setFloat("EdgeMorphNorth", morph);
        moundMaterial.setFloat("EdgeMorphSouth", morph);
        reconstructMound(morph);
        sun.setDirection(new Vector3f(1f, index == 4 || index == 5 ? -.25f : -.65f, .18f).normalizeLocal());
        visibility.setDepthBias(index == 7 ? .0001f : .0005f);
        cam.setParallelProjection(index != 6);
        if (index == 6) {
            cam.setFrustumPerspective(60, (float) cam.getWidth() / cam.getHeight(), 1, 90);
            cam.setLocation(new Vector3f(32, 30, 65));
            cam.lookAt(new Vector3f(32, 0, 26), Vector3f.UNIT_Y);
        } else {
            float half = (index >= 1 && index <= 4) || index == 8 ? 8 : 34;
            float centerX = index == 4 ? 37 : index == 8 ? 24.013f : half == 8 ? 24 : 32;
            float centerZ = half == 8 ? 24.5f : 32;
            float aspect = (float) cam.getWidth() / cam.getHeight();
            cam.setFrustum(1, 150, -half * aspect, half * aspect, half, -half);
            cam.setLocation(new Vector3f(centerX, 80, centerZ));
            cam.lookAt(new Vector3f(centerX, 0, centerZ), Vector3f.UNIT_Z);
        }
        cam.update();
    }

    private void reconstructMound(float alpha) {
        moundTriangles.clear();
        Vector3f[][] v = new Vector3f[17][17];
        for (int z = 0; z <= 16; z++) for (int x = 0; x <= 16; x++) {
            boolean corner = (x == 0 || x == 16) && (z == 0 || z == 16);
            v[z][x] = HeightfieldReconstruction.morphVertex(page, x, z, 1, corner ? 0 : alpha, null);
            v[z][x].addLocal(18, 0, 20);
        }
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            moundTriangles.add(new Vector3f[]{v[z][x], v[z + 1][x + 1], v[z][x + 1]});
            moundTriangles.add(new Vector3f[]{v[z][x], v[z + 1][x], v[z + 1][x + 1]});
        }
    }

    private static void addBlockTriangles(List<Vector3f[]> triangles, Vector3f low, Vector3f high) {
        Vector3f[] v = new Vector3f[8];
        for (int i = 0; i < 8; i++) v[i] = new Vector3f((i & 1) == 0 ? low.x : high.x,
                (i & 2) == 0 ? low.y : high.y, (i & 4) == 0 ? low.z : high.z);
        int[][] faces = {{0, 1, 3, 2}, {4, 6, 7, 5}, {0, 4, 5, 1},
            {2, 3, 7, 6}, {0, 2, 6, 4}, {1, 5, 7, 3}};
        for (int[] f : faces) {
            triangles.add(new Vector3f[]{v[f[0]], v[f[1]], v[f[2]]});
            triangles.add(new Vector3f[]{v[f[0]], v[f[2]], v[f[3]]});
        }
    }

    private boolean hits(Ray ray, List<Vector3f[]> triangles, float maximum) {
        Vector3f hit = new Vector3f();
        for (Vector3f[] t : triangles) {
            if (ray.intersectWhere(t[0], t[1], t[2], hit)
                    && hit.distance(ray.getOrigin()) > .002f
                    && hit.distance(ray.getOrigin()) < maximum) return true;
        }
        return false;
    }

    private boolean shadowAt(Vector3f point) {
        totalRays++;
        Ray ray = new Ray(point.add(0, .002f, 0), sun.getDirection().negate());
        return hits(ray, moundTriangles, 200) || hits(ray, blockTriangles, 200);
    }

    private void verify(float[] unoccluded, int index) {
        int shadowCount = 0, litCount = 0, wrongShadow = 0, wrongLit = 0, moundOnly = 0;
        int[] cascades = new int[3];
        for (int y = 3; y < cam.getHeight(); y += 6) for (int x = 3; x < cam.getWidth(); x += 6) {
            Vector3f near = cam.getWorldCoordinates(new Vector2f(x + .5f, y + .5f), 0);
            Vector3f far = cam.getWorldCoordinates(new Vector2f(x + .5f, y + .5f), 1);
            float fraction = -near.y / (far.y - near.y);
            if (!(fraction > 0 && fraction < 1)) continue;
            Vector3f point = near.clone().interpolateLocal(far, fraction);
            if (point.x < 2 || point.x > 62 || point.z < 2 || point.z > 62
                    || (point.x > 17 && point.x < 27 && point.z > 19 && point.z < 29)
                    || (point.x > 39 && point.x < 45 && point.z > 25 && point.z < 31)) continue;
            Vector3f toward = point.subtract(near);
            float distance = toward.length();
            Ray viewRay = new Ray(near, toward.divide(distance));
            if (hits(viewRay, moundTriangles, distance - .01f) || hits(viewRay, blockTriangles, distance - .01f)) continue;
            boolean expected = shadowAt(point);
            if (shadowAt(point.add(1, 0, 0)) != expected || shadowAt(point.add(-1, 0, 0)) != expected
                    || shadowAt(point.add(0, 0, 1)) != expected || shadowAt(point.add(0, 0, -1)) != expected) continue;
            int offset = (y * cam.getWidth() + x) * 4;
            float fill = FILL.r;
            float direct = unoccluded[offset] - fill;
            if (direct <= .01f) throw new IllegalStateException("No measurable sun contribution");
            float ratio = (shadowed[offset] - fill) / direct;
            if (expected) {
                shadowCount++;
                if (ratio > .25f) wrongShadow++;
                Ray ray = new Ray(point.add(0, .002f, 0), sun.getDirection().negate());
                if (hits(ray, moundTriangles, 200) && !hits(ray, blockTriangles, 200)) moundOnly++;
            } else {
                litCount++;
                if (ratio < .75f) wrongLit++;
            }
            float depth = -cam.getViewMatrix().mult(point).z;
            float a = cam.getFrustumNear(), b = cam.getFrustumFar();
            cascades[Math.min(2, (int) (3 * (depth - a) / (b - a)))]++;
        }
        if (shadowCount < 4 || litCount < 100 || moundOnly < 4
                || wrongShadow > Math.max(1, shadowCount / 20) || wrongLit > Math.max(1, litCount / 100)) {
            throw new IllegalStateException(CASES[index] + " ray agreement failed: shadow=" + shadowCount
                    + " wrongShadow=" + wrongShadow + " lit=" + litCount + " wrongLit=" + wrongLit + " moundOnly=" + moundOnly);
        }
        if (index == 4) {
            int priorPlaneState = cam.getPlaneState();
            cam.setPlaneState(0);
            Camera.FrustumIntersect result = cam.contains(mound.getWorldBound());
            cam.setPlaneState(priorPlaneState);
            if (result != Camera.FrustumIntersect.Outside) {
                throw new IllegalStateException("Offscreen caster is still in the main frustum");
            }
        }
        if (index == 6 && (cascades[0] == 0 || cascades[1] == 0 || cascades[2] == 0)) {
            throw new IllegalStateException("Perspective control did not exercise all three cascade depths");
        }
        totalShadowed += shadowCount;
        totalLit += litCount;
        System.out.println("DISPLACED_VISIBILITY_CASE_PASS case=" + CASES[index] + " shadow=" + shadowCount
                + " lit=" + litCount + " wrongShadow=" + wrongShadow + " wrongLit=" + wrongLit
                + " moundOnly=" + moundOnly + " cascadeSamples=" + Arrays.toString(cascades));
    }

    private final class Capture implements SceneProcessor {
        private boolean initialized;
        @Override public void initialize(RenderManager rm, ViewPort vp) { initialized = true; }
        @Override public boolean isInitialized() { return initialized; }
        @Override public void reshape(ViewPort vp, int w, int h) { }
        @Override public void preFrame(float tpf) { }
        @Override public void postQueue(RenderQueue queue) { }
        @Override public void setProfiler(AppProfiler profiler) { }
        @Override public void cleanup() {
            renderer.deleteFrameBuffer(target);
            renderer.deleteImage(heights.getImage());
            for (ByteBuffer data : heights.getImage().getData()) BufferUtils.destroyDirectBuffer(data);
            BufferUtils.destroyDirectBuffer(pixels);
            initialized = false;
        }
        @Override public void postFrame(FrameBuffer out) {
            if (failure != null || frame > CASES.length * 10 || frame % 5 != 0) return;
            try {
                pixels.clear();
                renderer.readFrameBufferWithFormat(target, pixels, Image.Format.RGBA32F);
                pixels.rewind();
                FloatBuffer linear = pixels.asFloatBuffer();
                float[] values = new float[linear.remaining()];
                linear.get(values);
                for (float value : values) if (!Float.isFinite(value)) throw new IllegalStateException("Nonfinite HDR pixel");
                int index = (frame - 1) / 10;
                boolean withShadows = frame % 10 == 5;
                write(values, CASES[index] + (withShadows ? "-shadowed" : "-unoccluded"));
                if (withShadows) shadowed = values;
                else verify(values, index);
                if (frame == CASES.length * 10) {
                    System.out.println("DISPLACED_VISIBILITY_PASS frames=" + frame + " rays=" + totalRays
                            + " shadowSamples=" + totalShadowed + " litSamples=" + totalLit + " compare=" + visibility.getShadowCompareMode());
                    finished.countDown();
                }
            } catch (Throwable error) { fail(error); }
        }
        private void write(float[] linear, String label) throws Exception {
            Files.createDirectories(output);
            BufferedImage image = new BufferedImage(cam.getWidth(), cam.getHeight(), BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < cam.getHeight(); y++) for (int x = 0; x < cam.getWidth(); x++) {
                int i = ((cam.getHeight() - 1 - y) * cam.getWidth() + x) * 4;
                int color = (display(linear[i]) << 16) | (display(linear[i + 1]) << 8) | display(linear[i + 2]);
                image.setRGB(x, y, color);
            }
            ImageIO.write(image, "png", output.resolve(label + ".png").toFile());
        }
        private int display(float value) {
            value = Math.max(0, Math.min(1, value));
            float encoded = value <= .0031308f ? value * 12.92f : 1.055f * (float) Math.pow(value, 1 / 2.4) - .055f;
            return Math.max(0, Math.min(255, Math.round(encoded * 255)));
        }
    }
}
