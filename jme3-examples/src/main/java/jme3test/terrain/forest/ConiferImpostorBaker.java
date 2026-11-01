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

import com.jme3.asset.AssetManager;
import com.jme3.material.MatParamTexture;
import com.jme3.material.Material;
import com.jme3.math.FastMath;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;
import com.jme3.renderer.Caps;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.Renderer;
import com.jme3.renderer.ViewPort;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.texture.FrameBuffer;
import com.jme3.texture.Image;
import com.jme3.texture.Texture;
import com.jme3.texture.TextureArray;
import com.jme3.texture.image.ColorSpace;
import com.jme3.util.BufferUtils;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Render-thread incremental baker for the original conifer's view frames.
 * Each update renders and reads one frame in two material passes. This baker
 * owns the arrays and their CPU image data; they remain valid only until
 * {@link #close()}. Close it after runtime materials have stopped using the
 * arrays and queued draws have drained. The source asset, mesh, and frond
 * texture remain owned by their caller/asset manager.
 */
public final class ConiferImpostorBaker implements AutoCloseable {
    private static final int SCALE = 2;
    private static final int BAKE_WIDTH = ConiferImpostorFrames.WIDTH * SCALE;
    private static final int BAKE_HEIGHT = ConiferImpostorFrames.HEIGHT * SCALE;
    private static final float CAMERA_DISTANCE = 36f;
    private static final float CAMERA_FAR = CAMERA_DISTANCE * 2f;

    private final RenderManager manager;
    private final Renderer renderer;
    private final Thread owner;
    private final Camera fallbackCamera;
    private final Camera camera;
    private final ViewPort viewPort;
    private final FrameBuffer colorTarget;
    private final FrameBuffer normalDepthTarget;
    private ByteBuffer colorReadback;
    private ByteBuffer normalDepthReadback;
    private final Geometry geometry;
    private final Material colorMaterial;
    private final Material normalDepthMaterial;
    private final ArrayList<Image> colorLayers = new ArrayList<>(ConiferImpostorFrames.COUNT);
    private final ArrayList<Image> normalDepthLayers = new ArrayList<>(ConiferImpostorFrames.COUNT);
    private final ArrayList<ByteBuffer> ownedBuffers = new ArrayList<>();
    private TextureArray colorFrames;
    private TextureArray normalDepthFrames;
    private int nextFrame;
    private long logicalBytes;
    private double maxMipCoverageError;
    private final double[] mipCoverageErrors = new double[mipSizes(ConiferImpostorFrames.WIDTH,
            ConiferImpostorFrames.HEIGHT, 4).length];
    private boolean closed, captureReleased;

    public ConiferImpostorBaker(RenderManager manager, AssetManager assets,
            OriginalConifer.Asset source, Material sourceTemplate) {
        if (manager == null || assets == null || source == null || sourceTemplate == null) {
            throw new IllegalArgumentException("renderer, assets, source tree, and material are required");
        }
        this.manager = manager;
        this.renderer = manager.getRenderer();
        this.owner = Thread.currentThread();
        Camera currentCamera = manager.getCurrentCamera();
        fallbackCamera = currentCamera != null ? currentCamera
                : (manager.getMainViews().isEmpty() ? null : manager.getMainViews().get(0).getCamera());
        if (fallbackCamera == null) {
            throw new IllegalStateException("a main viewport camera is required to restore render-manager state");
        }
        if (!renderer.getCaps().contains(Caps.FrameBuffer)
                || !renderer.getCaps().contains(Caps.TextureArray)
                || !renderer.getCaps().contains(Caps.HalfFloatColorBufferRGBA)) {
            throw new UnsupportedOperationException("Conifer impostor bake requires FBO, texture-array, and RGBA16F render-target support");
        }
        if (!renderer.isLinearizeSrgbImages()) {
            throw new IllegalStateException("conifer bake requires gamma-correct sRGB texture sampling");
        }
        MatParamTexture frond = sourceTemplate.getTextureParam("FrondTexture");
        if (frond == null || frond.getTextureValue() == null) {
            throw new IllegalArgumentException("source material must bind FrondTexture");
        }

        colorMaterial = bakeMaterial(assets, frond.getTextureValue(), sourceTemplate, 1);
        normalDepthMaterial = bakeMaterial(assets, frond.getTextureValue(), sourceTemplate, 2);
        camera = new Camera(BAKE_WIDTH, BAKE_HEIGHT);
        camera.setParallelProjection(true);
        camera.setFrustum(.1f, CAMERA_FAR, -1, 1, 1, -1);
        viewPort = new ViewPort("conifer-impostor-bake", camera);
        viewPort.setClearFlags(true, true, true);
        viewPort.setBackgroundColor(ColorRGBA.BlackNoAlpha);
        viewPort.setEnabled(false);
        colorTarget = makeTarget(Image.Format.RGBA8);
        normalDepthTarget = makeTarget(Image.Format.RGBA16F);
        colorReadback = own(BufferUtils.createByteBuffer(BAKE_WIDTH * BAKE_HEIGHT * 4));
        try {
            normalDepthReadback = own(BufferUtils.createByteBuffer(BAKE_WIDTH * BAKE_HEIGHT * 16)
                    .order(ByteOrder.nativeOrder()));
        } catch (RuntimeException | Error failure) {
            BufferUtils.destroyDirectBuffer(colorReadback);
            ownedBuffers.clear();
            throw failure;
        }
        geometry = new Geometry("conifer-impostor-source", source.getNearMesh());
        geometry.setQueueBucket(RenderQueue.Bucket.Opaque);
        Node scene = new Node("conifer-impostor-bake-scene");
        scene.attachChild(geometry);
        scene.updateGeometricState();
        viewPort.attachScene(scene);

        geometry.setMaterial(colorMaterial);
    }

    /** Render and store one complete frame; returns true when all 65 are ready. */
    public boolean update() {
        checkOwner();
        if (closed) throw new IllegalStateException("baker is closed");
        if (isReady()) return true;

        try {
            configureCamera(nextFrame);
            geometry.setMaterial(colorMaterial);
            renderAndRead(colorTarget, colorReadback, Image.Format.RGBA8);
            geometry.setMaterial(normalDepthMaterial);
            // Read the half-float attachment as RGBA/FLOAT for GLES3 portability.
            renderAndRead(normalDepthTarget, normalDepthReadback, Image.Format.RGBA32F);

            Image colorLayer = makeColorLayer(colorReadback);
            Image normalDepthLayer = makeNormalDepthLayer(normalDepthReadback);
            maxMipCoverageError = Math.max(maxMipCoverageError,
                    buildSemanticMips(colorLayer.getData(0), normalDepthLayer.getData(0),
                            ConiferImpostorFrames.WIDTH, ConiferImpostorFrames.HEIGHT,
                            colorLayer.getMipMapSizes(), mipCoverageErrors));
            colorLayers.add(colorLayer);
            normalDepthLayers.add(normalDepthLayer);
            logicalBytes += sum(colorLayer.getMipMapSizes()) * 3L;
            nextFrame++;
            if (isReady()) finishArrays();
            return isReady();
        } catch (RuntimeException | Error failure) {
            try { close(); } catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    public boolean isReady() { return nextFrame == ConiferImpostorFrames.COUNT; }

    /** Return baker-owned sRGB premultiplied-color and coverage frames. */
    public TextureArray getColorFrames() {
        checkOwner();
        if (!isReady() || closed) throw new IllegalStateException("all frames must be baked and baker open");
        return colorFrames;
    }

    /** Return baker-owned linear premultiplied object-normal and front-depth frames. */
    public TextureArray getNormalDepthFrames() {
        checkOwner();
        if (!isReady() || closed) throw new IllegalStateException("all frames must be baked and baker open");
        return normalDepthFrames;
    }

    /** Exact CPU mip payload bytes, including both semantic arrays. */
    public long getLogicalBytes() { checkOwner(); return logicalBytes; }

    /** Largest discrete alpha-test coverage error across generated mip levels. */
    public double getMaxMipCoverageError() { checkOwner(); return maxMipCoverageError; }

    /** Worst discrete covered-area mismatch among levels at least this many texels tall. */
    public double getMaxMipCoverageError(int minimumHeight) {
        checkOwner();
        if (minimumHeight < 1) throw new IllegalArgumentException("minimumHeight must be positive");
        double error = 0;
        for (int level = 0, height = ConiferImpostorFrames.HEIGHT; level < mipCoverageErrors.length;
                level++, height = Math.max(1, height / 2)) {
            if (height >= minimumHeight) error = Math.max(error, mipCoverageErrors[level]);
        }
        return error;
    }

    @Override
    public void close() {
        checkOwner();
        if (closed) return;
        closed = true;
        viewPort.setEnabled(false);
        viewPort.clearScenes();
        releaseCaptureResources();
        if (colorFrames != null) renderer.deleteImage(colorFrames.getImage());
        if (normalDepthFrames != null) renderer.deleteImage(normalDepthFrames.getImage());
        for (ByteBuffer buffer : ownedBuffers) BufferUtils.destroyDirectBuffer(buffer);
        ownedBuffers.clear();
        colorLayers.clear();
        normalDepthLayers.clear();
        colorFrames = null;
        normalDepthFrames = null;
    }

    private void renderAndRead(FrameBuffer passTarget, ByteBuffer destination, Image.Format format) {
        FrameBuffer oldTarget = renderer.getCurrentFrameBuffer();
        Camera oldCamera = manager.getCurrentCamera();
        if (oldCamera == null) oldCamera = fallbackCamera;
        Material oldMaterial = manager.getForcedMaterial();
        String oldTechnique = manager.getForcedTechnique();
        com.jme3.material.RenderState oldState = manager.getForcedRenderState();
        List<com.jme3.material.MatParamOverride> oldOverrides = new ArrayList<>(manager.getForcedMatParams());
        try {
            manager.setForcedMaterial(null);
            manager.setForcedTechnique(null);
            manager.setForcedRenderState(null);
            manager.getForcedMatParams().clear();
            destination.clear();
            viewPort.setOutputFrameBuffer(passTarget);
            viewPort.setEnabled(true);
            manager.renderViewPort(viewPort, 0f);
            renderer.readFrameBufferWithFormat(passTarget, destination, format);
        } finally {
            viewPort.setEnabled(false);
            manager.setForcedMaterial(oldMaterial);
            manager.setForcedTechnique(oldTechnique);
            manager.setForcedRenderState(oldState);
            manager.getForcedMatParams().clear();
            manager.getForcedMatParams().addAll(oldOverrides);
            renderer.setFrameBuffer(oldTarget);
            if (oldCamera != null) {
                manager.setCamera(oldCamera, false,
                        oldTarget == null ? oldCamera.getWidth() : oldTarget.getWidth(),
                        oldTarget == null ? oldCamera.getHeight() : oldTarget.getHeight());
            }
        }
    }

    private FrameBuffer makeTarget(Image.Format colorFormat) {
        FrameBuffer result = new FrameBuffer(BAKE_WIDTH, BAKE_HEIGHT, 1);
        result.addColorTarget(FrameBuffer.FrameBufferTarget.newTarget(colorFormat));
        result.setDepthTarget(FrameBuffer.FrameBufferTarget.newTarget(Image.Format.Depth24));
        result.setSrgb(false);
        return result;
    }

    private void configureCamera(int frame) {
        Vector3f direction = ConiferImpostorFrames.direction(frame, new Vector3f());
        Vector3f right = ConiferImpostorFrames.right(frame, new Vector3f());
        Vector3f up = ConiferImpostorFrames.up(frame, new Vector3f());
        camera.setLocation(direction.mult(CAMERA_DISTANCE).addLocal(0, ConiferImpostorFrames.CENTER_Y, 0));
        camera.setAxes(right.negate(), up, direction.negate());
        normalDepthMaterial.setVector3("ConiferBakeToObserver", direction);
        camera.setFrustum(.1f, CAMERA_FAR,
                -ConiferImpostorFrames.halfWidth(frame), ConiferImpostorFrames.halfWidth(frame),
                ConiferImpostorFrames.halfHeight(frame), -ConiferImpostorFrames.halfHeight(frame));
    }

    private Image makeColorLayer(ByteBuffer source) {
        int width = ConiferImpostorFrames.WIDTH, height = ConiferImpostorFrames.HEIGHT;
        int[] mipSizes = mipSizes(width, height, 4);
        ByteBuffer chain = own(ByteBuffer.allocateDirect(sum(mipSizes)));
        int baseBytes = width * height * 4;
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            double r = 0, g = 0, b = 0, a = 0;
            for (int oy = 0; oy < 2; oy++) for (int ox = 0; ox < 2; ox++) {
                int p = ((y * 2 + oy) * BAKE_WIDTH + x * 2 + ox) * 4;
                r += srgbToLinear((source.get(p) & 255) / 255.0);
                g += srgbToLinear((source.get(p + 1) & 255) / 255.0);
                b += srgbToLinear((source.get(p + 2) & 255) / 255.0);
                a += (source.get(p + 3) & 255) / 255.0;
            }
            int p = (y * width + x) * 4;
            int alpha = (int) Math.round(a * 63.75);
            double ratio = a > 0 ? alpha / (a * 63.75) : 0;
            chain.put(p, (byte) Math.round(linearToSrgb(r * .25 * ratio) * 255));
            chain.put(p + 1, (byte) Math.round(linearToSrgb(g * .25 * ratio) * 255));
            chain.put(p + 2, (byte) Math.round(linearToSrgb(b * .25 * ratio) * 255));
            chain.put(p + 3, (byte) alpha);
        }
        chain.position(baseBytes);
        chain.position(0); chain.limit(sum(mipSizes));
        return new Image(Image.Format.RGBA8, width, height, chain, mipSizes, ColorSpace.sRGB);
    }

    private Image makeNormalDepthLayer(ByteBuffer source) {
        int width = ConiferImpostorFrames.WIDTH, height = ConiferImpostorFrames.HEIGHT;
        int[] mipSizes = mipSizes(width, height, 8);
        ByteBuffer chain = own(ByteBuffer.allocateDirect(sum(mipSizes)).order(ByteOrder.nativeOrder()));
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            float r = 0, g = 0, b = 0, a = 0, coverage = 0;
            for (int oy = 0; oy < 2; oy++) for (int ox = 0; ox < 2; ox++) {
                int fp = ((y * 2 + oy) * BAKE_WIDTH + x * 2 + ox) * 16;
                coverage += (colorReadback.get(fp / 4 + 3) & 255) / 255f;
                r += source.getFloat(fp);
                g += source.getFloat(fp + 4);
                b += source.getFloat(fp + 8);
                a += source.getFloat(fp + 12);
            }
            int p = (y * width + x) * 8;
            float ratio = coverage > 0 ? Math.round(coverage * 63.75f) / (coverage * 63.75f) : 0;
            r *= ratio; g *= ratio; b *= ratio; a *= ratio;
            chain.putShort(p, FastMath.convertFloatToHalf(r * .25f));
            chain.putShort(p + 2, FastMath.convertFloatToHalf(g * .25f));
            chain.putShort(p + 4, FastMath.convertFloatToHalf(b * .25f));
            chain.putShort(p + 6, FastMath.convertFloatToHalf(a * .25f));
        }
        chain.position(0); chain.limit(sum(mipSizes));
        return new Image(Image.Format.RGBA16F, width, height, chain, mipSizes, ColorSpace.Linear);
    }

    static double buildSemanticMips(ByteBuffer color, ByteBuffer normalDepth,
            int width, int height, int[] sizes) {
        return buildSemanticMips(color, normalDepth, width, height, sizes, null);
    }

    private static double buildSemanticMips(ByteBuffer color, ByteBuffer normalDepth,
            int width, int height, int[] sizes, double[] errors) {
        double baseCoverage = coverage(color, 0, width * height);
        double maxError = 0;
        int previousOffset = 0, offset = sizes[0], previousWidth = width, previousHeight = height;
        for (int level = 1; level < sizes.length; level++) {
            int w = Math.max(1, previousWidth / 2), h = Math.max(1, previousHeight / 2);
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                double r = 0, g = 0, b = 0, a = 0;
                double nr = 0, ng = 0, nb = 0, nd = 0;
                for (int oy = 0; oy < 2; oy++) for (int ox = 0; ox < 2; ox++) {
                    int sourcePixel = Math.min(previousHeight - 1, y * 2 + oy) * previousWidth
                            + Math.min(previousWidth - 1, x * 2 + ox);
                    int cp = previousOffset + sourcePixel * 4;
                    r += srgbToLinear((color.get(cp) & 255) / 255.0);
                    g += srgbToLinear((color.get(cp + 1) & 255) / 255.0);
                    b += srgbToLinear((color.get(cp + 2) & 255) / 255.0);
                    a += (color.get(cp + 3) & 255) / 255.0;
                    int np = previousOffset * 2 + sourcePixel * 8;
                    nr += FastMath.convertHalfToFloat(normalDepth.getShort(np));
                    ng += FastMath.convertHalfToFloat(normalDepth.getShort(np + 2));
                    nb += FastMath.convertHalfToFloat(normalDepth.getShort(np + 4));
                    nd += FastMath.convertHalfToFloat(normalDepth.getShort(np + 6));
                }
                int cp = offset + (y * w + x) * 4;
                color.put(cp, (byte) Math.round(linearToSrgb(r * .25) * 255));
                color.put(cp + 1, (byte) Math.round(linearToSrgb(g * .25) * 255));
                color.put(cp + 2, (byte) Math.round(linearToSrgb(b * .25) * 255));
                color.put(cp + 3, (byte) Math.round(a * 63.75));
                int np = offset * 2 + (y * w + x) * 8;
                normalDepth.putShort(np, FastMath.convertFloatToHalf((float) (nr * .25)));
                normalDepth.putShort(np + 2, FastMath.convertFloatToHalf((float) (ng * .25)));
                normalDepth.putShort(np + 4, FastMath.convertFloatToHalf((float) (nb * .25)));
                normalDepth.putShort(np + 6, FastMath.convertFloatToHalf((float) (nd * .25)));
            }
            double error = preserveCoverage(color, normalDepth, offset, w, h, baseCoverage);
            maxError = Math.max(maxError, error);
            if (errors != null) errors[level] = Math.max(errors[level], error);
            previousOffset = offset; offset += sizes[level]; previousWidth = w; previousHeight = h;
        }
        return maxError;
    }

    private static double coverage(ByteBuffer color, int offset, int count) {
        int covered = 0;
        for (int p = 0; p < count; p++) {
            if ((color.get(offset + p * 4 + 3) & 255) / 255.0 >= OriginalConiferMaterial.FROND_ALPHA_THRESHOLD) covered++;
        }
        return (double) covered / count;
    }

    private static double preserveCoverage(ByteBuffer color, ByteBuffer normalDepth,
            int offset, int width, int height, double targetCoverage) {
        int count = width * height;
        double low = 0, high = 1024, bestScale = 1;
        double bestError = Math.abs(coverageAtScale(color, offset, count, bestScale) - targetCoverage);
        for (int i = 0; i < 32; i++) {
            double scale = (low + high) * .5;
            double current = coverageAtScale(color, offset, count, scale);
            double error = Math.abs(current - targetCoverage);
            if (error < bestError) { bestError = error; bestScale = scale; }
            if (current > targetCoverage) high = scale; else low = scale;
        }
        for (int p = 0; p < count; p++) {
            int cp = offset + p * 4, np = offset * 2 + p * 8;
            double oldAlpha = (color.get(cp + 3) & 255) / 255.0;
            double newAlpha = Math.round(Math.min(1, oldAlpha * bestScale) * 255) / 255.0;
            double ratio = oldAlpha > 0 ? newAlpha / oldAlpha : 0;
            for (int c = 0; c < 3; c++) {
                double premultiplied = srgbToLinear((color.get(cp + c) & 255) / 255.0) * ratio;
                color.put(cp + c, (byte) Math.round(linearToSrgb(premultiplied) * 255));
                int channel = np + c * 2;
                normalDepth.putShort(channel, FastMath.convertFloatToHalf(
                        FastMath.convertHalfToFloat(normalDepth.getShort(channel)) * (float) ratio));
            }
            int depthChannel = np + 6;
            normalDepth.putShort(depthChannel, FastMath.convertFloatToHalf(
                    FastMath.convertHalfToFloat(normalDepth.getShort(depthChannel)) * (float) ratio));
            color.put(cp + 3, (byte) Math.round(newAlpha * 255));
        }
        return bestError;
    }

    private static double coverageAtScale(ByteBuffer color, int offset, int count, double scale) {
        int covered = 0;
        for (int p = 0; p < count; p++) {
            double alpha = (color.get(offset + p * 4 + 3) & 255) / 255.0;
            int quantized = (int) Math.round(Math.min(1, alpha * scale) * 255);
            if (quantized / 255.0 >= OriginalConiferMaterial.FROND_ALPHA_THRESHOLD) covered++;
        }
        return (double) covered / count;
    }

    private void releaseCaptureResources() {
        if (captureReleased) return;
        captureReleased = true;
        renderer.deleteFrameBuffer(colorTarget);
        renderer.deleteFrameBuffer(normalDepthTarget);
        if (colorReadback != null) {
            ownedBuffers.remove(colorReadback);
            BufferUtils.destroyDirectBuffer(colorReadback);
            colorReadback = null;
        }
        if (normalDepthReadback != null) {
            ownedBuffers.remove(normalDepthReadback);
            BufferUtils.destroyDirectBuffer(normalDepthReadback);
            normalDepthReadback = null;
        }
    }

    private void finishArrays() {
        if (logicalBytes != 34_078_980L) {
            throw new IllegalStateException("unexpected 65-frame full-mip payload: " + logicalBytes);
        }
        colorFrames = new TextureArray(colorLayers);
        normalDepthFrames = new TextureArray(normalDepthLayers);
        configure(colorFrames);
        configure(normalDepthFrames);
        // Blocking readback completed every capture; no bake target is queued.
        releaseCaptureResources();
    }

    private static void configure(TextureArray texture) {
        texture.setWrap(Texture.WrapAxis.S, Texture.WrapMode.EdgeClamp);
        texture.setWrap(Texture.WrapAxis.T, Texture.WrapMode.EdgeClamp);
        texture.setMagFilter(Texture.MagFilter.Bilinear);
        texture.setMinFilter(Texture.MinFilter.Trilinear);
    }

    private Material bakeMaterial(AssetManager assets, Texture frond, Material template, int mode) {
        Material result = new Material(assets, OriginalConiferMaterial.DEFINITION);
        result.setTexture("FrondTexture", frond);
        result.setFloat("AlphaDiscardThreshold", ((Number) template.getParam("AlphaDiscardThreshold").getValue()).floatValue());
        result.setInt("ConiferBakeMode", mode);
        result.setVector3("ConiferBakeToObserver", Vector3f.UNIT_Z);
        return result;
    }

    private ByteBuffer own(ByteBuffer data) { ownedBuffers.add(data); return data; }
    private void checkOwner() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("baker is render-thread confined");
    }

    static int[] mipSizes(int width, int height, int bytesPerPixel) {
        ArrayList<Integer> result = new ArrayList<>();
        int w = width, h = height;
        while (true) {
            result.add(w * h * bytesPerPixel);
            if (w == 1 && h == 1) break;
            w = Math.max(1, w / 2); h = Math.max(1, h / 2);
        }
        int[] sizes = new int[result.size()];
        for (int i = 0; i < sizes.length; i++) sizes[i] = result.get(i);
        return sizes;
    }
    private static int sum(int[] values) { int result = 0; for (int value : values) result += value; return result; }
    private static double srgbToLinear(double x) { return x <= .04045 ? x / 12.92 : Math.pow((x + .055) / 1.055, 2.4); }
    private static double linearToSrgb(double x) { x = Math.max(0, Math.min(1, x)); return x <= .0031308 ? 12.92 * x : 1.055 * Math.pow(x, 1 / 2.4) - .055; }

}
