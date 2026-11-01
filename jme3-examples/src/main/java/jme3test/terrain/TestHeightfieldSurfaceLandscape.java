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
import com.jme3.input.KeyInput;
import com.jme3.input.controls.ActionListener;
import com.jme3.input.controls.KeyTrigger;
import com.jme3.light.AmbientLight;
import com.jme3.light.DirectionalLight;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector2f;
import com.jme3.math.Vector3f;
import com.jme3.post.SceneProcessor;
import com.jme3.renderer.Caps;
import com.jme3.profile.AppProfiler;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.ViewPort;
import com.jme3.renderer.opengl.GLFence;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.system.AppSettings;
import com.jme3.terrain.heightfield.HeightfieldGrid;
import com.jme3.terrain.heightfield.HeightfieldPage;
import com.jme3.terrain.heightfield.HeightfieldSurfacePage;
import com.jme3.terrain.heightfield.HeightfieldSurfaceResidency;
import com.jme3.terrain.heightfield.HeightfieldSurfaceResidency.PageKey;
import com.jme3.texture.FrameBuffer;
import com.jme3.texture.Image;
import com.jme3.texture.Texture;
import com.jme3.texture.Texture2D;
import com.jme3.texture.TextureArray;
import com.jme3.texture.image.ColorSpace;
import com.jme3.util.BufferUtils;
import java.awt.image.BufferedImage;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;

/**
 * Original rolling terrain with independently streamed material pages.
 * No arguments enables exploration; 1/2/3 select camera poses, P selects exact
 * filter taps, and L holds a diagnostic logical mip. Assertion-enabled finite
 * mode takes renderer and output directory; "detail" adds 640x480 cached views.
 */
public final class TestHeightfieldSurfaceLandscape extends SimpleApplication {
    private static final int CELLS = 128;
    private static final float SPACING = .5f;
    private static final int RESOLUTION = 32, MIPS = 3, GUTTER = 2, SIDE = 36, SLOTS = 16;
    private static final int HEIGHT_SLOTS = 1;
    private static final int CAPTURE_WIDTH = 192, CAPTURE_HEIGHT = 144;
    // Covers half-float color quantization and this fixture's filtered PBR response.
    private static final float HDR_TOLERANCE = .001f;
    private static final int[] CAPTURE_FRAMES = {2, 12, 45, 70, 86, 95, 100, 105};
    private static final int[] CAPTURE_CAMERAS = {2, 2, 2, 2, 2, 2, 0, 1};
    private static final boolean[] COMPARE_CAPTURE = {false, false, false, false, false, true, true, true};
    private static final String[] CAPTURE_NAMES = {
        "root-fallback", "base-overview", "appearance-overview", "semantic-overview",
        "height-streaming", "height-edit-overview", "final-close", "final-grazing"
    };

    private final Node terrain = new Node("surface-landscape");
    private final List<Material> materials = new ArrayList<>();
    private HeightfieldSurfaceResidency pool;
    private TextureArray albedoPages, normalPages, semanticPages, heightPages;
    private Texture2D pageTable;
    private Geometry heightGeometry;
    private final List<LayeredSurfaceSource> sources = new ArrayList<>();
    private final List<HeightfieldPage> heightData = new ArrayList<>();
    private final CountDownLatch finished = new CountDownLatch(1);
    private final Path output;
    private final boolean finiteRun;
    private final boolean detailCapture;
    private final int captureWidth, captureHeight;
    private volatile Throwable failure;
    private com.jme3.texture.FrameBuffer ownedTarget;
    private com.jme3.texture.FrameBuffer priorTarget;
    private ByteBuffer capturePixels;
    private HeightfieldSurfaceResidency.Frame comparisonLease;
    private HeightfieldSurfaceResidency.Frame frameLease;
    private float[] cachedCapture;
    private Vector3f originalCameraLocation;
    private Quaternion originalCameraRotation;
    private int finiteStage, captureIndex, captureCount, comparisonCount, uploadCount, uploadAttempts;
    private int peakInFlightFrames, peakResidentSlots, peakPreparedPages;
    private float maximumHdrDifference;
    private boolean restored;
    private int frame;
    private boolean childrenPublished;
    private boolean injectedPartialUpload;
    private List<PageKey> pinnedKeys = new ArrayList<>();
    private List<PageKey> mappedKeys = new ArrayList<>();

    private TestHeightfieldSurfaceLandscape(boolean finiteRun, Path output, boolean detailCapture) {
        this.finiteRun = finiteRun;
        this.output = output;
        this.detailCapture = detailCapture;
        captureWidth = detailCapture ? 640 : CAPTURE_WIDTH;
        captureHeight = detailCapture ? 480 : CAPTURE_HEIGHT;
    }

    public static void main(String[] args) throws Exception {
        boolean assertions = false;
        assert assertions = true;
        if (args.length != 0 && ((args.length != 2 && args.length != 3) || !assertions
                || (args.length == 3 && !"detail".equals(args[2])))) {
            throw new IllegalArgumentException("Finite mode requires -ea and: <renderer> <output-directory> [detail]");
        }
        boolean finite = args.length >= 2;
        TestHeightfieldSurfaceLandscape app = new TestHeightfieldSurfaceLandscape(finite,
                finite ? Paths.get(args[1]) : null, args.length == 3);
        AppSettings settings = new AppSettings(true);
        settings.setResolution(finite ? app.captureWidth : 1280, finite ? app.captureHeight : 800);
        settings.setAudioRenderer(null);
        settings.setGammaCorrection(true);
        if (finite) settings.setRenderer(args[0]);
        app.setSettings(settings);
        app.setShowSettings(false);
        app.start();
        if (finite) {
            boolean completed = app.finished.await(100, TimeUnit.SECONDS);
            app.stop(true);
            if (!completed) throw new IllegalStateException("Finite landscape capture timed out", app.failure);
            if (app.failure != null) throw new IllegalStateException("Finite landscape capture failed", app.failure);
        }
    }

    @Override public void simpleInitApp() {
        flyCam.setEnabled(!finiteRun);
        flyCam.setMoveSpeed(42);
        flyCam.setDragToRotate(true);
        flyCam.setEnabled(!finiteRun);
        setDisplayFps(!finiteRun);
        setDisplayStatView(false);
        if (!renderer.getCaps().contains(Caps.TextureArray)
                || !renderer.getCaps().contains(Caps.FloatTextureFilter)
                || (finiteRun && !renderer.getCaps().contains(Caps.FloatTexture))) {
            throw new UnsupportedOperationException("Heightfield surfaces need texture arrays and float filtering");
        }
        cam.setLocation(new Vector3f(24, 8, 12));
        cam.lookAt(new Vector3f(32, 1, 30), Vector3f.UNIT_Y);
        originalCameraLocation = cam.getLocation().clone();
        originalCameraRotation = cam.getRotation().clone();
        if (!finiteRun) {
            inputManager.addMapping("LandscapeClose", new KeyTrigger(KeyInput.KEY_1));
            inputManager.addMapping("LandscapeGrazing", new KeyTrigger(KeyInput.KEY_2));
            inputManager.addMapping("LandscapeOverview", new KeyTrigger(KeyInput.KEY_3));
            inputManager.addMapping("LandscapePrecise", new KeyTrigger(KeyInput.KEY_P));
            inputManager.addMapping("LandscapeFixedLod", new KeyTrigger(KeyInput.KEY_L));
            inputManager.addListener(cameraAndQuality, "LandscapeClose", "LandscapeGrazing",
                    "LandscapeOverview", "LandscapePrecise", "LandscapeFixedLod");
        }
        viewPort.setBackgroundColor(new ColorRGBA(.045f, .08f, .13f, 1));
        cam.setFrustumPerspective(50, (float) cam.getWidth() / cam.getHeight(), .25f, 250);
        rootNode.attachChild(terrain);
        AmbientLight sky = new AmbientLight();
        sky.setColor(new ColorRGBA(.28f, .34f, .43f, 1));
        rootNode.addLight(sky);
        DirectionalLight sun = new DirectionalLight();
        sun.setDirection(new Vector3f(-.55f, -.8f, -.35f).normalizeLocal());
        sun.setColor(new ColorRGBA(1.7f, 1.55f, 1.35f, 1));
        rootNode.addLight(sun);
        initStorage();
        installRevision(new HeightfieldSurfacePage.Revision(0, 0, 0));
        if (finiteRun) initializeFiniteCapture();
        viewPort.addProcessor(new SurfaceUseFence());
    }

    private void initializeFiniteCapture() {
        priorTarget = viewPort.getOutputFrameBuffer();
        ownedTarget = new FrameBuffer(captureWidth, captureHeight, 1);
        ownedTarget.addColorTarget(FrameBuffer.FrameBufferTarget.newTarget(Image.Format.RGBA32F));
        ownedTarget.setDepthTarget(FrameBuffer.FrameBufferTarget.newTarget(Image.Format.Depth24));
        viewPort.setOutputFrameBuffer(ownedTarget);
        capturePixels = BufferUtils.createByteBuffer(captureWidth * captureHeight * 16)
                .order(ByteOrder.nativeOrder());
    }

    private void setCaptureCamera(int index) {
        if (index == 0) {
            cam.setLocation(new Vector3f(24, 8, 12));
            cam.lookAt(new Vector3f(32, 1, 30), Vector3f.UNIT_Y);
        } else if (index == 1) {
            cam.setLocation(new Vector3f(-18, 8, 14));
            cam.lookAt(new Vector3f(42, 1, 22), Vector3f.UNIT_Y);
        } else {
            cam.setLocation(new Vector3f(32, 100, -20));
            cam.lookAt(new Vector3f(32, 0, 32), Vector3f.UNIT_Y);
        }
    }

    private boolean compareCapture() {
        return !detailCapture && COMPARE_CAPTURE[captureIndex];
    }

    private void processFiniteCapture() {
        if (failure != null || (finiteStage != 2 && finiteStage != 3)) return;
        try {
            if (finiteStage == 2) {
                cachedCapture = readCapture();
                writePng(cachedCapture, CAPTURE_NAMES[captureIndex]);
                captureCount++;
                if (compareCapture()) {
                    setDirectReference(true);
                    finiteStage = 3;
                } else {
                    submitFrameLease();
                    advanceCapturePlan();
                }
                return;
            }
            float[] direct = readCapture();
            float maximum = 0f;
            for (int i = 0; i < cachedCapture.length; i++) {
                float difference = Math.abs(cachedCapture[i] - direct[i]);
                maximum = Math.max(maximum, difference);
                float tolerance = HDR_TOLERANCE * Math.max(1f, Math.abs(direct[i]));
                if (difference > tolerance) {
                    throw new IllegalStateException("Cached/direct HDR mismatch camera=" + CAPTURE_NAMES[captureIndex]
                            + " channel=" + i + " cached=" + cachedCapture[i] + " direct=" + direct[i]
                            + " difference=" + difference + " tolerance=" + tolerance);
                }
            }
            maximumHdrDifference = Math.max(maximumHdrDifference, maximum);
            comparisonCount++;
            submitComparisonLease();
            setDirectReference(false);
            advanceCapturePlan();
        } catch (Throwable error) {
            fail(error);
        }
    }

    private float[] readCapture() {
        capturePixels.clear();
        renderer.readFrameBufferWithFormat(ownedTarget, capturePixels, Image.Format.RGBA32F);
        FloatBuffer values = capturePixels.asFloatBuffer();
        float[] result = new float[values.remaining()];
        values.get(result);
        for (float value : result) {
            if (!Float.isFinite(value)) throw new IllegalStateException("Nonfinite HDR landscape capture");
        }
        return result;
    }

    private void submitComparisonLease() {
        if (comparisonLease == null) return;
        GLFence fence = renderer.createFence();
        if (!pool.submit(comparisonLease, new HeightfieldSurfaceResidency.Completion() {
            @Override public boolean isComplete() { return renderer.isFenceSignaled(fence); }
            @Override public void close() { renderer.deleteFence(fence); }
        })) {
            renderer.deleteFence(fence);
            throw new IllegalStateException("Could not retire comparison page lease");
        }
        comparisonLease = null;
    }

    private void setDirectReference(boolean direct) {
        for (Material material : materials) material.setBoolean("SurfaceDirectReference", direct);
    }

    private void advanceCapturePlan() {
        captureIndex++;
        finiteStage = captureIndex == CAPTURE_FRAMES.length ? 4 : 0;
    }

    private void writePng(float[] rgba, String name) throws Exception {
        Files.createDirectories(output);
        BufferedImage image = new BufferedImage(captureWidth, captureHeight, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < captureHeight; y++) for (int x = 0; x < captureWidth; x++) {
            int offset = ((captureHeight - 1 - y) * captureWidth + x) * 4;
            int r = displayByte(rgba[offset]), g = displayByte(rgba[offset + 1]), b = displayByte(rgba[offset + 2]);
            image.setRGB(x, y, r << 16 | g << 8 | b);
        }
        ImageIO.write(image, "png", output.resolve("landscape-" + name + ".png").toFile());
    }

    private int displayByte(float linear) {
        linear = Math.max(0f, Math.min(1f, linear));
        float srgb = linear <= .0031308f ? linear * 12.92f
                : 1.055f * (float) Math.pow(linear, 1f / 2.4f) - .055f;
        return Math.max(0, Math.min(255, Math.round(srgb * 255f)));
    }

    @Override public void handleError(String message, Throwable error) {
        fail(error == null ? new IllegalStateException(message) : error);
    }

    private void fail(Throwable error) {
        if (failure == null) failure = error;
        error.printStackTrace();
        if (comparisonLease != null && pool != null) {
            pool.abort(comparisonLease);
            comparisonLease = null;
        }
        if (frameLease != null && pool != null) {
            pool.abort(frameLease);
            frameLease = null;
        }
        restoreOwnedTarget();
        finished.countDown();
    }

    private void restoreOwnedTarget() {
        if (!finiteRun || restored || viewPort == null) return;
        viewPort.setOutputFrameBuffer(priorTarget);
        if (cam != null && originalCameraLocation != null) {
            cam.setLocation(originalCameraLocation);
            cam.setRotation(originalCameraRotation);
        }
        restored = true;
    }

    private void initStorage() {
        pool = new HeightfieldSurfaceResidency(RESOLUTION, MIPS,
                new HeightfieldSurfaceResidency.Limits(SLOTS, 12, 12L * 1840 * HeightfieldSurfacePage.CPU_BYTES_PER_TEXEL,
                        12L * 1840 * HeightfieldSurfacePage.CPU_BYTES_PER_TEXEL, 1, 1840L * 25, SLOTS, 8));
        albedoPages = atlas(Image.Format.RGBA16F, 8, false);
        normalPages = atlas(Image.Format.RGBA32F, 16, false);
        semanticPages = atlas(Image.Format.Luminance8, 1, true);
        List<Image> heightImages = new ArrayList<>();
        for (int i = 0; i < HEIGHT_SLOTS; i++) {
            heightImages.add(new Image(Image.Format.Luminance32F, CELLS + 3, CELLS + 3,
                    BufferUtils.createByteBuffer((CELLS + 3) * (CELLS + 3) * 4), ColorSpace.Linear));
        }
        heightPages = new TextureArray(heightImages);
        nearest(heightPages);
        ByteBuffer table = BufferUtils.createByteBuffer(2 * 2 * 16).order(ByteOrder.nativeOrder());
        pageTable = new Texture2D(new Image(Image.Format.RGBA32F, 2, 2, table, ColorSpace.Linear));
        nearest(pageTable);
    }

    private TextureArray atlas(Image.Format format, int bytes, boolean categorical) {
        List<Image> layers = new ArrayList<>();
        for (int i = 0; i < SLOTS * MIPS; i++) {
            layers.add(new Image(format, SIDE, SIDE,
                    BufferUtils.createByteBuffer(SIDE * SIDE * bytes), ColorSpace.Linear));
        }
        TextureArray array = new TextureArray(layers);
        if (categorical) nearest(array);
        else {
            array.setMinFilter(Texture.MinFilter.BilinearNoMipMaps);
            array.setMagFilter(Texture.MagFilter.Bilinear);
            array.setWrap(Texture.WrapMode.EdgeClamp);
            array.setAnisotropicFilter(1);
        }
        return array;
    }

    private static void nearest(Texture texture) {
        texture.setMinFilter(Texture.MinFilter.NearestNoMipMaps);
        texture.setMagFilter(Texture.MagFilter.Nearest);
        texture.setWrap(Texture.WrapMode.EdgeClamp);
    }

    private void installRevision(HeightfieldSurfacePage.Revision revision) {
        LayeredSurfaceSource source = new LayeredSurfaceSource(revision);
        sources.add(source);
        source.createTextures();
        List<PageKey> rootKeys = Arrays.asList(new PageKey(revision, 0, 0, 4));
        for (PageKey key : rootKeys) prepare(source, key);
        pool.uploadPrepared((slot, page) -> {
            uploadAttempts++;
            return upload(slot, page);
        });
        peakResidentSlots = Math.max(peakResidentSlots, pool.stats().residentPayloadSlots);
        publishTable(rootKeys);
        installHeightPages(revision);
        if (!pool.pin(rootKeys.get(0))) throw new IllegalStateException("Could not retain root fallback");
        for (PageKey old : pinnedKeys) {
            pool.unpin(old);
            if (!pool.evict(old)) throw new IllegalStateException("Could not retire the old surface revision");
        }
        pinnedKeys.clear();
        pinnedKeys = new ArrayList<>(rootKeys);
        childrenPublished = false;
        attachRoot(revision, 0);
        requestChildren();
    }

    private void prepare(LayeredSurfaceSource source, PageKey key) {
        HeightfieldSurfaceResidency.Request request = pool.request(key);
        if (request == null || !pool.complete(request, new HeightfieldSurfacePage(key.revision,
                key.originX, key.originZ, RESOLUTION, key.baseStride, MIPS, source))) {
            throw new IllegalStateException("Could not prepare landscape surface page " + key);
        }
    }

    private boolean upload(int slot, HeightfieldSurfacePage page) {
        for (int mip = 0; mip < MIPS; mip++) {
            int layer = slot * MIPS + mip;
            write(albedoPages, page.createAlbedoImage(mip), layer);
            write(normalPages, page.createNormalImage(mip), layer);
            write(semanticPages, page.createSemanticImage(mip), layer);
        }
        uploadCount++;
        return true;
    }

    private void write(TextureArray destination, Image image, int layer) {
        try { renderer.modifyTexture(destination, image, layer, 0, 0); }
        finally { BufferUtils.destroyDirectBuffer(image.getData(0)); }
    }

    private void publishTable(List<PageKey> keys) {
        ByteBuffer data = pageTable.getImage().getData(0);
        data.clear();
        for (int z = 0; z < 2; z++) for (int x = 0; x < 2; x++) {
            float fx = x * 64 + 32;
            float fz = z * 64 + 32;
            PageKey selected = keys.get(0);
            for (PageKey key : keys) {
                if (fx >= key.originX && fx < key.originX + RESOLUTION * key.baseStride
                        && fz >= key.originZ && fz < key.originZ + RESOLUTION * key.baseStride) selected = key;
            }
            HeightfieldSurfaceResidency.Handle handle = pool.ready(selected);
            if (handle == null) throw new IllegalStateException("Publishing a page before its upload is ready");
            data.putFloat(selected.originX).putFloat(selected.originZ).putFloat(selected.baseStride)
                    .putFloat(handle.getSlot() * MIPS);
        }
        data.flip();
        renderer.modifyTexture(pageTable, pageTable.getImage(), 0, 0);
        mappedKeys = new ArrayList<>(keys);
    }

    private void installHeightPages(HeightfieldSurfacePage.Revision revision) {
        HeightfieldGrid grid = new HeightfieldGrid(CELLS);
        List<Image> images = new ArrayList<>();
        heightData.clear();
        HeightfieldPage page = createHeightPage(0, 0, revision);
        heightData.add(page);
        Image image = page.createImage();
        images.add(image);
        if (materials.isEmpty()) {
            Geometry patch = new Geometry("heightfield-landscape-64m", grid.createPatchMesh(page));
            patch.setModelBound(page.createBound());
            Material material = new Material(assetManager,
                    "Common/MatDefs/Terrain/Heightfield/Heightfield.j3md");
            material.setTexture("HeightPages", heightPages);
            material.setInt("HeightLayer", 0);
            material.setFloat("SampleSpacing", SPACING);
            bindSurface(material, revision);
            patch.setMaterial(material);
            materials.add(material);
            heightGeometry = patch;
            terrain.attachChild(patch);
        } else {
            bindSurface(materials.get(0), revision);
            heightGeometry.setModelBound(page.createBound());
        }
        renderer.modifyTexture(heightPages, image, 0, 0, 0);
        BufferUtils.destroyDirectBuffer(image.getData(0));
    }

    private HeightfieldPage createHeightPage(int ox, int oz, HeightfieldSurfacePage.Revision revision) {
        int side = CELLS + 3;
        float[] samples = new float[side * side];
        for (int z = -1; z <= CELLS + 1; z++) for (int x = -1; x <= CELLS + 1; x++) {
            double wx = (ox + x) * SPACING, wz = (oz + z) * SPACING;
            samples[(z + 1) * side + x + 1] = LayeredSurfaceSource.heightAt(wx, wz, revision);
        }
        return new HeightfieldPage(CELLS, SPACING, samples);
    }

    private void bindSurface(Material material, HeightfieldSurfacePage.Revision revision) {
        LayeredSurfaceSource source = sources.get(sources.size() - 1);
        HeightfieldSurfaceBindings.bind(material, albedoPages, normalPages, semanticPages,
                pageTable, SIDE, GUTTER, MIPS, new Vector2f(0, 0), 64, SPACING);
        material.setTexture("SurfaceSourceAlbedo", source.albedoTexture);
        material.setTexture("SurfaceSourceNormal", source.normalTexture);
        material.setTexture("SurfaceSourceWeights", source.weightTexture);
        material.setVector2("SurfaceSourceOriginFine", new Vector2f(-64, -64));
        material.setBoolean("SurfaceDirectReference", false);
        material.setBoolean("SurfacePreciseFilter", false);
        material.setFloat("SurfaceFixedLod", -1);
    }

    private void attachRoot(HeightfieldSurfacePage.Revision revision, int heightLayer) {
        // Height geometry is attached by installHeightPages. Its first material
        // starts on the coarsest resident surface; following frames replace it
        // with four finer page-table records once every child has uploaded.
        for (Material material : materials) bindSurface(material, revision);
    }

    @Override public void simpleUpdate(float tpf) {
        frame++;
        pool.poll();
        HeightfieldSurfaceResidency.Stats stats = pool.stats();
        peakInFlightFrames = Math.max(peakInFlightFrames, stats.frames);
        peakResidentSlots = Math.max(peakResidentSlots, stats.readySlots + stats.retiredSlots);
        peakPreparedPages = Math.max(peakPreparedPages, stats.preparedPages);
        if (finiteRun && finiteStage == 4 && pool.stats().frames == 0) {
            System.out.println("LANDSCAPE_FINITE_PASS captures=" + captureCount
                    + " comparisons=" + comparisonCount + " pixels="
                    + (long) comparisonCount * captureWidth * captureHeight
                    + " uploads=" + uploadCount + " uploadAttempts=" + uploadAttempts
                    + " maxUploadsPerFrame=1 slots=" + SLOTS
                    + " peakResidentSlots=" + peakResidentSlots + " peakPreparedPages=" + peakPreparedPages
                    + " peakFrames=" + peakInFlightFrames + " maxHDRDifference=" + maximumHdrDifference);
            restoreOwnedTarget();
            finiteStage = 5;
            finished.countDown();
        }
        if (childKeys != null && !childrenPublished) {
            pool.uploadPrepared((slot, page) -> uploadWithRetryCase(slot, page));
            if (childKeys.stream().allMatch(key -> pool.ready(key) != null)) {
                publishTable(childKeys);
                for (PageKey child : childKeys) {
                    if (!pool.pin(child)) throw new IllegalStateException("Could not pin complete child set");
                }
                pinnedKeys.addAll(childKeys);
                childrenPublished = true;
                childKeys = null;
            }
        }
        if (frame == 35) installRevision(new HeightfieldSurfacePage.Revision(0, 1, 0));
        if (frame == 60) installRevision(new HeightfieldSurfacePage.Revision(0, 1, 1));
        if (frame == 85) installRevision(new HeightfieldSurfacePage.Revision(1, 1, 1));
        if (finiteRun && frame > 120 && finiteStage != 5) fail(new IllegalStateException("Finite capture exceeded 120 frames"));
        if (finiteRun && captureIndex < CAPTURE_FRAMES.length && finiteStage == 0
                && frame >= CAPTURE_FRAMES[captureIndex]
                && (childrenPublished || captureIndex == 0 || captureIndex == 4)) {
            setCaptureCamera(CAPTURE_CAMERAS[captureIndex]);
            finiteStage = 1;
        } else if (finiteRun && finiteStage == 1) {
            finiteStage = 2;
        }
        if (frame % 240 == 0) {
            Vector3f location = cam.getLocation();
            if (location.y < 4) cam.setLocation(new Vector3f(location.x, 4, location.z));
        }
    }

    private void requestChildren() {
        LayeredSurfaceSource source = sources.get(sources.size() - 1);
        List<PageKey> children = new ArrayList<>();
        children.add(new PageKey(source.revision, 0, 0, 2));
        children.add(new PageKey(source.revision, 64, 0, 2));
        children.add(new PageKey(source.revision, 0, 64, 2));
        children.add(new PageKey(source.revision, 64, 64, 2));
        for (PageKey key : children) prepare(source, key);
        // The bounded upload budget advances by one whole page each frame. The
        // table is only switched after all children report READY.
        childKeys = children;
    }

    private boolean uploadWithRetryCase(int slot, HeightfieldSurfacePage page) {
        uploadAttempts++;
        if (!injectedPartialUpload) {
            injectedPartialUpload = true;
            Image partial = page.createAlbedoImage(0);
            write(albedoPages, partial, slot * MIPS);
            System.out.println("LANDSCAPE_INJECTED_PARTIAL_UPLOAD retry=full-page");
            return false;
        }
        return upload(slot, page);
    }

    private List<PageKey> childKeys;

    private boolean preciseFilter;
    private boolean fixedLod;
    private final ActionListener cameraAndQuality = (name, pressed, tpf) -> {
        if (!pressed) return;
        if ("LandscapeClose".equals(name)) {
            cam.setLocation(new Vector3f(24, 8, 12));
            cam.lookAt(new Vector3f(32, 1, 30), Vector3f.UNIT_Y);
        } else if ("LandscapeGrazing".equals(name)) {
            cam.setLocation(new Vector3f(-18, 8, 14));
            cam.lookAt(new Vector3f(42, 1, 22), Vector3f.UNIT_Y);
        } else if ("LandscapeOverview".equals(name)) {
            cam.setLocation(new Vector3f(32, 100, -20));
            cam.lookAt(new Vector3f(32, 0, 32), Vector3f.UNIT_Y);
        } else if ("LandscapePrecise".equals(name)) {
            preciseFilter = !preciseFilter;
            updateQualityModes();
        } else if ("LandscapeFixedLod".equals(name)) {
            fixedLod = !fixedLod;
            updateQualityModes();
        }
    };

    private void updateQualityModes() {
        for (Material material : materials) {
            material.setBoolean("SurfacePreciseFilter", preciseFilter);
            material.setFloat("SurfaceFixedLod", fixedLod ? 1.25f : -1f);
        }
    }

    @Override public void destroy() {
        restoreOwnedTarget();
        releaseRendererStorage();
        super.destroy();
    }

    private void releaseRendererStorage() {
        if (renderer == null) return;
        if (pool != null) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (pool.stats().frames > 0 && System.nanoTime() < deadline) {
                pool.poll();
                try { Thread.sleep(1); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while draining terrain surface fences", interrupted);
                }
            }
            if (pool.stats().frames > 0) {
                throw new IllegalStateException("Surface GPU fences did not drain before native cleanup");
            }
        }
        if (ownedTarget != null) renderer.deleteFrameBuffer(ownedTarget);
        for (TextureArray texture : Arrays.asList(heightPages, albedoPages, normalPages, semanticPages)) {
            if (texture != null) release(texture.getImage());
        }
        if (pageTable != null) release(pageTable.getImage());
        if (pool != null) pool.close();
        for (LayeredSurfaceSource source : sources) source.close(renderer);
        if (capturePixels != null) BufferUtils.destroyDirectBuffer(capturePixels);
        ownedTarget = null;
        capturePixels = null;
    }

    private final class SurfaceUseFence implements SceneProcessor {
        private boolean initialized;
        @Override public void initialize(RenderManager manager, ViewPort viewport) { initialized = true; }
        @Override public boolean isInitialized() { return initialized; }
        @Override public void reshape(ViewPort viewport, int width, int height) { }
        @Override public void preFrame(float tpf) {
            if (mappedKeys.isEmpty() || (finiteRun && finiteStage >= 4)) return;
            if (finiteRun && finiteStage == 2 && compareCapture()) {
                if (comparisonLease == null) comparisonLease = pool.acquire(mappedKeys);
                if (comparisonLease == null) fail(new IllegalStateException("Could not lease comparison pages"));
                return;
            }
            if (finiteRun && finiteStage == 3) return;
            frameLease = pool.acquire(mappedKeys);
            if (frameLease == null) fail(new IllegalStateException("Could not lease displayed surface pages"));
        }
        @Override public void postQueue(RenderQueue queue) { }
        @Override public void setProfiler(AppProfiler profiler) { }
        @Override public void postFrame(com.jme3.texture.FrameBuffer out) {
            if (finiteRun) {
                int stageBeforeCapture = finiteStage;
                processFiniteCapture();
                if (stageBeforeCapture != 2 && stageBeforeCapture != 3 && finiteStage != 4) {
                    submitFrameLease();
                }
                return;
            }
            submitFrameLease();
        }
        @Override public void cleanup() { initialized = false; }
    }

    private void submitFrameLease() {
        if (frameLease == null) return;
        GLFence fence = renderer.createFence();
        if (!pool.submit(frameLease, new HeightfieldSurfaceResidency.Completion() {
            @Override public boolean isComplete() { return renderer.isFenceSignaled(fence); }
            @Override public void close() { renderer.deleteFence(fence); }
        })) {
            renderer.deleteFence(fence);
            pool.abort(frameLease);
            frameLease = null;
            fail(new IllegalStateException("Could not retire displayed surface page lease"));
            return;
        }
        frameLease = null;
    }

    private void release(Image image) {
        renderer.deleteImage(image);
        for (ByteBuffer buffer : image.getData()) BufferUtils.destroyDirectBuffer(buffer);
    }
}
