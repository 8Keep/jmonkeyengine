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
import com.jme3.bounding.BoundingSphere;
import com.jme3.material.MatParamOverride;
import com.jme3.material.Material;
import com.jme3.material.RenderState;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.post.SceneProcessor;
import com.jme3.profile.AppProfiler;
import com.jme3.renderer.Camera;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.Renderer;
import com.jme3.renderer.ViewPort;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
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
 * Horizon-focused visible sky for one viewport and one live GPU sky owner.
 * Shared transport tables/sun stay immutable; only observer-dependent radiance
 * is rebuilt. Camera rotation and projection do not change this spherical map.
 * A full-screen quad reconstructs exact pixel rays; the borrowed sky geometry's
 * original cube material and mesh are restored on cleanup.
 * Terrain diffuse/direct lighting retains its explicit fixed scene altitude.
 *
 * This examples-stage path assumes ordinary projection between viewport passes
 * and no active custom scissor. Virtual ground remains a uniform sphere, not
 * distant terrain relief. The LUT excludes the analytic solar disk.
 */
final class GpuAtmosphereSkyView implements SceneProcessor {
    static final int WIDTH = 256, HEIGHT = 128;
    private final AssetManager assets;
    private final GpuAtmosphereSky sky;
    private final Camera passCamera = new Camera(1, 1);
    private final Vector3f cachedOrigin = new Vector3f();
    private final ColorRGBA solarRadiance = new ColorRGBA();
    private RenderManager manager;
    private ViewPort viewport;
    private Thread owner;
    private Texture2D lookup;
    private FrameBuffer target;
    private Geometry quad, displayGeometry;
    private Material integration, display, originalDisplay;
    private Mesh originalMesh;
    private SkyViewMapping mapping;
    private boolean initialized, invalid = true;
    private long builds, buildNanos, maximumBuildNanos;

    GpuAtmosphereSkyView(AssetManager assets, GpuAtmosphereSky sky) {
        if (assets == null || sky == null) throw new IllegalArgumentException("assets and live sky are required");
        this.assets = assets;
        this.sky = sky;
    }

    @Override public void initialize(RenderManager renderManager, ViewPort viewPort) {
        if (owner != null && owner != Thread.currentThread()) throw new IllegalStateException("wrong sky-view owner");
        if (initialized) cleanup();
        owner = Thread.currentThread();
        manager = renderManager;
        viewport = viewPort;
        sky.requireLiveRenderer(manager.getRenderer());
        try {
            integration = sky.createSkyViewMaterial(assets);
            lookup = new Texture2D(WIDTH, HEIGHT, Image.Format.RGBA16F);
            lookup.getImage().setColorSpace(ColorSpace.Linear);
            lookup.setMinFilter(Texture.MinFilter.BilinearNoMipMaps);
            lookup.setMagFilter(Texture.MagFilter.Bilinear);
            lookup.setWrap(Texture.WrapAxis.S, Texture.WrapMode.Repeat);
            lookup.setWrap(Texture.WrapAxis.T, Texture.WrapMode.EdgeClamp);
            target = new FrameBuffer(WIDTH, HEIGHT, 1);
            target.addColorTarget(FrameBuffer.FrameBufferTarget.newTarget(lookup));
            quad = new Geometry("sky-view-pass", new Quad(1, 1));
            // Preserve SkyFactory's infinite bound after replacing its mesh.
            // Otherwise an ancestor can cull the sky before its Never hint is
            // reached, based on the screen quad's ordinary world-space bound.
            quad.setModelBound(new BoundingSphere(Float.POSITIVE_INFINITY, Vector3f.ZERO));
            display = new Material(assets, "MatDefs/AtmosphereSkyViewDisplay.j3md");
            display.setTexture("SkyViewMap", lookup);
            display.setVector3("SunDirection", sky.getSunDirection(new Vector3f()));
            displayGeometry = (Geometry) sky.getSpatial();
            originalDisplay = displayGeometry.getMaterial();
            originalMesh = displayGeometry.getMesh();
            displayGeometry.setMesh(quad.getMesh());
            displayGeometry.setMaterial(display);
            invalid = true;
            initialized = true;
        } catch (RuntimeException | Error failure) {
            release();
            throw failure;
        }
    }

    @Override public boolean isInitialized() { return initialized; }
    @Override public void preFrame(float tpf) { update(viewport.getCamera()); }
    @Override public void reshape(ViewPort viewPort, int width, int height) { /* fixed spherical resolution */ }
    @Override public void postQueue(RenderQueue queue) { }
    @Override public void postFrame(FrameBuffer output) { }
    @Override public void setProfiler(AppProfiler profiler) { }

    void invalidate() { checkOwner(); invalid = true; }

    void update(Camera camera) {
        checkOwner();
        if (!invalid && cachedOrigin.equals(camera.getLocation())) return;
        long started = System.nanoTime();
        Vector3f sun = sky.getSunDirection(new Vector3f());
        SkyViewMapping next = new SkyViewMapping(camera.getLocation(), sun);
        Vector3f up = next.getUp(new Vector3f());
        Vector3f forward = next.getForward(new Vector3f());
        Vector3f side = next.getSide(new Vector3f());
        integration.setVector3("ViewUp", up);
        integration.setVector3("ViewForward", forward);
        integration.setVector3("ViewSide", side);
        integration.setFloat("ObserverAltitudeKm", (float) (next.getAltitudeMeters() / 1000));
        integration.setFloat("HorizonElevation", (float) next.getHorizonElevation());
        display.setVector3("ViewUp", up);
        display.setVector3("ViewForward", forward);
        display.setVector3("ViewSide", side);
        display.setFloat("HorizonElevation", (float) next.getHorizonElevation());
        sky.sampleTransmission(next.getRadiusMeters(), up.dot(sun), solarRadiance);
        float solar = (float) ClearSkyAtmosphere.modelParameters().solarIrradiance();
        float solidAngle = (float) (2 * Math.PI * (1 - Math.cos(.004675)));
        solarRadiance.multLocal(solar / solidAngle);
        solarRadiance.a = 1;
        display.setColor("SolarRadiance", solarRadiance);

        Renderer renderer = manager.getRenderer();
        FrameBuffer savedTarget = renderer.getCurrentFrameBuffer();
        Camera savedCamera = manager.getCurrentCamera() == null ? camera : manager.getCurrentCamera();
        Material savedMaterial = manager.getForcedMaterial();
        String savedTechnique = manager.getForcedTechnique();
        RenderState savedState = manager.getForcedRenderState();
        List<MatParamOverride> savedOverrides = new ArrayList<>(manager.getForcedMatParams());
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
            manager.setForcedMaterial(savedMaterial);
            manager.setForcedTechnique(savedTechnique);
            manager.setForcedRenderState(savedState);
            manager.getForcedMatParams().clear();
            manager.getForcedMatParams().addAll(savedOverrides);
            renderer.setFrameBuffer(savedTarget);
            manager.setCamera(savedCamera, false,
                    savedTarget == null ? savedCamera.getWidth() : savedTarget.getWidth(),
                    savedTarget == null ? savedCamera.getHeight() : savedTarget.getHeight());
        }
        mapping = next;
        cachedOrigin.set(camera.getLocation());
        invalid = false;
        builds++;
        long elapsed = System.nanoTime() - started;
        buildNanos += elapsed;
        maximumBuildNanos = Math.max(maximumBuildNanos, elapsed);
    }

    SkyViewMapping getMapping() {
        checkOwner();
        if (mapping == null) throw new IllegalStateException("sky view has not been built");
        return mapping;
    }
    ColorRGBA getSolarRadiance(ColorRGBA store) { checkOwner(); return store.set(solarRadiance); }
    long getEncodedBytes() { return (long) WIDTH * HEIGHT * 8; }
    long getBuilds() { return builds; }
    long getBuildNanos() { return buildNanos; }
    long getMaximumBuildNanos() { return maximumBuildNanos; }

    /** Diagnostic-only readback. Caller releases the returned direct buffer. */
    ByteBuffer readMap() {
        checkOwner();
        ByteBuffer values = BufferUtils.createByteBuffer(WIDTH * HEIGHT * 16).order(ByteOrder.nativeOrder());
        Renderer renderer = manager.getRenderer();
        FrameBuffer saved = renderer.getCurrentFrameBuffer();
        try { renderer.readFrameBufferWithFormat(target, values, Image.Format.RGBA32F); }
        catch (RuntimeException | Error failure) { BufferUtils.destroyDirectBuffer(values); throw failure; }
        finally { renderer.setFrameBuffer(saved); }
        return values;
    }

    /** Rebuild after explicitly synchronized GPU-only image loss. */
    void recreate() {
        checkOwner();
        manager.getRenderer().deleteFrameBuffer(target);
        manager.getRenderer().deleteImage(lookup.getImage());
        invalid = true;
    }

    private void checkOwner() {
        if (Thread.currentThread() != owner || !initialized) throw new IllegalStateException("sky view requires its initialized render owner");
        sky.requireLiveRenderer(manager.getRenderer());
    }

    @Override public void cleanup() {
        if (owner != null && Thread.currentThread() != owner) throw new IllegalStateException("wrong sky-view owner");
        release();
    }

    private void release() {
        if (displayGeometry != null) {
            if (displayGeometry.getMaterial() == display) displayGeometry.setMaterial(originalDisplay);
            if (quad != null && displayGeometry.getMesh() == quad.getMesh()) displayGeometry.setMesh(originalMesh);
        }
        if (manager != null) {
            Renderer renderer = manager.getRenderer();
            if (target != null) renderer.deleteFrameBuffer(target);
            if (lookup != null) renderer.deleteImage(lookup.getImage());
            if (quad != null) for (VertexBuffer buffer : quad.getMesh().getBufferList()) {
                renderer.deleteBuffer(buffer);
                if (buffer.getData() != null) BufferUtils.destroyDirectBuffer(buffer.getData());
            }
        }
        target = null;
        lookup = null;
        quad = null;
        displayGeometry = null;
        originalDisplay = null;
        originalMesh = null;
        mapping = null;
        initialized = false;
        invalid = true;
    }
}
