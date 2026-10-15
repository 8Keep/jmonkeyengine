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

import com.jme3.app.SimpleApplication;
import com.jme3.material.Material;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;
import com.jme3.renderer.Renderer;
import com.jme3.scene.Geometry;
import com.jme3.scene.VertexBuffer;
import com.jme3.scene.shape.Quad;
import com.jme3.system.AppSettings;
import com.jme3.terrain.heightfield.HeightfieldGpuTransitions;
import com.jme3.terrain.heightfield.HeightfieldHierarchy;
import com.jme3.terrain.heightfield.HeightfieldLod;
import com.jme3.terrain.heightfield.HeightfieldPage;
import com.jme3.terrain.heightfield.HeightfieldProjection;
import com.jme3.texture.FrameBuffer;
import com.jme3.texture.Image;
import com.jme3.util.BufferUtils;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/** Finite native comparison of persistent GPU transitions with the CPU oracle. */
public final class TestHeightfieldGpuTransitions extends SimpleApplication {
    private static final int CELLS = 128, PATCH_CELLS = 16, NODES = 85;
    private final CountDownLatch finished = new CountDownLatch(1);
    private final Path output;
    private final boolean checkShading;
    private final Map<HeightfieldHierarchy.Node, Integer> indices = new IdentityHashMap<>();
    private final List<String> rows = new ArrayList<>();
    private volatile Throwable failure;
    private int checks, frames, refining, coarsening, partialMorphs;
    private HeightfieldHierarchy hierarchy;
    private HeightfieldLod lod;
    private HeightfieldLod.Transition reference;
    private HeightfieldGpuTransitions gpu;
    private HeightfieldLodGpuProbe positions;
    private HeightfieldMorphRenderProbe images;
    private Geometry decoder;
    private FrameBuffer target;
    private Camera decoderCamera;
    private ByteBuffer bytes;

    private TestHeightfieldGpuTransitions(Path output, boolean checkShading) {
        this.output = output;
        this.checkShading = checkShading;
    }

    public static void main(String[] args) throws Exception {
        TestHeightfieldGpuTransitions app = new TestHeightfieldGpuTransitions(Paths.get(
                args.length > 1 ? args[1] : "build/heightfield-gpu-transitions"),
                args.length > 2 && "--check-shading".equals(args[2]));
        AppSettings settings = new AppSettings(true);
        settings.setRenderer(args.length > 0 ? args[0] : AppSettings.LWJGL_OPENGL45);
        settings.setResolution(320, 240);
        settings.setAudioRenderer(null);
        settings.setVSync(false);
        settings.setGraphicsDebug(true);
        app.setSettings(settings);
        app.setShowSettings(false);
        app.start();
        if (!app.finished.await(120, TimeUnit.SECONDS)) {
            app.stop(false);
            throw new IllegalStateException("GPU terrain transitions timed out", app.failure);
        }
        app.stop(true);
        if (app.failure != null) throw new IllegalStateException("GPU terrain transitions failed", app.failure);
        System.out.println("HEIGHTFIELD_GPU_TRANSITIONS_PASS frames=" + app.frames
                + " checks=" + app.checks + " refining=" + app.refining + " coarsening=" + app.coarsening
                + " partialMorphs=" + app.partialMorphs + " output=" + app.output.toAbsolutePath());
    }

    @Override public void simpleInitApp() {
        try {
            flyCam.setEnabled(false);
            runProbe(renderManager.getRenderer());
        } catch (Throwable error) {
            fail(error);
        } finally {
            finished.countDown();
        }
    }

    @Override public void handleError(String message, Throwable error) {
        fail(error == null ? new IllegalStateException(message) : error);
    }

    private void fail(Throwable error) {
        failure = error;
        error.printStackTrace();
        finished.countDown();
    }

    private void runProbe(Renderer renderer) throws Exception {
        HeightfieldPage page = makePage();
        hierarchy = new HeightfieldHierarchy(page, PATCH_CELLS, 0);
        lod = new HeightfieldLod(hierarchy);
        for (int i = 0; i < hierarchy.getNodes().size(); i++) indices.put(hierarchy.getNodes().get(i), i);
        require(indices.size() == NODES, "fixed hierarchy");
        Files.createDirectories(output);
        rows.add("phase,frame,leaves,active,maximumPixels");
        Camera originalCamera = renderManager.getCurrentCamera();
        Throwable primaryFailure = null;
        try {
            gpu = new HeightfieldGpuTransitions(assetManager, renderer, hierarchy);
            reference = new HeightfieldLod.Transition(hierarchy);
            positions = new HeightfieldLodGpuProbe(assetManager, renderManager, renderer, page);
            images = new HeightfieldMorphRenderProbe(assetManager, renderManager, renderer, hierarchy, gpu);
            Material material = new Material(assetManager, "jme3test/terrain/HeightfieldGpuTransitions.j3md");
            material.setUniformBufferObject("Result", gpu.getPatchRecords());
            decoder = new Geometry("transition-record-readback", new Quad(2, 2));
            decoder.setLocalTranslation(-1, -1, 0);
            decoder.setMaterial(material);
            decoder.updateGeometricState();
            target = new FrameBuffer(NODES, 5, 1);
            target.addColorTarget(FrameBuffer.FrameBufferTarget.newTarget(Image.Format.RGBA32F));
            bytes = BufferUtils.createByteBuffer(NODES * 5 * 16).order(ByteOrder.nativeOrder());
            decoderCamera = new Camera(NODES, 5);
            decoderCamera.setParallelProjection(true);
            decoderCamera.setFrustum(.1f, 10, -1, 1, 1, -1);
            decoderCamera.setLocation(new Vector3f(0, 0, 1));
            decoderCamera.lookAt(Vector3f.ZERO, Vector3f.UNIT_Y);
            decoderCamera.update();
            Camera normal = camera(new Vector3f(32, 30, 72), new Vector3f(32, 0, 32));
            Camera cut = camera(new Vector3f(-42, 25, 9), new Vector3f(10, 0, 55));
            Predicate<HeightfieldHierarchy.Node> all = node -> true;
            if (checkShading) {
                phase("parent-endpoint", normal, 10000, 64, 1, all, 1, 0);
                images.verify(normal, reference.getPatches(), "parent-endpoint", output);
                phase("child-endpoint", normal, .8, 64, 1, all, 1, 0);
                images.verify(normal, reference.getPatches(), "child-endpoint", output);
                verifyShadingEndpoint();
                gpu.reset();
                reference = new HeightfieldLod.Transition(hierarchy);
            }
            phase("refine", normal, .8, 64, 1, all, 110, .25f);
            phase("coarsen", normal, 10000, 64, 1, all, 110, .25f);
            require(reference.getPatches().size() == 1 && !reference.isTransitioning(), "coarse convergence");
            phase("cut-budget", cut, .25, 7, 1, all, 30, .25f);
            require(reference.getPatches().size() <= 7 && !reference.isTransitioning(), "budget convergence");
            // Reset while refining. Label changes are diagnostic resets, not height edits.
            gpu.reset(); reference = new HeightfieldLod.Transition(hierarchy);
            phase("reset-refine", normal, .5, 64, 1, all, 12, .25f);
            reference = new HeightfieldLod.Transition(hierarchy);
            phase("revision-cold", normal, .5, 64, 2, node -> node == hierarchy.getRoot(), 5, .25f);
            require(reference.getPatches().size() == 1, "cold root holds");
            phase("ready-recover", normal, .8, 64, 2, all, 110, .25f);
            phase("budget-shrink", cut, .25, 7, 2, all, 110, .25f);
            require(reference.getPatches().size() <= 7 && !reference.isTransitioning(), "shrinking budget converges");
            require(refining > 0 && coarsening > 0 && partialMorphs > 0, "both transaction directions and fractional morphs exercised");
            Files.write(output.resolve("frames.csv"), rows);
            List<String> summary = new ArrayList<>();
            summary.add("Every current cover, morph, edge, projected error and active flag matches CPU transition state.");
            summary.add("Whole immutable page stays resident; readiness metadata and revision labels are synthetic controls.");
            summary.add("Current cover can temporarily exceed the desired budget while safely coarsening; hard cap remains 64.");
            summary.add("renderer=" + renderer.getCaps());
            summary.add("frames=" + frames + " checks=" + checks + " fractionalPatchFrames=" + partialMorphs);
            Files.write(output.resolve("summary.txt"), summary);
        } catch (Exception | Error error) {
            primaryFailure = error;
            throw error;
        } finally {
            try {
                renderer.setFrameBuffer(null);
                if (originalCamera != null) renderManager.setCamera(originalCamera, false);
                if (images != null) images.close();
                if (positions != null) positions.close();
                if (gpu != null) gpu.close();
                if (target != null) renderer.deleteFrameBuffer(target);
                if (decoder != null) for (VertexBuffer buffer : decoder.getMesh().getBufferList()) renderer.deleteBuffer(buffer);
            } catch (RuntimeException | Error cleanupFailure) {
                if (primaryFailure == null) throw cleanupFailure;
                primaryFailure.addSuppressed(cleanupFailure);
            }
        }
    }

    private void verifyShadingEndpoint() throws Exception {
        java.awt.image.BufferedImage parent = javax.imageio.ImageIO.read(output.resolve("parent-endpoint-gpu.png").toFile());
        java.awt.image.BufferedImage children = javax.imageio.ImageIO.read(output.resolve("child-endpoint-gpu.png").toFile());
        int maximum = 0, changed = 0;
        for (int y = 0; y < parent.getHeight(); y++) for (int x = 0; x < parent.getWidth(); x++) {
            int a = parent.getRGB(x, y), b = children.getRGB(x, y);
            require((a >>> 24) == (b >>> 24), "collapsed split preserves raster coverage");
            boolean differs = false;
            for (int shift : new int[]{0, 8, 16}) {
                int delta = Math.abs(((a >>> shift) & 255) - ((b >>> shift) & 255));
                maximum = Math.max(maximum, delta);
                differs |= delta > 1;
            }
            if (differs) changed++;
        }
        System.out.println("HEIGHTFIELD_MORPH_SHADING_ENDPOINT maxByteDifference=" + maximum + " changedOverOneByte=" + changed);
        require(maximum <= 1, "fully collapsed children preserve parent shading within one display byte");
    }

    private void phase(String name, Camera camera, double pixels, int budget, int revision,
            Predicate<HeightfieldHierarchy.Node> ready, int count, float step) throws Exception {
        int previousCount = reference.getPatches().size();
        for (int frame = 0; frame < count; frame++) {
            HeightfieldLod.Selection desired = lod.select(camera, pixels, budget, ready);
            gpu.dispatch(camera, pixels, revision, budget, step, ready);
            reference.advance(desired, step);
            List<HeightfieldLod.Patch> patches = new ArrayList<>(reference.getPatches());
            patches.sort(Comparator.comparingInt(patch -> indices.get(patch.getNode())));
            renderManager.setCamera(decoderCamera, false);
            Renderer renderer = renderManager.getRenderer();
            renderer.setFrameBuffer(target);
            renderer.clearBuffers(true, false, false);
            renderManager.renderGeometry(decoder);
            bytes.clear();
            renderer.readFrameBufferWithFormat(target, bytes, Image.Format.RGBA32F);
            verify(name + ":" + frame, camera, revision, patches, bytes.asFloatBuffer());
            if (patches.size() > previousCount) refining++;
            if (patches.size() < previousCount) coarsening++;
            previousCount = patches.size();
            rows.add(name + "," + frame + "," + patches.size() + "," + reference.isTransitioning()
                    + "," + reference.getMaximumPixels(camera));
            if (frame == 2 && reference.isTransitioning()) {
                HeightfieldLodGpuProbe.Verification proof = positions.verifyMorphRecords(patches, gpu.getPatchRecords(), 0, target);
                require(proof.getComparedChannels() > 0, "native position coverage");
                images.verify(camera, patches, name, output);
                System.out.println("HEIGHTFIELD_GPU_MORPH_POSITIONS phase=" + name + " " + proof);
            }
            frames++;
        }
        System.out.println("HEIGHTFIELD_GPU_TRANSITION_PHASE name=" + name + " frames=" + count
                + " leaves=" + reference.getPatches().size() + " active=" + reference.isTransitioning());
    }

    private void verify(String name, Camera camera, int revision, List<HeightfieldLod.Patch> patches, FloatBuffer values) {
        require(pixel(values, 0, 0, 0) == patches.size() && pixel(values, 0, 0, 2) == patches.size(), name + " cover count");
        require(pixel(values, 0, 0, 1) == (reference.isTransitioning() ? 1 : 0), name + " active transaction");
        require(pixel(values, 0, 0, 3) == revision, name + " header revision");
        require(!patches.isEmpty() && patches.size() <= 64, name + " bounded nonempty cover");
        for (int i = 0; i < patches.size(); i++) {
            HeightfieldLod.Patch patch = patches.get(i);
            HeightfieldHierarchy.Node node = patch.getNode();
            require(pixel(values, i, 1, 0) == indices.get(node), name + " node at record " + i);
            require(pixel(values, i, 1, 1) == node.getOriginX() && pixel(values, i, 1, 2) == node.getOriginZ()
                    && pixel(values, i, 1, 3) == node.getStride(), name + " record lattice");
            require(pixel(values, i, 2, 0) == node.getSpan() && pixel(values, i, 2, 3) == revision, name + " record metadata");
            require(pixel(values, i, 3, 0) == patch.getMorph(), name + " interior morph " + indices.get(node));
            boolean morphed = patch.getMorph() > 0;
            if (patch.getMorph() > 0 && patch.getMorph() < 1) partialMorphs++;
            for (int edge = 0; edge < 4; edge++) {
                float actual = edge < 3 ? pixel(values, i, 3, edge + 1) : pixel(values, i, 4, 0);
                require(actual == patch.getEdgeMorph(edge), name + " edge " + edge + " node " + indices.get(node));
                morphed |= patch.getEdgeMorph(edge) > 0;
            }
            float expected = ceilFloat(HeightfieldProjection.pixels(camera, node.getBound(),
                    morphed ? node.getMorphError() : node.getStableError()));
            require(pixel(values, i, 2, 2) == expected, name + " conservative projected error");
        }
    }

    private static float pixel(FloatBuffer values, int x, int row, int channel) { return values.get((row * NODES + x) * 4 + channel); }
    private void require(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static float ceilFloat(double value) {
        float result = (float) value;
        return result < value ? Math.nextUp(result) : result;
    }
    private static Camera camera(Vector3f location, Vector3f target) {
        Camera camera = new Camera(320, 240);
        camera.setFrustumPerspective(58, 4f / 3f, .1f, 500);
        camera.setLocation(location);
        camera.lookAt(target, Vector3f.UNIT_Y);
        camera.update();
        return camera;
    }
    private static HeightfieldPage makePage() {
        float[] heights = new float[(CELLS + 3) * (CELLS + 3)];
        for (int z = -1; z <= CELLS + 1; z++) for (int x = -1; x <= CELLS + 1; x++) {
            heights[(z + 1) * (CELLS + 3) + x + 1] = (float) (4 * Math.sin(x * .085) * Math.cos(z * .06)
                    + 8 * Math.exp(-((x - 80) * (x - 80) + (z - 62) * (z - 62)) / 1100.0));
        }
        return new HeightfieldPage(CELLS, .5f, heights);
    }
}
