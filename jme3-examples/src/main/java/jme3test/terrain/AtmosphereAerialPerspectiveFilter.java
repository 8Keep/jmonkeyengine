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

import com.jme3.asset.AssetManager;
import com.jme3.material.MatParamOverride;
import com.jme3.material.Material;
import com.jme3.material.RenderState;
import com.jme3.math.Matrix4f;
import com.jme3.math.Vector2f;
import com.jme3.math.Vector3f;
import com.jme3.math.Vector4f;
import com.jme3.post.Filter;
import com.jme3.renderer.Camera;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.Renderer;
import com.jme3.renderer.ViewPort;
import com.jme3.scene.Geometry;
import com.jme3.scene.VertexBuffer;
import com.jme3.scene.shape.Quad;
import com.jme3.texture.FrameBuffer;
import com.jme3.texture.Image;
import com.jme3.texture.Texture;
import com.jme3.texture.Texture2D;
import com.jme3.texture.image.ColorSpace;
import com.jme3.util.BufferUtils;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Camera-to-opaque-surface atmosphere, before tone mapping. A packed frustum
 * lookup amortizes finite transport across screen pixels. It retains RGB
 * transmittance rather than collapsing extinction to a scalar. The immutable
 * sky owns the shared model/sun/transport tables and must outlive this filter.
 *
 * This examples-stage implementation supports a full viewport, standard
 * perspective/orthographic projection and ray origins inside the atmosphere.
 * It has no terrain volumetric shadows or transparent-material integration.
 * Rebuild between viewport passes with no custom scissor or GUI projection.
 */
final class AtmosphereAerialPerspectiveFilter extends Filter {
    static final int TILE_SIZE = 32, SLICES = 32, WIDTH = 512, HEIGHT = 128;
    private final GpuAtmosphereSky sky;
    private final Camera cachedCamera = new Camera(1, 1);
    private final Matrix4f expectedProjection = new Matrix4f();
    private RenderManager manager;
    private ViewPort viewport;
    private Thread owner;
    private Texture2D atlas;
    private FrameBuffer target;
    private Material integration;
    private Geometry quad;
    private final Camera passCamera = new Camera(1, 1);
    private boolean invalid = true;
    private long builds, buildNanos, maximumBuildNanos;

    AtmosphereAerialPerspectiveFilter(GpuAtmosphereSky sky) {
        super("Atmosphere aerial perspective");
        if (sky == null) throw new IllegalArgumentException("live GPU sky is required");
        this.sky = sky;
    }

    @Override protected boolean isRequiresDepthTexture() { return true; }
    @Override protected Image.Format getDefaultPassTextureFormat() { return Image.Format.RGBA16F; }
    @Override protected Image.Format getDefaultPassDepthFormat() { return Image.Format.Depth24; }

    @Override protected void initFilter(AssetManager assets, RenderManager renderManager,
            ViewPort viewPort, int width, int height) {
        if (owner != null && Thread.currentThread() != owner) {
            throw new IllegalStateException("aerial filter belongs to its render owner");
        }
        release(renderManager.getRenderer());
        owner = Thread.currentThread();
        manager = renderManager;
        viewport = viewPort;
        try {
            sky.requireLiveRenderer(renderManager.getRenderer());
            integration = sky.createAerialMaterial(assets);
            atlas = new Texture2D(WIDTH, HEIGHT, Image.Format.RGBA16F);
            atlas.getImage().setColorSpace(ColorSpace.Linear);
            atlas.setMinFilter(Texture.MinFilter.BilinearNoMipMaps);
            atlas.setMagFilter(Texture.MagFilter.Bilinear);
            atlas.setWrap(Texture.WrapMode.EdgeClamp);
            target = new FrameBuffer(WIDTH, HEIGHT, 1);
            target.addColorTarget(FrameBuffer.FrameBufferTarget.newTarget(atlas));
            quad = new Geometry("aerial-transport-pass", new Quad(1, 1));
            material = new Material(assets, "MatDefs/AtmosphereAerialComposite.j3md");
            material.setTexture("AerialMap", atlas);
            invalid = true;
        } catch (RuntimeException | Error failure) {
            release(renderManager.getRenderer());
            throw failure;
        }
    }

    @Override protected Material getMaterial() { return material; }
    @Override protected void preFrame(float tpf) { updateAtlas(viewport.getCamera()); }

    void invalidate() { checkOwner(); invalid = true; }

    /** No readback or allocation of images on the normal frame path. */
    void updateAtlas(Camera camera) {
        checkOwner();
        if (!invalid && sameCamera(camera)) return;
        validateCamera(camera);
        long started = System.nanoTime();
        Vector3f right = camera.getLeft().negate();
        integration.setVector3("CameraOrigin", camera.getLocation());
        integration.setVector3("CameraForward", camera.getDirection());
        integration.setVector3("CameraRight", right);
        integration.setVector3("CameraUp", camera.getUp());
        integration.setVector4("Frustum", new Vector4f(camera.getFrustumLeft(), camera.getFrustumRight(),
                camera.getFrustumBottom(), camera.getFrustumTop()));
        integration.setFloat("FrustumNear", camera.getFrustumNear());
        integration.setBoolean("ParallelProjection", camera.isParallelProjection());
        integration.setFloat("MaxDepthMeters", camera.getFrustumFar());
        material.setVector2("FrustumNearFar", new Vector2f(camera.getFrustumNear(), camera.getFrustumFar()));
        material.setBoolean("ParallelProjection", camera.isParallelProjection());
        material.setFloat("MaxDepthMeters", camera.getFrustumFar());
        Renderer renderer = manager.getRenderer();
        FrameBuffer previousTarget = renderer.getCurrentFrameBuffer();
        Camera restoreCamera = manager.getCurrentCamera() == null ? camera : manager.getCurrentCamera();
        Material previousMaterial = manager.getForcedMaterial();
        String previousTechnique = manager.getForcedTechnique();
        RenderState previousState = manager.getForcedRenderState();
        List<MatParamOverride> previousOverrides = new ArrayList<>(manager.getForcedMatParams());
        try {
            manager.setForcedMaterial(null);
            manager.setForcedTechnique(null);
            manager.setForcedRenderState(null);
            manager.getForcedMatParams().clear();
            renderer.setFrameBuffer(target);
            manager.setCamera(passCamera, false, WIDTH, HEIGHT);
            quad.setMaterial(integration);
            manager.renderGeometry(quad);
        } finally {
            manager.setForcedMaterial(previousMaterial);
            manager.setForcedTechnique(previousTechnique);
            manager.setForcedRenderState(previousState);
            manager.getForcedMatParams().clear();
            manager.getForcedMatParams().addAll(previousOverrides);
            renderer.setFrameBuffer(previousTarget);
            manager.setCamera(restoreCamera, false,
                    previousTarget == null ? restoreCamera.getWidth() : previousTarget.getWidth(),
                    previousTarget == null ? restoreCamera.getHeight() : previousTarget.getHeight());
        }
        cachedCamera.copyFrom(camera);
        invalid = false;
        long elapsed = System.nanoTime() - started;
        builds++;
        buildNanos += elapsed;
        maximumBuildNanos = Math.max(maximumBuildNanos, elapsed);
    }

    private boolean sameCamera(Camera camera) {
        // VP alone is insufficient: an orthographic camera can move along its
        // rays while adjusting near/far to keep the same world clip planes.
        return cachedCamera.getWidth() == camera.getWidth()
                && cachedCamera.getHeight() == camera.getHeight()
                && cachedCamera.isParallelProjection() == camera.isParallelProjection()
                && cachedCamera.getLocation().equals(camera.getLocation())
                && cachedCamera.getRotation().equals(camera.getRotation())
                && cachedCamera.getFrustumNear() == camera.getFrustumNear()
                && cachedCamera.getFrustumFar() == camera.getFrustumFar()
                && cachedCamera.getFrustumLeft() == camera.getFrustumLeft()
                && cachedCamera.getFrustumRight() == camera.getFrustumRight()
                && cachedCamera.getFrustumBottom() == camera.getFrustumBottom()
                && cachedCamera.getFrustumTop() == camera.getFrustumTop()
                && cachedCamera.getViewProjectionMatrix().equals(camera.getViewProjectionMatrix());
    }

    private void validateCamera(Camera camera) {
        if (camera.getViewPortLeft() != 0 || camera.getViewPortRight() != 1
                || camera.getViewPortBottom() != 0 || camera.getViewPortTop() != 1
                || !(camera.getFrustumNear() > 0) || !(camera.getFrustumFar() > camera.getFrustumNear())
                || !Float.isFinite(camera.getFrustumFar())) {
            throw new UnsupportedOperationException("aerial control requires a finite full-viewport frustum");
        }
        expectedProjection.fromFrustum(camera.getFrustumNear(), camera.getFrustumFar(),
                camera.getFrustumLeft(), camera.getFrustumRight(), camera.getFrustumTop(),
                camera.getFrustumBottom(), camera.isParallelProjection());
        if (!expectedProjection.equals(camera.getProjectionMatrix())) {
            throw new UnsupportedOperationException("custom projection matrices require matching aerial ray reconstruction");
        }
        validateOrigin(camera.getLocation());
        if (camera.isParallelProjection()) {
            for (float x : new float[]{camera.getFrustumLeft(), camera.getFrustumRight()}) {
                for (float y : new float[]{camera.getFrustumBottom(), camera.getFrustumTop()}) {
                    validateOrigin(camera.getLocation().add(camera.getLeft().mult(-x)).addLocal(camera.getUp().mult(y)));
                }
            }
        }
    }

    private static void validateOrigin(Vector3f origin) {
        ClearSkyAtmosphere.ModelParameters model = ClearSkyAtmosphere.modelParameters();
        double y = origin.y + model.groundRadiusMeters();
        double radius = Math.sqrt((double) origin.x * origin.x + y * y + (double) origin.z * origin.z);
        if (!Double.isFinite(radius) || radius < model.groundRadiusMeters() || radius > model.topRadiusMeters()) {
            throw new UnsupportedOperationException("aerial ray origins must lie inside the atmosphere");
        }
    }

    /** Diagnostic-only full atlas readback; caller releases the direct buffer. */
    ByteBuffer readAtlas() {
        checkOwner();
        ByteBuffer pixels = BufferUtils.createByteBuffer(WIDTH * HEIGHT * 16).order(ByteOrder.nativeOrder());
        Renderer renderer = manager.getRenderer();
        FrameBuffer previous = renderer.getCurrentFrameBuffer();
        try { renderer.readFrameBufferWithFormat(target, pixels, Image.Format.RGBA32F); }
        catch (RuntimeException | Error failure) { BufferUtils.destroyDirectBuffer(pixels); throw failure; }
        finally { renderer.setFrameBuffer(previous); }
        return pixels;
    }

    /** Recompute GPU-only data after an explicitly synchronized storage loss. */
    void recreateAtlas() {
        checkOwner();
        Renderer renderer = manager.getRenderer();
        renderer.deleteFrameBuffer(target);
        renderer.deleteImage(atlas.getImage());
        invalid = true;
    }

    long getEncodedBytes() { return (long) WIDTH * HEIGHT * 8; }
    long getBuilds() { return builds; }
    long getBuildNanos() { return buildNanos; }
    long getMaximumBuildNanos() { return maximumBuildNanos; }

    private void checkOwner() {
        if (Thread.currentThread() != owner || target == null) {
            throw new IllegalStateException("aerial filter belongs to its initialized render owner");
        }
        sky.requireLiveRenderer(manager.getRenderer());
    }

    @Override protected void cleanUpFilter(Renderer renderer) {
        if (owner != null && Thread.currentThread() != owner) throw new IllegalStateException("wrong render owner");
        release(renderer);
    }

    private void release(Renderer renderer) {
        if (target != null) renderer.deleteFrameBuffer(target);
        if (atlas != null) renderer.deleteImage(atlas.getImage());
        if (quad != null) for (VertexBuffer buffer : quad.getMesh().getBufferList()) {
            renderer.deleteBuffer(buffer);
            if (buffer.getData() != null) BufferUtils.destroyDirectBuffer(buffer.getData());
        }
        target = null;
        atlas = null;
        quad = null;
        invalid = true;
    }
}
