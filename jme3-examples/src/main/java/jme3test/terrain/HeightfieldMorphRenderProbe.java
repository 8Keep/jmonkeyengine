/*
 * Copyright (c) 2026 jMonkeyEngine
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
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDER(S) AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
 * TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
 * PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER(S) OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS;
 * OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR
 * OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package jme3test.terrain;

import com.jme3.asset.AssetManager;
import com.jme3.material.Material;
import com.jme3.material.RenderState;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector2f;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;
import com.jme3.renderer.Caps;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.Renderer;
import com.jme3.renderer.ViewPort;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.scene.Spatial;
import com.jme3.scene.VertexBuffer;
import com.jme3.light.AmbientLight;
import com.jme3.light.DirectionalLight;
import com.jme3.shader.Shader;
import com.jme3.shader.VarType;
import com.jme3.terrain.heightfield.HeightfieldGpuTransitions;
import com.jme3.terrain.heightfield.HeightfieldGrid;
import com.jme3.terrain.heightfield.HeightfieldHierarchy;
import com.jme3.terrain.heightfield.HeightfieldLod.Patch;
import com.jme3.texture.FrameBuffer;
import com.jme3.texture.Image;
import com.jme3.texture.Texture;
import com.jme3.texture.Texture2D;
import com.jme3.texture.TextureArray;
import com.jme3.util.BufferUtils;
import com.jme3.scene.instancing.IndirectGeometry;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import javax.imageio.ImageIO;

/** Native image and caster-depth comparison for temporal heightfield morphs. */
public final class HeightfieldMorphRenderProbe implements AutoCloseable {
    private static final int WIDTH = 512;
    private static final int HEIGHT = 512;
    private static final float COLOR_TOLERANCE = 2e-4f;
    private static final float DEPTH_TOLERANCE = 2f / 16777215f;

    private final AssetManager assetManager;
    private final RenderManager renderManager;
    private final Renderer renderer;
    private final HeightfieldHierarchy hierarchy;
    private final HeightfieldGpuTransitions transitions;
    private final TextureArray heights;
    private final com.jme3.scene.Node scene = new com.jme3.scene.Node("heightfield-morph-render-probe");
    private final Mesh grid;
    private final FrameBuffer gpuTarget;
    private final FrameBuffer referenceTarget;
    private final FrameBuffer gpuShadowTarget;
    private final FrameBuffer referenceShadowTarget;
    private final FrameBuffer decodedDepthTarget;
    private final Mesh decodeQuad = new com.jme3.scene.shape.Quad(2f, 2f);
    private final Shader depthDecoder;
    private final ViewPort viewPort;
    private final AmbientLight ambient = new AmbientLight();
    private final DirectionalLight sun = new DirectionalLight();
    private boolean closed;

    public HeightfieldMorphRenderProbe(AssetManager assetManager, RenderManager renderManager,
            Renderer renderer, HeightfieldHierarchy hierarchy, HeightfieldGpuTransitions transitions) {
        if (assetManager == null || renderManager == null || renderer == null || hierarchy == null
                || transitions == null || renderManager.getRenderer() != renderer
                || transitions.getHierarchy() != hierarchy) {
            throw new IllegalArgumentException("matching asset manager, renderer, hierarchy and transitions are required");
        }
        requireCapabilities(renderer);
        this.assetManager = assetManager;
        this.renderManager = renderManager;
        this.renderer = renderer;
        this.hierarchy = hierarchy;
        this.transitions = transitions;

        heights = new TextureArray(Collections.singletonList(hierarchy.getPage().createImage()));
        heights.setMinFilter(Texture.MinFilter.NearestNoMipMaps);
        heights.setMagFilter(Texture.MagFilter.Nearest);
        heights.setWrap(Texture.WrapMode.EdgeClamp);
        grid = new HeightfieldGrid(hierarchy.getPatchCells()).createLodPatchMesh(hierarchy.getRoot());
        grid.setStatic();
        gpuTarget = target();
        referenceTarget = target();
        gpuShadowTarget = target();
        referenceShadowTarget = target();
        decodedDepthTarget = new FrameBuffer(WIDTH, HEIGHT, 1);
        decodedDepthTarget.addColorTarget(FrameBuffer.FrameBufferTarget.newTarget(Image.Format.RGBA32F));

        Camera initialCamera = new Camera(WIDTH, HEIGHT);
        viewPort = new ViewPort("Heightfield morph render probe "
                + Integer.toHexString(System.identityHashCode(this)), initialCamera);
        viewPort.attachScene(scene);
        viewPort.setBackgroundColor(new ColorRGBA(0f, 0f, 0f, 0f));
        viewPort.setClearFlags(true, true, true);

        ambient.setColor(new ColorRGBA(.28f, .34f, .43f, 1f));
        sun.setDirection(new Vector3f(-.55f, -.8f, -.35f).normalizeLocal());
        sun.setColor(new ColorRGBA(1.7f, 1.55f, 1.35f, 1f));
        scene.addLight(ambient);
        scene.addLight(sun);

        boolean es = renderer.getCaps().contains(Caps.OpenGLES30);
        String language = es ? "GLSL300" : "GLSL150";
        String precision = es ? "precision highp float; precision highp int;\n" : "";
        depthDecoder = new Shader();
        depthDecoder.addSource(Shader.ShaderType.Vertex, "heightfield-morph-depth-read.vert",
                precision + "in vec3 inPosition; void main(){gl_Position=vec4(inPosition.xy-vec2(1),0,1);}",
                "", language);
        depthDecoder.addSource(Shader.ShaderType.Fragment, "heightfield-morph-depth-read.frag",
                precision + "uniform sampler2D m_Depth; out vec4 fragColor;"
                        + "void main(){fragColor=vec4(texelFetch(m_Depth,ivec2(gl_FragCoord.xy),0).r);}",
                "", language);
        depthDecoder.getUniform("m_Depth").setValue(VarType.Int, 0);
    }

    /** Render and compare the current GPU morph cover with the supplied CPU state. */
    public Verification verify(Camera camera, List<Patch> reference, String name, Path output)
            throws IOException, com.jme3.renderer.TextureUnitException {
        requireOpen();
        if (camera == null || reference == null || name == null || name.isEmpty() || output == null
                || name.contains("/") || name.contains("\\")) {
            throw new IllegalArgumentException("camera, ordered reference patches, simple name and output folder are required");
        }
        Camera previousCamera = renderManager.getCurrentCamera();
        RenderState previousForcedState = renderManager.getForcedRenderState();
        FrameBuffer resumeTarget = renderer.getCurrentFrameBuffer();
        float[] gpuColor = null;
        float[] referenceColor = null;
        float[] gpuDepth = null;
        float[] referenceDepth = null;
        try {
            scene.detachAllChildren();
            scene.updateGeometricState();

            Geometry gpu = new IndirectGeometry("heightfield temporal GPU cover", grid,
                    transitions.getCommand(), 0, hierarchy.getRoot().getBound());
            gpu.setCullHint(Spatial.CullHint.Never);
            Material gpuMaterial = createMaterial();
            transitions.configureMaterial(gpuMaterial);
            gpuMaterial.setBoolean("UseHeightfieldLod", true);
            gpu.setMaterial(gpuMaterial);
            gpu.setQueueBucket(RenderQueue.Bucket.Opaque);
            scene.attachChild(gpu);
            scene.updateGeometricState();
            renderScene(camera, gpuTarget, false);
            gpuColor = readColor(gpuTarget);

            scene.detachAllChildren();
            for (int i = 0; i < reference.size(); i++) {
                Patch patch = reference.get(i);
                if (patch == null || patch.getNode() == null || patch.getNode().getSpan()
                        != hierarchy.getPatchCells() * patch.getNode().getStride()) {
                    throw new IllegalArgumentException("reference patches must match this hierarchy's grid");
                }
                Geometry geometry = new Geometry("heightfield reference patch " + i, grid);
                geometry.setCullHint(Spatial.CullHint.Never);
                geometry.setQueueBucket(RenderQueue.Bucket.Opaque);
                geometry.setMaterial(createReferenceMaterial(patch));
                scene.attachChild(geometry);
            }
            scene.updateGeometricState();
            renderScene(camera, referenceTarget, false);
            referenceColor = readColor(referenceTarget);

            scene.detachAllChildren();
            scene.attachChild(createGpuGeometry(true));
            scene.updateGeometricState();
            renderScene(camera, gpuShadowTarget, true);
            gpuDepth = readDepth(gpuShadowTarget);

            scene.detachAllChildren();
            for (int i = 0; i < reference.size(); i++) {
                Geometry geometry = new Geometry("heightfield reference caster " + i, grid);
                geometry.setCullHint(Spatial.CullHint.Never);
                geometry.setQueueBucket(RenderQueue.Bucket.Opaque);
                Material material = createReferenceMaterial(reference.get(i));
                material.selectTechnique("PreShadow", renderManager);
                geometry.setMaterial(material);
                scene.attachChild(geometry);
            }
            scene.updateGeometricState();
            renderScene(camera, referenceShadowTarget, true);
            referenceDepth = readDepth(referenceShadowTarget);

            Files.createDirectories(output);
            writePng(output.resolve(name + "-gpu.png"), gpuColor);
            writePng(output.resolve(name + "-reference.png"), referenceColor);
            Verification result = compare(gpuColor, referenceColor, gpuDepth, referenceDepth);
            System.out.println("HEIGHTFIELD_MORPH_RENDER phase=" + name + " " + result);
            return result;
        } finally {
            renderManager.setForcedRenderState(previousForcedState);
            if (previousCamera != null) {
                renderManager.setCamera(previousCamera, false);
            }
            renderer.setFrameBuffer(resumeTarget);
        }
    }

    private Geometry createGpuGeometry(boolean caster) {
        Geometry geometry = new IndirectGeometry("heightfield temporal GPU caster", grid,
                transitions.getCommand(), 0, hierarchy.getRoot().getBound());
        geometry.setCullHint(Spatial.CullHint.Never);
        geometry.setQueueBucket(RenderQueue.Bucket.Opaque);
        Material material = createMaterial();
        transitions.configureMaterial(material);
        material.setBoolean("UseHeightfieldLod", true);
        if (caster) {
            material.selectTechnique("PreShadow", renderManager);
        }
        geometry.setMaterial(material);
        return geometry;
    }

    private Material createMaterial() {
        Material material = new Material(assetManager, "Common/MatDefs/Terrain/Heightfield/Heightfield.j3md");
        material.setTexture("HeightPages", heights);
        material.setInt("HeightLayer", 0);
        material.setFloat("SampleSpacing", hierarchy.getPage().getSampleSpacing());
        material.setBoolean("UseHeightfieldLod", true);
        material.setVector2("PageLatticeOrigin", new Vector2f(0, 0));
        material.setInt("PageCells", hierarchy.getPage().getCells());
        material.setInt("PatchGridCells", hierarchy.getPatchCells());
        return material;
    }

    private Material createReferenceMaterial(Patch patch) {
        HeightfieldHierarchy.Node node = patch.getNode();
        Material material = createMaterial();
        material.setBoolean("UseHeightfieldInstances", false);
        material.setBoolean("UseHeightfieldInstanceMorphs", false);
        material.setInt("PatchGridCells", hierarchy.getPatchCells());
        material.setVector2("PatchLatticeOrigin", new Vector2f(node.getOriginX(), node.getOriginZ()));
        material.setInt("SampleStride", node.getStride());
        material.setFloat("InteriorMorph", patch.getMorph());
        material.setFloat("EdgeMorphWest", patch.getEdgeMorph(0));
        material.setFloat("EdgeMorphEast", patch.getEdgeMorph(1));
        material.setFloat("EdgeMorphSouth", patch.getEdgeMorph(2));
        material.setFloat("EdgeMorphNorth", patch.getEdgeMorph(3));
        return material;
    }

    private void renderScene(Camera camera, FrameBuffer target, boolean caster) {
        Camera captureCamera = camera.clone();
        captureCamera.resize(WIDTH, HEIGHT, true);
        viewPort.setCamera(captureCamera);
        viewPort.setOutputFrameBuffer(target);
        Camera previousCamera = renderManager.getCurrentCamera();
        RenderState previousState = renderManager.getForcedRenderState();
        try {
            if (caster) {
                RenderState unbiased = new RenderState();
                unbiased.setColorWrite(false);
                unbiased.setDepthTest(true);
                unbiased.setDepthWrite(true);
                unbiased.setFaceCullMode(RenderState.FaceCullMode.Off);
                renderManager.setForcedRenderState(unbiased);
            }
            renderManager.renderViewPort(viewPort, 0f);
        } finally {
            renderManager.setForcedRenderState(previousState);
            if (previousCamera != null) {
                renderManager.setCamera(previousCamera, false);
            }
        }
    }

    private float[] readColor(FrameBuffer target) {
        ByteBuffer bytes = BufferUtils.createByteBuffer(WIDTH * HEIGHT * 4 * Float.BYTES)
                .order(ByteOrder.nativeOrder());
        try {
            renderer.setFrameBuffer(target);
            renderer.setViewPort(0, 0, WIDTH, HEIGHT);
            renderer.readFrameBufferWithFormat(target, bytes, Image.Format.RGBA32F);
            float[] result = new float[WIDTH * HEIGHT * 4];
            bytes.asFloatBuffer().get(result);
            return result;
        } finally {
            BufferUtils.destroyDirectBuffer(bytes);
        }
    }

    private float[] readDepth(FrameBuffer source) throws com.jme3.renderer.TextureUnitException {
        FrameBuffer resumeTarget = renderer.getCurrentFrameBuffer();
        ByteBuffer bytes = BufferUtils.createByteBuffer(WIDTH * HEIGHT * 4 * Float.BYTES)
                .order(ByteOrder.nativeOrder());
        RenderState state = new RenderState();
        state.setDepthTest(false);
        state.setDepthWrite(false);
        state.setFaceCullMode(RenderState.FaceCullMode.Off);
        try {
            renderer.setFrameBuffer(decodedDepthTarget);
            renderer.setViewPort(0, 0, WIDTH, HEIGHT);
            renderer.clearClipRect();
            renderer.applyRenderState(state);
            renderer.setTexture(0, source.getDepthTarget().getTexture());
            renderer.setShader(depthDecoder);
            renderer.renderMesh(decodeQuad, 0, 1, null);
            renderer.readFrameBufferWithFormat(decodedDepthTarget, bytes, Image.Format.RGBA32F);
            float[] result = new float[WIDTH * HEIGHT];
            FloatBuffer decoded = bytes.asFloatBuffer();
            for (int i = 0; i < result.length; i++) {
                result[i] = decoded.get(i * 4);
            }
            return result;
        } finally {
            renderer.setFrameBuffer(resumeTarget);
            BufferUtils.destroyDirectBuffer(bytes);
        }
    }

    private static Verification compare(float[] gpu, float[] reference, float[] gpuDepth,
            float[] referenceDepth) {
        int terrainPixels = 0;
        int depthPixels = 0;
        float maxColor = 0;
        float maxDepth = 0;
        for (int pixel = 0; pixel < WIDTH * HEIGHT; pixel++) {
            int offset = pixel * 4;
            boolean gpuCovered = gpu[offset + 3] >= .5f;
            boolean referenceCovered = reference[offset + 3] >= .5f;
            if (gpuCovered != referenceCovered) {
                throw new IllegalStateException("GPU/reference terrain coverage differs at pixel " + pixel);
            }
            if (gpuCovered) terrainPixels++;
            for (int channel = 0; channel < 4; channel++) {
                float a = gpu[offset + channel];
                float b = reference[offset + channel];
                if (!Float.isFinite(a) || !Float.isFinite(b)) {
                    throw new IllegalStateException("non-finite rendered color at pixel " + pixel);
                }
                float error = Math.abs(a - b);
                maxColor = Math.max(maxColor, error);
                if (error > COLOR_TOLERANCE) {
                    throw new IllegalStateException("GPU/reference color differs at pixel " + pixel
                            + " channel " + channel + ": " + a + " vs " + b + " (" + error + ")");
                }
            }
            float a = gpuDepth[pixel];
            float b = referenceDepth[pixel];
            if (!Float.isFinite(a) || !Float.isFinite(b) || a < 0f || a > 1f || b < 0f || b > 1f) {
                throw new IllegalStateException("invalid decoded PreShadow depth at pixel " + pixel);
            }
            if (a < 1f) depthPixels++;
            float error = Math.abs(a - b);
            maxDepth = Math.max(maxDepth, error);
            if (error > DEPTH_TOLERANCE) {
                throw new IllegalStateException("GPU/reference unbiased PreShadow depth differs at pixel "
                        + pixel + ": " + a + " vs " + b + " (" + error + ")");
            }
        }
        if (terrainPixels < 100 || depthPixels < 100) {
            throw new IllegalStateException("insufficient color or caster coverage: " + terrainPixels + "/" + depthPixels);
        }
        return new Verification(terrainPixels, maxColor, maxDepth);
    }

    private static void writePng(Path path, float[] pixels) throws IOException {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                int offset = (y * WIDTH + x) * 4;
                int r = displayByte(pixels[offset]);
                int g = displayByte(pixels[offset + 1]);
                int b = displayByte(pixels[offset + 2]);
                int a = Math.max(0, Math.min(255, Math.round(pixels[offset + 3] * 255f)));
                image.setRGB(x, HEIGHT - 1 - y, (a << 24) | (r << 16) | (g << 8) | b);
            }
        }
        ImageIO.write(image, "png", path.toFile());
    }

    private static int displayByte(float linear) {
        linear = Math.max(0f, Math.min(1f, linear));
        float encoded = linear <= .0031308f ? linear * 12.92f
                : 1.055f * (float) Math.pow(linear, 1f / 2.4f) - .055f;
        return Math.max(0, Math.min(255, Math.round(encoded * 255f)));
    }

    private static void requireCapabilities(Renderer renderer) {
        for (Caps cap : new Caps[]{Caps.TextureArray, Caps.VertexTextureFetch, Caps.FloatTexture,
                Caps.FloatColorBufferRGBA, Caps.DepthTexture}) {
            if (!renderer.getCaps().contains(cap)) {
                throw new UnsupportedOperationException("morph render probe requires " + cap);
            }
        }
    }

    private FrameBuffer target() {
        FrameBuffer target = new FrameBuffer(WIDTH, HEIGHT, 1);
        target.addColorTarget(FrameBuffer.FrameBufferTarget.newTarget(Image.Format.RGBA32F));
        target.setDepthTarget(FrameBuffer.FrameBufferTarget.newTarget(
                new Texture2D(WIDTH, HEIGHT, Image.Format.Depth24)));
        return target;
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("heightfield morph render probe is closed");
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        viewPort.clearScenes();
        for (FrameBuffer owned : new FrameBuffer[]{gpuTarget, referenceTarget, gpuShadowTarget, referenceShadowTarget}) {
            renderer.deleteImage(owned.getDepthTarget().getTexture().getImage());
        }
        renderer.deleteFrameBuffer(gpuTarget);
        renderer.deleteFrameBuffer(referenceTarget);
        renderer.deleteFrameBuffer(gpuShadowTarget);
        renderer.deleteFrameBuffer(referenceShadowTarget);
        renderer.deleteFrameBuffer(decodedDepthTarget);
        renderer.deleteShader(depthDecoder);
        for (VertexBuffer buffer : grid.getBufferList().getArray()) {
            renderer.deleteBuffer(buffer);
        }
        for (VertexBuffer buffer : decodeQuad.getBufferList().getArray()) {
            renderer.deleteBuffer(buffer);
        }
        renderer.deleteImage(heights.getImage());
    }

    public static final class Verification {
        private final int terrainPixels;
        private final float maximumColorDifference;
        private final float maximumDepthDifference;

        private Verification(int terrainPixels, float maximumColorDifference, float maximumDepthDifference) {
            this.terrainPixels = terrainPixels;
            this.maximumColorDifference = maximumColorDifference;
            this.maximumDepthDifference = maximumDepthDifference;
        }

        public int getTerrainPixels() { return terrainPixels; }
        public float getMaximumColorDifference() { return maximumColorDifference; }
        public float getMaximumDepthDifference() { return maximumDepthDifference; }

        @Override
        public String toString() {
            return "HeightfieldMorphRenderProbe.Verification{terrainPixels=" + terrainPixels
                    + ", maxColorDifference=" + maximumColorDifference
                    + ", maxDepthDifference=" + maximumDepthDifference + "}";
        }
    }
}
