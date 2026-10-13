/*
 * Copyright (c) 2009-2024 jMonkeyEngine
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
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
 * TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
 * PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package jme3test.terrain;

import com.jme3.app.SimpleApplication;
import com.jme3.material.RenderState;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Matrix4f;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;
import com.jme3.renderer.Caps;
import com.jme3.renderer.Limits;
import com.jme3.renderer.Renderer;
import com.jme3.renderer.TextureUnitException;
import com.jme3.scene.Mesh;
import com.jme3.scene.VertexBuffer;
import com.jme3.shader.Shader;
import com.jme3.shader.VarType;
import com.jme3.system.AppSettings;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;

/**
 * Asset-free native proof of shared indexed grids displaced by resident height
 * array layers. Integer vertex fetch uses one halo sample for central-difference
 * normals. A GPU-only interior edit is lost on recreation and explicitly replayed.
 *
 * <p>Arguments: renderer name, output directory, optional "trace". Run with Java
 * assertions enabled. Missing GPU/float-readback capabilities fail explicitly;
 * CPU-expanded meshes are the reference/fallback design, never a fake GPU pass.
 * This finite two-page fixture is not a terrain scheduler, LOD or benchmark.
 */
public final class TestResidentHeightPages extends SimpleApplication {
    private static final int ORACLE_WIDTH = 128;
    private static final int ORACLE_HEIGHT = 64;
    private static final int PREVIEW_WIDTH = 384;
    private static final int PREVIEW_HEIGHT = 256;
    private final CountDownLatch finished = new CountDownLatch(1);
    private final Path output;
    private volatile Throwable failure;
    private int comparisons;
    private float maximumOracleError;
    private float maximumReferenceError;

    private TestResidentHeightPages(Path output) {
        this.output = output;
    }

    public static void main(String[] args) throws Exception {
        TestResidentHeightPages app = new TestResidentHeightPages(Paths.get(
                args.length > 1 ? args[1] : "build/resident-height-pages"));
        AppSettings settings = new AppSettings(true);
        settings.setRenderer(args.length > 0 ? args[0] : AppSettings.ANGLE_GLES3);
        settings.setResolution(320, 240);
        settings.setAudioRenderer(null);
        settings.setGammaCorrection(false);
        settings.setGraphicsDebug(true);
        settings.setGraphicsTrace(args.length > 2 && "trace".equals(args[2]));
        settings.setVSync(false);
        app.setSettings(settings);
        app.setShowSettings(false);
        app.start();
        boolean completed = app.finished.await(45, TimeUnit.SECONDS);
        if (!completed || app.failure != null) {
            app.stop(false);
            throw new IllegalStateException(completed ? "Resident height probe failed"
                    : "Resident height probe timed out", app.failure);
        }
        app.stop(true);
        if (app.failure != null) {
            throw new IllegalStateException("Resident height shutdown failed", app.failure);
        }
        System.out.println("RESIDENT_HEIGHT_PAGES_PASS comparisons=" + app.comparisons
                + " maxOracleError=" + app.maximumOracleError
                + " maxReferenceError=" + app.maximumReferenceError
                + " output=" + app.output.toAbsolutePath());
    }

    @Override
    public void simpleInitApp() {
        try {
            flyCam.setEnabled(false);
            runProbe(renderManager.getRenderer());
        } catch (Throwable error) {
            failure = error;
        } finally {
            finished.countDown();
        }
    }

    @Override
    public void handleError(String message, Throwable error) {
        failure = error == null ? new IllegalStateException(message) : error;
        System.err.println(message);
        failure.printStackTrace();
        finished.countDown();
    }

    private void runProbe(Renderer renderer) throws Exception {
        for (Caps capability : new Caps[]{Caps.TextureArray, Caps.VertexTextureFetch,
                Caps.FloatTexture}) {
            require(renderer.getCaps().contains(capability), "required capability " + capability
                    + "; use CPU-expanded terrain when GPU height fetch is unavailable");
        }
        require(renderer.getCaps().contains(Caps.FloatColorBufferRGBA),
                "float RGBA targets required for this numerical probe, not for ordinary height rendering");
        require(renderer.getLimits().get(Limits.VertexTextureUnits) >= 1, "vertex texture unit");
        boolean gles = renderer.getCaps().contains(Caps.OpenGLES30);
        require(gles || renderer.getCaps().contains(Caps.GLSL330), "GLSL300 ES or GLSL330 desktop");
        ResidentHeightPageFixture.verify();
        TextureArray heights = new TextureArray(Arrays.asList(
                ResidentHeightPageFixture.page(0, false), ResidentHeightPageFixture.page(1, false)));
        heights.setMinFilter(Texture.MinFilter.NearestNoMipMaps);
        heights.setMagFilter(Texture.MagFilter.Nearest);
        heights.setWrap(Texture.WrapMode.EdgeClamp);
        Mesh grid = ResidentHeightPageFixture.grid();
        Shader diagnostic = shader(gles, false);
        Shader preview = shader(gles, true);
        RenderState state = new RenderState();
        state.setFaceCullMode(RenderState.FaceCullMode.Off);
        state.setDepthTest(true);
        state.setDepthWrite(true);
        renderer.applyRenderState(state);
        FrameBuffer oracle = framebuffer(ORACLE_WIDTH, ORACLE_HEIGHT, Image.Format.RGBA32F);
        FrameBuffer perspective = framebuffer(PREVIEW_WIDTH, PREVIEW_HEIGHT, Image.Format.RGBA32F);
        FrameBuffer picture = framebuffer(PREVIEW_WIDTH, PREVIEW_HEIGHT, Image.Format.RGBA8);

        verifyStage(renderer, heights, grid, diagnostic, preview, oracle, perspective, picture,
                false, "initial");
        int[] gridIds = gridIds(grid);
        Image patch = ResidentHeightPageFixture.editPatch();
        uploadEdit(renderer, heights, patch);
        verifyStage(renderer, heights, grid, diagnostic, preview, oracle, perspective, picture,
                true, "edited");
        require(Arrays.equals(gridIds, gridIds(grid)), "edit preserves shared grid buffer handles");
        verifyCpuBacking(heights);
        require(!heights.getImage().isUpdateNeeded(), "patch does not dirty full array");
        renderer.deleteImage(heights.getImage());
        verifyStage(renderer, heights, grid, diagnostic, preview, oracle, perspective, picture,
                false, "recreated-base");
        uploadEdit(renderer, heights, patch);
        verifyStage(renderer, heights, grid, diagnostic, preview, oracle, perspective, picture,
                true, "replayed-edit");
        require(Arrays.equals(gridIds, gridIds(grid)), "texture recreation preserves grid buffers");
        verifyCpuBacking(heights);
        renderer.setFrameBuffer(null);
        System.out.println("RESIDENT_HEIGHT_DRAW_PASS pages=2 sharedVertices=289 sharedTriangles=512"
                + " heightBytes=2888 patchBytes=48 halo=1 comparisons=" + comparisons);
        // Keep live grid, programs, array and FBOs registered for real assertion-
        // enabled manager teardown. CPU reference meshes are retired per stage.
    }

    private void uploadEdit(Renderer renderer, TextureArray heights, Image patch) {
        renderer.modifyTexture(heights, patch, 0,
                ResidentHeightPageFixture.PATCH_X + ResidentHeightPageFixture.HALO,
                ResidentHeightPageFixture.PATCH_Z + ResidentHeightPageFixture.HALO);
    }

    private static int[] gridIds(Mesh grid) {
        return new int[]{grid.getBuffer(VertexBuffer.Type.Position).getId(),
            grid.getBuffer(VertexBuffer.Type.Normal).getId(), grid.getBuffer(VertexBuffer.Type.Index).getId()};
    }

    private void verifyCpuBacking(TextureArray heights) {
        int size = ResidentHeightPageFixture.TEXTURE_SIZE;
        for (int page = 0; page < 2; page++) {
            FloatBuffer data = heights.getImage().getData(page).duplicate()
                    .order(ByteOrder.nativeOrder()).asFloatBuffer();
            for (int z = 0; z < size; z++) {
                for (int x = 0; x < size; x++) {
                    require(data.get(z * size + x) == ResidentHeightPageFixture.height(
                            page, x - 1, z - 1, false), "GPU edit preserves base CPU samples");
                }
            }
        }
    }

    private void verifyStage(Renderer renderer, TextureArray heights, Mesh grid, Shader diagnostic,
            Shader preview, FrameBuffer oracle, FrameBuffer perspective, FrameBuffer picture,
            boolean edited, String label) throws Exception {
        Matrix4f overhead = new Matrix4f();
        overhead.set(1f / 16, 0, 0, -1, 0, 0, 1f / 8, -1,
                0, .1f, 0, 0, 0, 0, 0, 1);
        draw(renderer, heights, diagnostic, oracle, overhead, grid, grid, false);
        FloatBuffer actual = readFloats(renderer, oracle);
        verifyOracle(actual, edited);
        Camera camera = new Camera(PREVIEW_WIDTH, PREVIEW_HEIGHT);
        camera.setFrustumPerspective(50, (float) PREVIEW_WIDTH / PREVIEW_HEIGHT, .1f, 100);
        camera.setLocation(new Vector3f(28, 24, 42));
        camera.lookAt(new Vector3f(16, 1.7f, 8), Vector3f.UNIT_Y);
        Matrix4f viewProjection = camera.getViewProjectionMatrix();
        draw(renderer, heights, diagnostic, perspective, viewProjection, grid, grid, false);
        FloatBuffer displaced = readFloats(renderer, perspective);
        Mesh first = ResidentHeightPageFixture.cpuReference(0, edited);
        Mesh second = ResidentHeightPageFixture.cpuReference(1, edited);
        try {
            draw(renderer, heights, diagnostic, perspective, viewProjection, first, second, true);
            FloatBuffer reference = readFloats(renderer, perspective);
            int covered = 0;
            for (int i = 0; i < reference.capacity(); i++) {
                float error = Math.abs(reference.get(i) - displaced.get(i));
                maximumReferenceError = Math.max(maximumReferenceError, error);
                require(Float.isFinite(error) && error <= .002f, "perspective CPU reference at " + i
                        + ": expected " + reference.get(i) + " actual " + displaced.get(i));
                if (i % 4 == 0 && reference.get(i) > .5f) covered++;
            }
            require(covered > 1000, "perspective reference has terrain coverage");
        } finally {
            for (Mesh mesh : new Mesh[]{first, second}) {
                for (VertexBuffer buffer : mesh.getBufferList().getArray()) {
                    renderer.deleteBuffer(buffer);
                }
            }
        }
        draw(renderer, heights, preview, picture, viewProjection, grid, grid, false);
        ByteBuffer pixels = BufferUtils.createByteBuffer(PREVIEW_WIDTH * PREVIEW_HEIGHT * 4);
        renderer.readFrameBufferWithFormat(picture, pixels, Image.Format.RGBA8);
        writePicture(pixels, label);
    }

    private void verifyOracle(FloatBuffer pixels, boolean edited) {
        for (int z = 0; z < ORACLE_HEIGHT; z++) {
            for (int x = 0; x < ORACLE_WIDTH; x++) {
                float worldX = (x + .5f) * 32 / ORACLE_WIDTH;
                float localZ = (z + .5f) * 16 / ORACLE_HEIGHT;
                int page = worldX >= 16 ? 1 : 0;
                float localX = worldX - page * 16;
                int cellX = (int) localX;
                int cellZ = (int) localZ;
                float dx = localX - cellX;
                float dz = localZ - cellZ;
                int[] vx = dx >= dz ? new int[]{cellX, cellX + 1, cellX + 1}
                        : new int[]{cellX, cellX + 1, cellX};
                int[] vz = dx >= dz ? new int[]{cellZ, cellZ, cellZ + 1}
                        : new int[]{cellZ, cellZ + 1, cellZ + 1};
                float[] weights = dx >= dz ? new float[]{1 - dx, dx - dz, dz}
                        : new float[]{1 - dz, dx, dz - dx};
                float[] expected = new float[4];
                for (int vertex = 0; vertex < 3; vertex++) {
                    Vector3f normal = ResidentHeightPageFixture.normal(page, vx[vertex], vz[vertex], edited);
                    expected[0] += weights[vertex] * ResidentHeightPageFixture.height(
                            page, vx[vertex], vz[vertex], edited);
                    expected[1] += weights[vertex] * normal.x;
                    expected[2] += weights[vertex] * normal.y;
                    expected[3] += weights[vertex] * normal.z;
                }
                for (int channel = 0; channel < 4; channel++) {
                    float value = pixels.get((z * ORACLE_WIDTH + x) * 4 + channel);
                    float error = Math.abs(value - expected[channel]);
                    maximumOracleError = Math.max(maximumOracleError, error);
                    require(Float.isFinite(error) && error <= .00005f,
                            "triangle oracle " + x + "," + z + " channel " + channel
                            + ": expected " + expected[channel] + " actual " + value);
                }
            }
        }
    }

    private void draw(Renderer renderer, TextureArray heights, Shader shader, FrameBuffer target,
            Matrix4f matrix, Mesh first, Mesh second, boolean reference) throws TextureUnitException {
        renderer.setFrameBuffer(target);
        renderer.setViewPort(0, 0, target.getWidth(), target.getHeight());
        renderer.setBackgroundColor(new ColorRGBA(0, 0, 0, 0));
        renderer.clearBuffers(true, true, false);
        renderer.setTexture(0, heights);
        shader.getUniform("m_HeightPages").setValue(VarType.Int, 0);
        shader.getUniform("m_ViewProjection").setValue(VarType.Matrix4, matrix);
        shader.getUniform("m_Reference").setValue(VarType.Boolean, reference);
        for (int layer = 0; layer < 2; layer++) {
            shader.getUniform("m_Page").setValue(VarType.Int, layer);
            renderer.setShader(shader);
            renderer.renderMesh(layer == 0 ? first : second, 0, 1, null);
        }
    }

    private static Shader shader(boolean gles, boolean preview) {
        String language = gles ? "GLSL300" : "GLSL330";
        Shader shader = new Shader();
        shader.addSource(Shader.ShaderType.Vertex, "ResidentHeight.vert",
                "#ifdef GL_ES\nprecision highp float;\nprecision highp int;\n"
                + "precision highp sampler2DArray;\n#endif\n"
                + "in vec3 inPosition;\nin vec3 inNormal;\n"
                + "uniform sampler2DArray m_HeightPages;\nuniform int m_Page;\n"
                + "uniform bool m_Reference;\nuniform mat4 m_ViewProjection;\n"
                + "out vec3 vNormal;\nout float vHeight;\nout vec2 vWorldXZ;\n"
                + "float heightAt(ivec2 p){return texelFetch(m_HeightPages,ivec3(p,m_Page),0).r;}\n"
                + "void main(){ivec2 p=ivec2(inPosition.xz)+ivec2(1);\n"
                + "float h=m_Reference?inPosition.y:heightAt(p);\n"
                + "vec3 n=m_Reference?inNormal:normalize(vec3(\n"
                + "-(heightAt(p+ivec2(1,0))-heightAt(p-ivec2(1,0)))*0.5,1.0,\n"
                + "-(heightAt(p+ivec2(0,1))-heightAt(p-ivec2(0,1)))*0.5));\n"
                + "vec3 world=vec3(inPosition.x+float(m_Page)*16.0,h,inPosition.z);\n"
                + "vNormal=n;vHeight=h;vWorldXZ=world.xz;gl_Position=m_ViewProjection*vec4(world,1.0);}",
                "", language);
        String fragment = "#ifdef GL_ES\nprecision highp float;\n#endif\n"
                + "in vec3 vNormal;\nin float vHeight;\nin vec2 vWorldXZ;\nout vec4 outColor;\n";
        if (preview) {
            fragment += "void main(){vec3 n=normalize(vNormal);\n"
                    + "float sun=max(dot(n,normalize(vec3(-0.6,1.0,0.4))),0.0);\n"
                    + "vec3 base=mix(vec3(0.11,0.24,0.08),vec3(0.45,0.4,0.22),clamp((vHeight-1.0)/2.0,0.0,1.0));\n"
                    + "vec3 color=base*(0.35+sun*0.8);\n"
                    + "float seam=1.0-smoothstep(0.02,0.08,abs(vWorldXZ.x-16.0));\n"
                    + "color=mix(color,vec3(0.65,0.75,0.82),seam*0.5);\n"
                    + "outColor=vec4(pow(clamp(color,0.0,1.0),vec3(1.0/2.2)),1.0);}";
        } else {
            fragment += "void main(){outColor=vec4(vHeight,vNormal);}";
        }
        shader.addSource(Shader.ShaderType.Fragment, "ResidentHeight.frag", fragment, "", language);
        return shader;
    }

    private static FrameBuffer framebuffer(int width, int height, Image.Format format) {
        FrameBuffer result = new FrameBuffer(width, height, 1);
        result.addColorTarget(FrameBuffer.FrameBufferTarget.newTarget(format));
        result.setDepthTarget(FrameBuffer.FrameBufferTarget.newTarget(Image.Format.Depth24));
        return result;
    }

    private static FloatBuffer readFloats(Renderer renderer, FrameBuffer framebuffer) {
        ByteBuffer bytes = BufferUtils.createByteBuffer(framebuffer.getWidth() * framebuffer.getHeight() * 16)
                .order(ByteOrder.nativeOrder());
        renderer.readFrameBufferWithFormat(framebuffer, bytes, Image.Format.RGBA32F);
        bytes.rewind();
        return bytes.asFloatBuffer();
    }

    private void writePicture(ByteBuffer pixels, String label) throws Exception {
        Files.createDirectories(output);
        BufferedImage image = new BufferedImage(PREVIEW_WIDTH, PREVIEW_HEIGHT, BufferedImage.TYPE_INT_RGB);
        int covered = 0;
        for (int y = 0; y < PREVIEW_HEIGHT; y++) {
            for (int x = 0; x < PREVIEW_WIDTH; x++) {
                int offset = ((PREVIEW_HEIGHT - 1 - y) * PREVIEW_WIDTH + x) * 4;
                int r = pixels.get(offset) & 255;
                int g = pixels.get(offset + 1) & 255;
                int b = pixels.get(offset + 2) & 255;
                if ((pixels.get(offset + 3) & 255) > 0) covered++;
                image.setRGB(x, y, (r << 16) | (g << 8) | b);
            }
        }
        require(covered > 1000, "nonempty preview " + label);
        ImageIO.write(image, "png", output.resolve(label + ".png").toFile());
    }

    private void require(boolean condition, String message) {
        comparisons++;
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
