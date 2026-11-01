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
import com.jme3.math.FastMath;
import com.jme3.math.Vector2f;
import com.jme3.post.SceneProcessor;
import com.jme3.profile.AppProfiler;
import com.jme3.renderer.Camera;
import com.jme3.renderer.Caps;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.ViewPort;
import com.jme3.renderer.opengl.GLFence;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.Geometry;
import com.jme3.scene.shape.Quad;
import com.jme3.system.AppSettings;
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
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;

/**
 * Finite source/cache reference check. Separate array layers hold separately
 * composed logical mips, with world-neighbor gutters and categorical semantics.
 * This initial control is deliberately unlit and does not establish production
 * material quality, edit/streaming, grazing or performance acceptance.
 */
public final class TestHeightfieldSurfaces extends SimpleApplication {
    private static final int RESOLUTION = 32, MIPS = 3, SIDE = 36, SLOTS = 8;
    private static final long CPU_PAGE_BYTES = 1840L * HeightfieldSurfacePage.CPU_BYTES_PER_TEXEL, ENCODED_PAGE_BYTES = 1840L * 25;
    private final Path output;
    private final CountDownLatch finished = new CountDownLatch(1);
    private volatile Throwable failure;
    private LayeredSurfaceSource source;
    private HeightfieldSurfaceResidency pool;
    private TextureArray albedoPages, normalPages, semanticPages;
    private Texture2D pageTable;
    private Material probeMaterial;
    private Geometry probeQuad;
    private FrameBuffer probeTarget;
    private Camera probeCamera;
    private ByteBuffer pixels;
    private List<PageKey> mappedKeys;
    private PageKey root;
    private int frame, channels, uploads, scratchPeak;
    private float maxAlbedoError, maxMomentError, maxDetailWeightError;

    private TestHeightfieldSurfaces(Path output) { this.output = output; }
    public static void main(String[] args) throws Exception {
        boolean assertions = false;
        assert assertions = true;
        if (!assertions || args.length != 2) throw new IllegalArgumentException("Use -ea, renderer and output directory");
        TestHeightfieldSurfaces app = new TestHeightfieldSurfaces(Paths.get(args[1]));
        AppSettings settings = new AppSettings(true);
        settings.setRenderer(args[0]);
        settings.setResolution(640, 480);
        settings.setAudioRenderer(null);
        settings.setGammaCorrection(true);
        settings.setVSync(false);
        app.setSettings(settings);
        app.setShowSettings(false);
        app.start();
        if (!app.finished.await(100, TimeUnit.SECONDS)) {
            app.stop(false);
            throw new IllegalStateException("Surface control timeout", app.failure);
        }
        app.stop(true);
        if (app.failure != null) throw new IllegalStateException("Surface control failed", app.failure);
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
            setDisplayFps(false);
            setDisplayStatView(false);
            if (!renderer.getCaps().contains(Caps.TextureArray)
                    || !renderer.getCaps().contains(Caps.FloatTextureFilter)) {
                throw new UnsupportedOperationException("Surface reference needs arrays and float linear filtering");
            }
            source = new LayeredSurfaceSource(new HeightfieldSurfacePage.Revision(0, 0, 0));
            verifySourceDetailWeight();
            source.createTextures();
            pool = new HeightfieldSurfaceResidency(RESOLUTION, MIPS,
                    new HeightfieldSurfaceResidency.Limits(SLOTS, 6, CPU_PAGE_BYTES * 6,
                            CPU_PAGE_BYTES * 6, 1, ENCODED_PAGE_BYTES, SLOTS, 4));
            albedoPages = atlas(Image.Format.RGBA16F, 8, false);
            normalPages = atlas(Image.Format.RGBA32F, 16, false);
            semanticPages = atlas(Image.Format.Luminance8, 1, true);
            ByteBuffer tableData = BufferUtils.createByteBuffer(2 * 2 * 16).order(ByteOrder.nativeOrder());
            pageTable = new Texture2D(new Image(Image.Format.RGBA32F, 2, 2, tableData, ColorSpace.Linear));
            nearest(pageTable);
            probeTarget = new FrameBuffer(64, 48, 1);
            probeTarget.addColorTarget(FrameBuffer.FrameBufferTarget.newTarget(Image.Format.RGBA32F));
            probeCamera = new Camera(64, 48);
            pixels = BufferUtils.createByteBuffer(64 * 48 * 16).order(ByteOrder.nativeOrder());
            probeQuad = new Geometry("surface-numerical-fullscreen-quad", new Quad(1, 1));
            probeQuad.updateGeometricState();
            probeMaterial = new Material(assetManager, "jme3test/terrain/HeightfieldSurfaceProbe.j3md");
            probeMaterial.setTexture("SurfaceAlbedoPages", albedoPages);
            probeMaterial.setTexture("SurfaceNormalPages", normalPages);
            probeMaterial.setTexture("SurfaceSemanticPages", semanticPages);
            probeMaterial.setTexture("SurfacePageTable", pageTable);
            probeMaterial.setTexture("SurfaceSourceAlbedo", source.albedoTexture);
            probeMaterial.setTexture("SurfaceSourceNormal", source.normalTexture);
            probeMaterial.setTexture("SurfaceSourceWeights", source.weightTexture);
            probeQuad.setMaterial(probeMaterial);
            root = new PageKey(source.revision, 0, 0, 4);
            prepare(root);
            pool.uploadPrepared(this::upload);
            if (!pool.pin(root)) throw new IllegalStateException("Root not ready");
            map(Collections.nCopies(4, root));
            viewPort.addProcessor(new Capture());
        } catch (Throwable error) { fail(error); }
    }

    private TextureArray atlas(Image.Format format, int bytesPerTexel, boolean categorical) {
        List<Image> layers = new ArrayList<>();
        for (int i = 0; i < SLOTS * MIPS; i++) {
            layers.add(new Image(format, SIDE, SIDE,
                    BufferUtils.createByteBuffer(SIDE * SIDE * bytesPerTexel), ColorSpace.Linear));
        }
        TextureArray texture = new TextureArray(layers);
        texture.setAnisotropicFilter(1);
        if (categorical) nearest(texture);
        else {
            texture.setMinFilter(Texture.MinFilter.BilinearNoMipMaps);
            texture.setMagFilter(Texture.MagFilter.Bilinear);
            texture.setWrap(Texture.WrapMode.EdgeClamp);
        }
        return texture;
    }

    private void verifySourceDetailWeight() {
        HeightfieldSurfacePage.Sample sample = new HeightfieldSurfacePage.Sample();
        float minimum = 1f, maximum = 0f;
        for (int z = LayeredSurfaceSource.ORIGIN + 3; z < LayeredSurfaceSource.ORIGIN + LayeredSurfaceSource.SIDE; z += 29) {
            for (int x = LayeredSurfaceSource.ORIGIN + 5; x < LayeredSurfaceSource.ORIGIN + LayeredSurfaceSource.SIDE; x += 31) {
                source.sample(x, z, sample);
                if (!Float.isFinite(sample.detailWeight) || sample.detailWeight < 0 || sample.detailWeight > 1) {
                    throw new IllegalStateException("Layered detail weight must stay within [0,1]");
                }
                minimum = Math.min(minimum, sample.detailWeight);
                maximum = Math.max(maximum, sample.detailWeight);
            }
        }
        if (!(maximum - minimum > .1f)) {
            throw new IllegalStateException("Layered fixture needs varying smooth detail weights, range="
                    + minimum + ".." + maximum);
        }
    }

    private static void nearest(Texture texture) {
        texture.setMinFilter(Texture.MinFilter.NearestNoMipMaps);
        texture.setMagFilter(Texture.MagFilter.Nearest);
        texture.setWrap(Texture.WrapMode.EdgeClamp);
    }

    private void prepare(PageKey key) {
        HeightfieldSurfaceResidency.Request request = pool.request(key);
        if (request == null || !pool.complete(request, new HeightfieldSurfacePage(key.revision,
                key.originX, key.originZ, RESOLUTION, key.baseStride, MIPS, source))) {
            throw new IllegalStateException("Fixture page preparation was refused");
        }
    }

    private boolean upload(int slot, HeightfieldSurfacePage page) {
        for (int mip = 0; mip < MIPS; mip++) {
            int layer = slot * MIPS + mip;
            write(albedoPages, page.createAlbedoImage(mip), layer);
            write(normalPages, page.createNormalImage(mip), layer);
            write(semanticPages, page.createSemanticImage(mip), layer);
        }
        uploads++;
        return true;
    }

    private void write(TextureArray destination, Image image, int layer) {
        scratchPeak = Math.max(scratchPeak, image.getData(0).remaining());
        try { renderer.modifyTexture(destination, image, layer, 0, 0); }
        finally { BufferUtils.destroyDirectBuffer(image.getData(0)); }
    }

    private void map(List<PageKey> keys) {
        ByteBuffer data = pageTable.getImage().getData(0);
        data.clear();
        for (PageKey key : keys) {
            HeightfieldSurfaceResidency.Handle handle = pool.ready(key);
            if (handle == null) throw new IllegalStateException("Cannot publish an unready surface mapping");
            data.putFloat(key.originX).putFloat(key.originZ).putFloat(key.baseStride)
                    .putFloat(handle.getSlot() * MIPS);
        }
        data.flip();
        renderer.modifyTexture(pageTable, pageTable.getImage(), 0, 0);
        mappedKeys = new ArrayList<>(new LinkedHashSet<>(keys));
    }

    @Override public void simpleUpdate(float tpf) {
        if (failure != null) return;
        frame++;
        pool.poll();
        if (frame == 5) {
            prepare(new PageKey(source.revision, 0, 0, 2));
            prepare(new PageKey(source.revision, 64, 0, 2));
            prepare(new PageKey(source.revision, 0, 64, 2));
            prepare(new PageKey(source.revision, 64, 64, 2));
        }
        if (frame >= 5 && frame <= 8) pool.uploadPrepared(this::upload);
        if (frame == 9) {
            map(Arrays.asList(new PageKey(source.revision, 0, 0, 2), new PageKey(source.revision, 64, 0, 2),
                    new PageKey(source.revision, 0, 64, 2), new PageKey(source.revision, 64, 64, 2)));
        }
    }

    private float[] read(boolean direct, float lod, int outputKind) {
        probeMaterial.setBoolean("SurfaceDirectReference", direct);
        probeMaterial.setFloat("SurfaceProbeLod", lod);
        probeMaterial.setInt("SurfaceProbeOutput", outputKind);
        renderer.setFrameBuffer(probeTarget);
        renderManager.setCamera(probeCamera, false);
        renderer.clearBuffers(true, false, false);
        renderManager.renderGeometry(probeQuad);
        pixels.clear();
        renderer.readFrameBufferWithFormat(probeTarget, pixels, Image.Format.RGBA32F);
        pixels.rewind();
        FloatBuffer raw = pixels.asFloatBuffer();
        float[] result = new float[raw.remaining()];
        raw.get(result);
        for (float value : result) if (!Float.isFinite(value)) throw new IllegalStateException("Nonfinite surface output");
        return result;
    }

    private float cpuChannel(int offset, float lod, int kind, boolean encodeAlbedo) {
        int pixel = offset / 4, component = offset % 4;
        double wx = ((pixel % 64) + .5) * 64 / 64;
        double wz = ((pixel / 64) + .5) * 64 / 48;
        PageKey key = mappedKeys.get(0);
        for (PageKey candidate : mappedKeys) {
            double span = RESOLUTION * candidate.baseStride * LayeredSurfaceSource.SPACING;
            double ox = candidate.originX * LayeredSurfaceSource.SPACING;
            double oz = candidate.originZ * LayeredSurfaceSource.SPACING;
            if (wx >= ox && wx < ox + span && wz >= oz && wz < oz + span) {
                key = candidate; break;
            }
        }
        HeightfieldSurfacePage page = pool.get(pool.ready(key));
        int lowerMip = (int) Math.floor(lod), upperMip = Math.min(lowerMip + 1, MIPS - 1);
        float lower = cpuLevel(page, wx, wz, lowerMip, kind, component, encodeAlbedo);
        float upper = cpuLevel(page, wx, wz, upperMip, kind, component, encodeAlbedo);
        return lower * (1 - (lod - lowerMip)) + upper * (lod - lowerMip);
    }

    private float cpuLevel(HeightfieldSurfacePage page, double wx, double wz, int mip,
            int kind, int component, boolean encodeAlbedo) {
        double stride = page.getBaseStride() * (1 << mip);
        double qx = (wx / LayeredSurfaceSource.SPACING - page.getOriginX()) / stride - .5;
        double qz = (wz / LayeredSurfaceSource.SPACING - page.getOriginZ()) / stride - .5;
        int x = (int) Math.floor(qx), z = (int) Math.floor(qz);
        double ax = qx - x, az = qz - z;
        return (float) ((cpuTexel(page, mip, x, z, kind, component, encodeAlbedo) * (1 - ax)
                + cpuTexel(page, mip, x + 1, z, kind, component, encodeAlbedo) * ax) * (1 - az)
                + (cpuTexel(page, mip, x, z + 1, kind, component, encodeAlbedo) * (1 - ax)
                + cpuTexel(page, mip, x + 1, z + 1, kind, component, encodeAlbedo) * ax) * az);
    }

    private float cpuTexel(HeightfieldSurfacePage page, int mip, int x, int z,
            int kind, int component, boolean encodeAlbedo) {
        if (kind == 0) {
            if (component == 3) {
                float value = page.getDetailWeight(mip, x, z);
                return encodeAlbedo ? FastMath.convertHalfToFloat(FastMath.convertFloatToHalf(value)) : value;
            }
            float value = page.getAlbedo(mip, x, z, component);
            return encodeAlbedo ? FastMath.convertHalfToFloat(FastMath.convertFloatToHalf(value)) : value;
        }
        return component == 3 ? page.getRoughnessSquared(mip, x, z) : page.getNormalMoment(mip, x, z, component);
    }

    private void verifyStoredAlbedo() {
        HeightfieldSurfaceResidency.Handle handle = pool.ready(root);
        HeightfieldSurfacePage page = pool.get(handle);
        for (int mip = 0; mip < MIPS; mip++) {
            int side = page.getTextureSize(mip);
            probeMaterial.setInt("SurfaceProbeRawLayer", handle.getSlot() * MIPS + mip);
            probeMaterial.setInt("SurfaceProbeRawSide", side);
            float[] actual = read(false, 0, 4);
            for (int y = 0; y < 48; y++) for (int x = 0; x < 64; x++) {
                int sx = (int) Math.floor((x + .5) * side / 64) - HeightfieldSurfacePage.GUTTER;
                int sz = (int) Math.floor((y + .5) * side / 48) - HeightfieldSurfacePage.GUTTER;
                for (int c = 0; c < 4; c++) {
                    float source = c == 3 ? page.getDetailWeight(mip, sx, sz) : page.getAlbedo(mip, sx, sz, c);
                    float expected = FastMath.convertHalfToFloat(FastMath.convertFloatToHalf(source));
                    float value = actual[(y * 64 + x) * 4 + c];
                    if (Math.abs(value - expected) > .000001f) throw new IllegalStateException(
                            "Stored atlas mismatch mip=" + mip + " texel=" + sx + "," + sz + " c=" + c
                            + " actual=" + value + " expected=" + expected);
                }
            }
        }
        System.out.println("SURFACE_STORED_ALBEDO_PASS");
    }

    private void verify(String label) throws Exception {
        HeightfieldSurfaceResidency.Frame lease = pool.acquire(mappedKeys);
        if (lease == null) throw new IllegalStateException("Could not acquire whole surface mapping");
        try {
            verifyStoredAlbedo();
            for (float lod : new float[]{0, 1, 2, 1.5f}) for (int kind = 0; kind < 3; kind++) {
                probeMaterial.setBoolean("SurfacePreciseFilter", true);
                float[] cached = read(false, lod, kind);
                float[] direct = read(true, lod, kind);
                float maximum = 0;
                for (int i = 0; i < cached.length; i++) {
                    float difference = Math.abs(cached[i] - direct[i]);
                    maximum = Math.max(maximum, difference);
                    if (kind == 0 && i % 4 == 3) {
                        maxDetailWeightError = Math.max(maxDetailWeightError, difference);
                    }
                    float tolerance = kind == 0 ? 2.5e-4f + 1e-5f : kind == 1 ? 1e-5f : 0;
                    if (difference > tolerance) {
                        float[] coordinates = read(false, lod, 5);
                        float[] taps = read(false, lod, 6);
                        int start = i - i % 4;
                        System.out.println("SURFACE_FAILED_COORD=" + Arrays.toString(Arrays.copyOfRange(coordinates, start, start + 4))
                                + " TAPS=" + Arrays.toString(Arrays.copyOfRange(taps, start, start + 4)));
                        throw new IllegalStateException("Surface direct/cache difference kind="
                            + kind + " lod=" + lod + " channel=" + i + " cached=" + cached[i] + " direct=" + direct[i]
                            + " cpuEncoded=" + cpuChannel(i, lod, kind, true) + " cpuUnencoded=" + cpuChannel(i, lod, kind, false)
                            + " cpuMip0=" + cpuChannel(i, 0, kind, true) + " cpuMip1=" + cpuChannel(i, 1, kind, true)
                            + " cpuMip2=" + cpuChannel(i, 2, kind, true));
                    }
                    channels++;
                }
                if (kind == 0) { maxAlbedoError = Math.max(maxAlbedoError, maximum); writePng(cached, label + "-lod" + lod); }
                if (kind == 1) maxMomentError = Math.max(maxMomentError, maximum);
                System.out.println("SURFACE_COMPONENT_PASS stage=" + label + " lod=" + lod + " kind=" + kind + " maximum=" + maximum);
            }
            GLFence fence = renderer.createFence();
            if (!pool.submit(lease, new HeightfieldSurfaceResidency.Completion() {
                @Override public boolean isComplete() { return renderer.isFenceSignaled(fence); }
                @Override public void close() { renderer.deleteFence(fence); }
            })) throw new IllegalStateException("Could not submit surface lease");
        } finally {
            renderer.setFrameBuffer(viewPort.getOutputFrameBuffer());
            renderManager.setCamera(cam, false);
        }
    }

    private void writePng(float[] rgba, String label) throws Exception {
        Files.createDirectories(output);
        BufferedImage image = new BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 48; y++) for (int x = 0; x < 64; x++) {
            int i = ((47 - y) * 64 + x) * 4;
            image.setRGB(x, y, display(rgba[i]) << 16 | display(rgba[i + 1]) << 8 | display(rgba[i + 2]));
        }
        ImageIO.write(image, "png", output.resolve(label + ".png").toFile());
    }
    private int display(float value) {
        value = Math.max(0, Math.min(1, value));
        float encoded = value <= .0031308f ? value * 12.92f : 1.055f * (float) Math.pow(value, 1 / 2.4) - .055f;
        return Math.max(0, Math.min(255, Math.round(encoded * 255)));
    }

    private final class Capture implements SceneProcessor {
        private boolean initialized;
        @Override public void initialize(RenderManager rm, ViewPort vp) { initialized = true; }
        @Override public boolean isInitialized() { return initialized; }
        @Override public void reshape(ViewPort vp, int w, int h) { }
        @Override public void preFrame(float tpf) { }
        @Override public void postQueue(RenderQueue queue) { }
        @Override public void setProfiler(AppProfiler profiler) { }
        @Override public void postFrame(FrameBuffer out) {
            if (failure != null || (frame != 4 && frame != 10)) return;
            try {
                verify(frame == 4 ? "coarse-root" : "fine-neighbors");
                if (frame == 10) {
                    System.out.println("SURFACE_REFERENCE_PASS channels=" + channels + " uploads=" + uploads
                            + " albedoError=" + maxAlbedoError + " detailWeightError=" + maxDetailWeightError
                            + " momentError=" + maxMomentError
                            + " peakEncodingScratch=" + scratchPeak + " physicalAtlasBytes=" + (SLOTS * MIPS * SIDE * SIDE * 25));
                    finished.countDown();
                }
            } catch (Throwable error) { fail(error); }
        }
        @Override public void cleanup() {
            renderer.deleteFrameBuffer(probeTarget);
            for (TextureArray texture : Arrays.asList(albedoPages, normalPages, semanticPages)) release(texture.getImage());
            pool.close();
            release(pageTable.getImage());
            source.close(renderer);
            BufferUtils.destroyDirectBuffer(pixels);
            initialized = false;
        }
        private void release(Image image) {
            renderer.deleteImage(image);
            for (ByteBuffer data : image.getData()) BufferUtils.destroyDirectBuffer(data);
        }
    }
}
