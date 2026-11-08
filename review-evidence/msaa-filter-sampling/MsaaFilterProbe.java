import com.jme3.app.SimpleApplication;
import com.jme3.app.state.AppState;
import com.jme3.light.AmbientLight;
import com.jme3.light.DirectionalLight;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.post.FilterPostProcessor;
import com.jme3.post.filters.ContrastAdjustmentFilter;
import com.jme3.post.filters.CrossHatchFilter;
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

/** Asset-free reproduction of ContrastAdjustment and CrossHatch with FPP MSAA. */
public final class MsaaFilterProbe extends SimpleApplication {
    private static final int WIDTH = 800;
    private static final int HEIGHT = 450;
    private final CountDownLatch finished = new CountDownLatch(1);
    private final Path output;
    private int frames;
    private static String filterName;
    private int exitCode;

    private MsaaFilterProbe(Path output) {
        super(new AppState[0]);
        this.output = output;
    }

    public static void main(String[] args) throws Exception {
        filterName = args[2];
        MsaaFilterProbe app = new MsaaFilterProbe(Path.of(args[1]));
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

        FilterPostProcessor filters = new FilterPostProcessor(assetManager);
        filters.setNumSamples(4);
        if (filterName.equals("contrast")) filters.addFilter(new ContrastAdjustmentFilter());
        else filters.addFilter(new CrossHatchFilter());
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
