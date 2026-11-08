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
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; OR BUSINESS INTERRUPTION)
 * HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT
 * LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY
 * OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH
 * DAMAGE.
 */
import com.jme3.app.SimpleApplication;
import com.jme3.app.state.AppState;
import com.jme3.light.AmbientLight;
import com.jme3.light.DirectionalLight;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.post.FilterPostProcessor;
import com.jme3.post.filters.BloomFilter;
import com.jme3.post.filters.FXAAFilter;
import com.jme3.renderer.RenderManager;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.scene.shape.Box;
import com.jme3.scene.shape.Sphere;
import com.jme3.system.AppSettings;
import com.jme3.system.JmeContext;
import com.jme3.util.BufferUtils;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;

/** Engine-only reproduction: an enabled FXAA filter followed by disabled bloom. */
public final class PostFilterSceneEvidence extends SimpleApplication {
    private static final int WIDTH = 800;
    private static final int HEIGHT = 450;
    private final CountDownLatch finished = new CountDownLatch(1);
    private final Path output;
    private int frames;
    private int exitCode;

    private PostFilterSceneEvidence(Path output) {
        super(new AppState[0]);
        this.output = output;
    }

    public static void main(String[] args) throws Exception {
        PostFilterSceneEvidence app = new PostFilterSceneEvidence(Path.of(args[1]));
        AppSettings settings = new AppSettings(true);
        settings.setResolution(WIDTH, HEIGHT);
        settings.setRenderer(args[0].equals("gles") ? AppSettings.ANGLE_GLES3 : AppSettings.LWJGL_OPENGL33);
        settings.setAudioRenderer(null);
        settings.setUseInput(false);
        settings.setX11PlatformPreferred(true);
        settings.setSamples(0);
        settings.setGammaCorrection(false);
        settings.setFrameRate(60);
        app.setSettings(settings);
        app.setShowSettings(false);
        app.setPauseOnLostFocus(false);
        app.setDisplayFps(false);
        app.setDisplayStatView(false);
        app.start(JmeContext.Type.OffscreenSurface);
        if (!app.finished.await(35, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Capture timed out");
        }
        System.exit(app.exitCode);
    }

    @Override
    public void simpleInitApp() {
        viewPort.setBackgroundColor(new ColorRGBA(0.10f, 0.16f, 0.23f, 1f));
        cam.setLocation(new Vector3f(6f, 4.6f, 8f));
        cam.lookAt(new Vector3f(0f, 0.6f, 0f), Vector3f.UNIT_Y);
        cam.setFrustumPerspective(42f, (float) WIDTH / HEIGHT, 0.1f, 100f);
        rootNode.addLight(new AmbientLight(new ColorRGBA(0.35f, 0.35f, 0.35f, 1f)));
        DirectionalLight sun = new DirectionalLight();
        sun.setColor(new ColorRGBA(0.85f, 0.85f, 0.85f, 1f));
        sun.setDirection(new Vector3f(-1f, -2f, -1f).normalizeLocal());
        rootNode.addLight(sun);
        addShape("floor", new Box(5f, 0.1f, 4f), new Vector3f(0f, -0.1f, 0f),
                new ColorRGBA(0.42f, 0.47f, 0.52f, 1f));
        addShape("orange box", new Box(0.85f, 0.85f, 0.85f), new Vector3f(-1.8f, 0.85f, 0f),
                new ColorRGBA(0.95f, 0.35f, 0.08f, 1f));
        addShape("blue sphere", new Sphere(40, 40, 0.95f), new Vector3f(0.6f, 0.95f, 0.8f),
                new ColorRGBA(0.08f, 0.45f, 0.90f, 1f));
        addShape("green column", new Box(0.55f, 1.35f, 0.55f), new Vector3f(1.6f, 1.35f, -1.2f),
                new ColorRGBA(0.25f, 0.75f, 0.25f, 1f));

        // Configure the chain before attaching it to the viewport.
        FilterPostProcessor filters = new FilterPostProcessor(assetManager);
        filters.addFilter(new FXAAFilter());
        BloomFilter bloom = new BloomFilter();
        filters.addFilter(bloom);
        bloom.setEnabled(false);
        viewPort.addProcessor(filters);
    }

    private void addShape(String name, Mesh mesh, Vector3f location, ColorRGBA color) {
        Geometry shape = new Geometry(name, mesh);
        Material material = new Material(assetManager, "Common/MatDefs/Light/Lighting.j3md");
        material.setBoolean("UseMaterialColors", true);
        material.setColor("Diffuse", color);
        material.setColor("Ambient", color);
        material.setColor("Specular", ColorRGBA.White);
        material.setFloat("Shininess", 32f);
        shape.setMaterial(material);
        shape.setLocalTranslation(location);
        rootNode.attachChild(shape);
    }

    @Override
    public void simpleRender(RenderManager renderManager) {
        if (++frames != 8) {
            return;
        }
        ByteBuffer pixels = BufferUtils.createByteBuffer(WIDTH * HEIGHT * 4);
        try {
            renderer.readFrameBuffer(null, pixels);
            BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
            int visiblePixels = 0;
            for (int y = 0; y < HEIGHT; y++) {
                for (int x = 0; x < WIDTH; x++) {
                    int at = (y * WIDTH + x) * 4;
                    int red = pixels.get(at) & 255;
                    int green = pixels.get(at + 1) & 255;
                    int blue = pixels.get(at + 2) & 255;
                    if (Math.max(red, Math.max(green, blue)) > 16) {
                        visiblePixels++;
                    }
                    image.setRGB(x, HEIGHT - y - 1, (red << 16) | (green << 8) | blue);
                }
            }
            Files.createDirectories(output.getParent());
            ImageIO.write(image, "png", output.toFile());
            System.out.println("CAPTURE visiblePixels=" + visiblePixels + " total=" + WIDTH * HEIGHT);
        } catch (Exception error) {
            error.printStackTrace();
            exitCode = 2;
        } finally {
            BufferUtils.destroyDirectBuffer(pixels);
            stop();
        }
    }

    @Override
    public void handleError(String message, Throwable error) {
        System.err.println(message);
        error.printStackTrace();
        exitCode = 2;
        stop();
        finished.countDown();
    }

    @Override
    public void destroy() {
        try {
            super.destroy();
        } finally {
            finished.countDown();
        }
    }
}
