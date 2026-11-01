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
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package jme3test.terrain;

import com.jme3.app.SimpleApplication;
import com.jme3.light.AmbientLight;
import com.jme3.light.DirectionalLight;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector2f;
import com.jme3.math.Vector3f;
import com.jme3.post.FilterPostProcessor;
import com.jme3.post.SceneProcessor;
import com.jme3.post.filters.FXAAFilter;
import com.jme3.post.filters.KHRToneMapFilter;
import com.jme3.profile.AppProfiler;
import com.jme3.renderer.Camera;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.ViewPort;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.renderer.queue.RenderQueue.ShadowMode;
import com.jme3.shadow.DirectionalLightShadowFilter;
import com.jme3.shadow.DirectionalLightVisibilityRenderer;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.scene.Spatial;
import com.jme3.scene.instancing.IndirectGeometry;
import com.jme3.system.AppSettings;
import com.jme3.terrain.heightfield.HeightfieldGrid;
import com.jme3.terrain.heightfield.HeightfieldGpuPageBindings;
import com.jme3.terrain.heightfield.HeightfieldGpuSelection;
import com.jme3.terrain.heightfield.HeightfieldGpuTransitions;
import com.jme3.terrain.heightfield.HeightfieldGpuCoverFeedback;
import com.jme3.terrain.heightfield.HeightfieldHierarchy;
import com.jme3.terrain.heightfield.HeightfieldMetadataBuilder;
import com.jme3.terrain.heightfield.HeightfieldHierarchy.Node;
import com.jme3.terrain.heightfield.HeightfieldLod;
import com.jme3.terrain.heightfield.HeightfieldLod.Patch;
import com.jme3.terrain.heightfield.HeightfieldLod.Selection;
import com.jme3.terrain.heightfield.HeightfieldLod.Transition;
import com.jme3.terrain.heightfield.HeightfieldPage;
import com.jme3.terrain.heightfield.HeightfieldSource;
import com.jme3.terrain.heightfield.HeightfieldSkyIrradiance;
import com.jme3.terrain.heightfield.HeightfieldReconstruction;
import com.jme3.terrain.heightfield.HeightfieldResidency;
import com.jme3.terrain.heightfield.HeightfieldResidency.PageKey;
import com.jme3.terrain.heightfield.HeightfieldResidency.Request;
import com.jme3.terrain.heightfield.HeightfieldResidency.Handle;
import com.jme3.terrain.heightfield.HeightfieldResidency.Frame;
import com.jme3.terrain.heightfield.HeightfieldResidency.Stats;
import com.jme3.renderer.opengl.GLFence;
import com.jme3.bounding.BoundingBox;
import com.jme3.texture.FrameBuffer;
import com.jme3.texture.Image;
import com.jme3.texture.Texture;
import com.jme3.texture.TextureArray;
import com.jme3.util.BufferUtils;
import java.awt.image.BufferedImage;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;

/**
 * Finite engine-only residency control: one canonical source is decimated into
 * independent 16-cell GPU pages, with a fixed atlas and delayed synthetic jobs.
 * This proves publication and retirement around the existing CPU LOD oracle.
 * It does not accelerate selection or claim scenic quality or production speed.
 * Page jobs run deterministically on the owner thread; device loss and general
 * production scheduling remain separate acceptance.
 * Pass --gpu-selection for an immediate GPU cover using actual node pages and
 * conservative all-ready-page frame leases. That mode keeps one source revision;
 * pass --gpu-transitions to add persistent GPU morph state with readback feedback.
 * Add --source-gradients to preserve canonical source lighting in node pages;
 * height and gradient payloads share publication, generations and frame leases.
 * Normal exploration streams without synthetic delays, failures or automatic
 * edits. An output directory or --validation enables the finite stress controls.
 * --large-region uses a tiled source without a dense heightmap, CPU
 * selection, canonical gradients and a geometry pool bounded by its page capacity.
 * Its finite tour retains the same failure/edit/recreation checks, but uses
 * rectangle-cover checks rather than a full-region bitmap or dense GPU oracle.
 * Regional revisions prepare source and metadata on one bounded worker while
 * the old cover stays visible; initial bootstrap still blocks before rendering.
 * --sync-region-edits retains the synchronous comparison; --revision-stress
 * exercises a superseded build, a failed build and recovery to a newer revision.
 * --surface-cache adds independently paged regional rock/soil/grass/snow detail;
 * the finite tour waits for coherent material roots and captures each revision.
 * Regional detail defaults to 64-cell grids and 128 desired patches. Use
 * --region-detail=low for the original 16-cell/64-patch control, and
 * --sync-region-pages for the owner-thread preparation comparison.
 * Regional light defaults to GPU transmittance/multiple-scattering sky passes.
 * --reference-sky retains the CPU single-scattering control; --single-scattering
 * disables higher orders in the GPU sky. --plain-lighting retains constant fill;
 * --sun-elevation=12 shows a low sun. --hemisphere-fill retains artistic fill;
 * otherwise directional diffuse sky uses the shared HDR environment. Add
 * --aerial-perspective for camera-to-surface transport before tone mapping.
 * --sky-view resolves the visible sky at the actual camera, near its horizon;
 * terrain direct/diffuse light retains the fixed observer control.
 * --region-cells=2048 expands to one kilometre; --region-cells=8192 connects a
 * four-kilometre synthetic landscape around the existing central one-kilometre area.
 * Both larger sources bootstrap a coarse root while measured metadata prepares.
 * The default 1024-cell control stays at 512 metres. --batch-terrain opts into
 * spatial CPU-remapped instance draws using the same LOD cover and page leases.
 * --forest adds bounded original groves grounded on the current morphed surface;
 * it is separate from dense forest streaming and distant impostor acceptance.
 */
public final class TestHeightfieldStreaming extends SimpleApplication {
    private static final int CELLS = 128, DEFAULT_PATCH_CELLS = 16, DEFAULT_CAPACITY = 32;
    private static final float FAR_VIEW_DISTANCE = 6500f;
    private static final float SPACING = .5f, TRANSITION_STEP = .25f;
    private static final int DEFAULT_PATCH_BUDGET = 16, MAX_FRAMES = 720, MAX_JOBS = 12;
    private static final int PROFILE_WARMUP = 60, MAX_PROFILE_STAGE_SAMPLES = 4096;
    private static final double PIXEL_TARGET = 1.5;
    private final Path output;
    private final boolean gpuSelectionMode;
    private final boolean gpuTransitionsMode;
    private final boolean profileMode;
    private final boolean validationEnabled;
    private final boolean sourceGradientMode;
    private final boolean largeRegionMode;
    private final boolean synchronousRegionEdits, revisionStress, surfaceCacheMode;
    private RegionalSurfaceCache surfaces;
    private int surfaceActivationFrame = -1, surfaceActivations, surfaceFallbackFrames;
    private RegionalRevisionPreparation revisionPreparation;
    private long pendingRevision, requestedRevision;
    private int revisionFailures, preparingFrames, publishedFrame, samplingEndFrame;
    private boolean samplingStopped;
    private String revisionCapture;
    private final int capacity, patchBudget, patchCells, regionCells, pressureFrameCount;
    private int pressureStartFrame = 150, pressureEndFrame;
    private final boolean asynchronousInitialMetadata;
    private boolean initialMetadataPending;
    private int initialPreparingFrames, initialMetadataCompletedFrame;
    private int stressRequestFrame = -1;
    private final long startupStartedNanos = System.nanoTime();
    private boolean explorationReadyReported;
    private final boolean synchronousRegionPages;
    private RegionalPagePreparation pagePreparation;
    private final boolean atmosphereLighting;
    private final float sunElevation;
    private AtmosphereSky atmosphereSky;
    private final boolean referenceSky, singleScattering, hemisphereFill, aerialPerspective, skyViewEnabled;
    private HeightfieldSkyIrradiance skyIrradiance;
    private AtmosphereAerialPerspectiveFilter aerial;
    private GpuAtmosphereSkyView skyView;
    private volatile boolean cleanupComplete;
    private HeightfieldGrid patchGrid;
    private final boolean batchTerrain, forestMode, forestImpostors;
    private final float forestSpacing;
    private final int forestPages;
    private RegionalConiferForest forest;
    private final Vector3f forestFocus = new Vector3f();
    private RegionalTerrainBatches terrainBatches;
    private final List<RetiredTerrainBatches> retiredTerrainBatches = new ArrayList<>();
    private int peakBatchDraws;
    private List<PageKey> hierarchyPageKeys = Collections.emptyList();
    private List<PageKey> demandPageKeys = Collections.emptyList();
    private final List<Geometry> spareGeometry = new ArrayList<>();
    private int createdGeometry, peakActiveGeometry;
    private HeightfieldSource source, pendingSource;
    private final long pageBytes;
    private final List<Long> feedbackPollNanos = new ArrayList<>(MAX_PROFILE_STAGE_SAMPLES);
    private final List<Long> jobUploadNanos = new ArrayList<>(MAX_PROFILE_STAGE_SAMPLES);
    private final List<Long> cpuOracleNanos = new ArrayList<>(MAX_PROFILE_STAGE_SAMPLES);
    private final List<Long> gpuDispatchNanos = new ArrayList<>(MAX_PROFILE_STAGE_SAMPLES);
    private final CountDownLatch completed = new CountDownLatch(1);
    private final Map<Node, Geometry> geometryByNode = new IdentityHashMap<>();
    private HeightfieldHierarchy pageKeyHierarchy;
    private long pageKeyRevision = -1;
    private Map<Node, PageKey> pageKeys = new IdentityHashMap<>();
    private final Set<Node> displayed = Collections.newSetFromMap(new IdentityHashMap<>());
    private final List<Job> jobs = new ArrayList<>();
    private final List<GpuResourceBundle> retiredGpuResources = new ArrayList<>();
    private final Map<PageKey, Integer> requestedAt = new HashMap<>();
    private final Set<PageKey> obsolete = new LinkedHashSet<>();
    private final List<Integer> publicationLatency = new ArrayList<>();
    private HeightfieldResidency residency;
    private HeightfieldPage page, pendingPage;
    private HeightfieldHierarchy hierarchy, pendingHierarchy;
    private HeightfieldLod lod;
    private Transition transition;
    private Selection currentSelection;
    private CameraPath cameraPath;
    private RegionalRockDetail rockDetail;
    private Camera selectorCamera;
    private final Vector3f selectorLocation = new Vector3f();
    private com.jme3.scene.Node terrainParent;
    private HeightfieldLodGpuProbe gpuProbe;
    private HeightfieldGpuPageBindings gpuPageBindings;
    private HeightfieldGpuSelection gpuSelection;
    private HeightfieldGpuTransitions gpuTransitions;
    private HeightfieldGpuCoverFeedback gpuFeedback;
    private HeightfieldFrameTimings timings;
    private DirectionalLightVisibilityRenderer regionalVisibility;
    private Geometry gpuGeometry;
    private Mesh gpuPatchMesh;
    private Material gpuMaterial;
    private TextureArray atlas;
    private TextureArray gradientAtlas;
    private FrameBuffer captureTarget;
    private Frame frameLease;
    private volatile Throwable failure;
    private long revision;
    private int frame, checks, uploads, staleResults, preparationFailures, uploadFailures;
    private int cancellations, recreations, edits, peakRequests, peakReady, peakRetired, peakFrames;
    private int fencePolls, fencePending, fenceSignaled, waitingFrames, fullAtlasFrames;
    private int peakRetiredGpuBundles, retiredGpuFencePending, retiredGpuFenceSignaled;
    private int retiredGpuBundlesClosed, recreationDrainFrames;
    private long peakQueuedBytes, peakPreparedBytes, comparedChannels;
    private float maximumGpuError;
    private boolean injectedPreparation, injectedUpload, injectedCancellation;
    private boolean retiringStorage;
    private final Set<Node> gpuCurrentCover = Collections.newSetFromMap(new IdentityHashMap<>());
    private boolean gpuFeedbackPending;
    private boolean gpuRecreationDraining;
    private GLFence gpuRecreationFence;
    private int gpuDispatchSequence, pendingFeedbackSequence = -1, feedbackNotBeforeFrame;
    private int feedbackReads, feedbackSkippedDispatches, feedbackNativePendingPolls, artificialFeedbackDelaySkipped;
    private int profileDrainFrames;
    private boolean profileWritten;
    private List<Node> pendingOracleNodes = Collections.emptyList();

    private TestHeightfieldStreaming(Path output, boolean gpuSelectionMode,
            boolean gpuTransitionsMode, boolean profileMode, boolean sourceGradientMode, boolean largeRegionMode,
            boolean synchronousRegionEdits, boolean revisionStress, boolean surfaceCacheMode,
            boolean validationEnabled, boolean batchTerrain, boolean forestMode, boolean forestImpostors, RegionalOptions region) {
        this.output = output;
        this.batchTerrain = batchTerrain;
        this.forestMode = forestMode;
        this.forestImpostors = forestImpostors;
        forestSpacing = region.forestSpacing;
        forestPages = region.forestPages;
        this.gpuSelectionMode = gpuSelectionMode;
        this.gpuTransitionsMode = gpuTransitionsMode;
        this.profileMode = profileMode;
        this.validationEnabled = validationEnabled;
        this.sourceGradientMode = sourceGradientMode;
        this.largeRegionMode = largeRegionMode;
        this.synchronousRegionEdits = synchronousRegionEdits;
        this.revisionStress = revisionStress;
        this.surfaceCacheMode = surfaceCacheMode;
        regionCells = region.regionCells;
        int regionalPoolScale = Math.min(2, regionCells / RegionalHeightfieldSource.CELLS);
        capacity = largeRegionMode ? region.detail.capacity * regionalPoolScale : DEFAULT_CAPACITY;
        patchBudget = largeRegionMode ? region.detail.patches * regionalPoolScale : DEFAULT_PATCH_BUDGET;
        patchCells = largeRegionMode ? region.detail.cells : DEFAULT_PATCH_CELLS;
        synchronousRegionPages = region.synchronousPages;
        atmosphereLighting = largeRegionMode && !region.plainLighting;
        sunElevation = region.sunElevation;
        referenceSky = region.referenceSky;
        singleScattering = region.singleScattering;
        hemisphereFill = region.hemisphereFill;
        aerialPerspective = region.aerialPerspective;
        skyViewEnabled = region.skyViewEnabled;
        pressureFrameCount = largeRegionMode ? Math.max(130, capacity / 2 + 32) : 55;
        pressureEndFrame = pressureStartFrame + pressureFrameCount;
        asynchronousInitialMetadata = largeRegionMode && regionCells > RegionalHeightfieldSource.CELLS
                && !synchronousRegionEdits;
        if (asynchronousInitialMetadata) {
            pressureStartFrame = Integer.MAX_VALUE;
            pressureEndFrame = Integer.MAX_VALUE;
        }
        this.pageBytes = (long) (patchCells + 3) * (patchCells + 3) * Float.BYTES
                * (sourceGradientMode ? 3L : 1L);
    }

    private enum RegionalDetail {
        LOW(16, 64, 128), DETAILED(64, 128, 256);
        final int cells, patches, capacity;
        RegionalDetail(int cells, int patches, int capacity) {
            this.cells = cells; this.patches = patches; this.capacity = capacity;
        }
    }

    private static final class RegionalOptions {
        final RegionalDetail detail;
        final boolean synchronousPages, plainLighting;
        final float sunElevation, forestSpacing;
        final int forestPages;
        final int regionCells;
        final boolean referenceSky, singleScattering, hemisphereFill, aerialPerspective, skyViewEnabled;
        RegionalOptions(RegionalDetail detail, boolean synchronousPages, boolean plainLighting,
                float sunElevation, int regionCells, boolean referenceSky, boolean singleScattering, boolean hemisphereFill, boolean aerialPerspective, boolean skyViewEnabled, float forestSpacing, int forestPages) {
            this.detail = detail;
            this.synchronousPages = synchronousPages;
            this.plainLighting = plainLighting;
            this.sunElevation = sunElevation;
            this.forestSpacing = forestSpacing; this.forestPages = forestPages;
            this.regionCells = regionCells;
            this.referenceSky = referenceSky;
            this.singleScattering = singleScattering;
            this.hemisphereFill = hemisphereFill;
            this.aerialPerspective = aerialPerspective;
            this.skyViewEnabled = skyViewEnabled;
        }
    }

    public static void main(String[] args) throws Exception {
        boolean assertions = false;
        assert assertions = true;
        List<String> positional = new ArrayList<>();
        boolean gpuSelectionMode = false;
        boolean gpuTransitionsMode = false;
        boolean profileMode = false;
        boolean explicitValidation = false, batchTerrain = false, forestMode = false, forestImpostors = false;
        boolean sourceGradientMode = false, largeRegionMode = false;
        boolean synchronousRegionEdits = false, revisionStress = false, surfaceCacheMode = false;
        boolean regionDetailExplicit = false, synchronousPages = false;
        boolean plainLighting = false, sunExplicit = false;
        float sunElevation = 55, forestSpacing = 14;
        int forestPages = 96;
        int regionCells = RegionalHeightfieldSource.CELLS;
        boolean regionCellsExplicit = false;
        boolean referenceSky = false, singleScattering = false, hemisphereFill = false, aerialPerspective = false, skyViewEnabled = false;
        RegionalDetail detail = RegionalDetail.DETAILED;
        for (String arg : args) {
            if ("--gpu-selection".equals(arg)) gpuSelectionMode = true;
            else if ("--gpu-transitions".equals(arg)) gpuTransitionsMode = true;
            else if ("--forest".equals(arg)) forestMode = true;
            else if ("--forest-impostors".equals(arg)) forestImpostors = true;
            else if (arg.startsWith("--forest-spacing=")) forestSpacing = Float.parseFloat(arg.substring("--forest-spacing=".length()));
            else if (arg.startsWith("--forest-pages=")) forestPages = Integer.parseInt(arg.substring("--forest-pages=".length()));
            else if ("--batch-terrain".equals(arg)) batchTerrain = true;
            else if ("--profile".equals(arg)) profileMode = true;
            else if ("--validation".equals(arg)) explicitValidation = true;
            else if ("--source-gradients".equals(arg)) sourceGradientMode = true;
            else if ("--large-region".equals(arg)) largeRegionMode = true;
            else if ("--sync-region-edits".equals(arg)) synchronousRegionEdits = true;
            else if ("--revision-stress".equals(arg)) revisionStress = true;
            else if ("--surface-cache".equals(arg)) surfaceCacheMode = true;
            else if ("--sync-region-pages".equals(arg)) synchronousPages = true;
            else if ("--plain-lighting".equals(arg)) plainLighting = true;
            else if ("--reference-sky".equals(arg)) referenceSky = true;
            else if ("--hemisphere-fill".equals(arg)) hemisphereFill = true;
            else if ("--aerial-perspective".equals(arg)) aerialPerspective = true;
            else if ("--sky-view".equals(arg)) skyViewEnabled = true;
            else if ("--single-scattering".equals(arg)) singleScattering = true;
            else if (arg.startsWith("--region-cells=")) {
                regionCells = Integer.parseInt(arg.substring("--region-cells=".length()));
                regionCellsExplicit = true;
            }
            else if (arg.startsWith("--sun-elevation=")) {
                sunElevation = Float.parseFloat(arg.substring("--sun-elevation=".length()));
                sunExplicit = true;
            }
            else if (arg.startsWith("--region-detail=")) {
                detail = RegionalDetail.valueOf(arg.substring("--region-detail=".length()).toUpperCase(Locale.ROOT));
                regionDetailExplicit = true;
            }
            else if (arg.startsWith("--")) throw new IllegalArgumentException("Unknown option: " + arg);
            else positional.add(arg);
        }
        if (!Float.isFinite(forestSpacing) || forestSpacing < 6 || forestPages < 1 || forestPages > 128) {
            throw new IllegalArgumentException("forest spacing must be >=6m and page budget1..128");
        }
        if (forestImpostors && !forestMode) throw new IllegalArgumentException("--forest-impostors requires --forest");
        if (forestMode && !largeRegionMode) {
            throw new IllegalArgumentException("--forest requires --large-region CPU terrain");
        }
        if (batchTerrain && !largeRegionMode) {
            throw new IllegalArgumentException("--batch-terrain requires --large-region CPU selection");
        }
        if (largeRegionMode && (gpuSelectionMode || gpuTransitionsMode)) {
            throw new IllegalArgumentException("--large-region uses CPU selection; the GPU ABI remains capped at 85 nodes");
        }
        if ((synchronousRegionEdits || revisionStress) && !largeRegionMode
                || synchronousRegionEdits && revisionStress) {
            throw new IllegalArgumentException("revision controls require --large-region and are mutually exclusive");
        }
        if ((plainLighting || sunExplicit) && !largeRegionMode || plainLighting && sunExplicit
                || !Float.isFinite(sunElevation) || sunElevation < -90 || sunElevation > 90) {
            throw new IllegalArgumentException("sky options require --large-region; sun elevation in [-90,90] cannot accompany --plain-lighting");
        }
        if ((aerialPerspective || skyViewEnabled) && (!largeRegionMode || plainLighting || referenceSky)) {
            throw new IllegalArgumentException("camera atmosphere controls require regional GPU atmosphere");
        }
        if ((referenceSky || singleScattering || hemisphereFill) && (!largeRegionMode || plainLighting)
                || referenceSky && singleScattering) {
            throw new IllegalArgumentException("sky controls require regional atmosphere; reference and single scattering are exclusive");
        }
        if (regionCells != 1024 && regionCells != 2048 && regionCells != RegionalHeightfieldSource.FAR_CELLS
                || regionCellsExplicit && !largeRegionMode) {
            throw new IllegalArgumentException("--region-cells requires --large-region and 1024, 2048, or 8192 cells");
        }
        if (regionCells == RegionalHeightfieldSource.FAR_CELLS && detail != RegionalDetail.DETAILED) {
            throw new IllegalArgumentException("8192-cell terrain requires the bounded detailed grid");
        }
        if ((regionDetailExplicit || synchronousPages) && !largeRegionMode) {
            throw new IllegalArgumentException("regional detail options require --large-region");
        }
        if (surfaceCacheMode && !largeRegionMode) {
            throw new IllegalArgumentException("--surface-cache requires --large-region");
        }
        if (largeRegionMode) sourceGradientMode = true;
        else if (gpuTransitionsMode || sourceGradientMode) gpuSelectionMode = true;
        if (positional.size() > 2) throw new IllegalArgumentException("Expected renderer and optional output directory");
        String rendererName = positional.isEmpty() ? AppSettings.ANGLE_GLES3 : positional.get(0);
        Path output = positional.size() > 1 ? Paths.get(positional.get(1)) : null;
        boolean validationEnabled = output != null || explicitValidation || revisionStress;
        if (profileMode && output == null) throw new IllegalArgumentException("--profile requires an output directory");
        if (!assertions) throw new IllegalStateException("Streaming control requires -ea");
        System.out.println("HEIGHTFIELD_STREAM_MODE mode=" + (validationEnabled ? "validation" : "manual-exploration")
                + " validationEnabled=" + validationEnabled + " output=" + (output != null)
                + " revisionStress=" + revisionStress + " automaticCameraTour=" + (output != null));
        TestHeightfieldStreaming app = new TestHeightfieldStreaming(output, gpuSelectionMode,
                gpuTransitionsMode, profileMode, sourceGradientMode, largeRegionMode,
                synchronousRegionEdits, revisionStress, surfaceCacheMode, validationEnabled, batchTerrain, forestMode, forestImpostors,
                new RegionalOptions(detail, synchronousPages, plainLighting, sunElevation, regionCells, referenceSky, singleScattering, hemisphereFill, aerialPerspective, skyViewEnabled, forestSpacing, forestPages));
        AppSettings settings = new AppSettings(true);
        settings.setRenderer(rendererName);
        settings.setResolution(960, 640);
        settings.setAudioRenderer(null);
        settings.setGraphicsDebug(Boolean.getBoolean("jme.heightfieldLod.debug"));
        settings.setGammaCorrection(true);
        settings.setVSync(false);
        app.setSettings(settings);
        app.setShowSettings(false);
        app.start();
        if (output != null) {
            int timeoutSeconds = largeRegionMode && regionCells > 1024 ? 600
                    : largeRegionMode && detail != RegionalDetail.LOW ? 360 : surfaceCacheMode ? 180 : 90;
            if (!app.completed.await(timeoutSeconds, TimeUnit.SECONDS)) {
                app.stop(false);
                throw new IllegalStateException("Streaming control exceeded " + timeoutSeconds + " seconds", app.failure);
            }
        } else {
            // Keep main alive until normal closure or an error. Display creation
            // happens on the render thread and must not silently exit successfully.
            app.completed.await();
        }
        app.stop(true);
        if (app.failure != null) throw new IllegalStateException("Streaming control failed", app.failure);
        if (output != null && !app.cleanupComplete) {
            throw new IllegalStateException("Streaming render resources were not released");
        }
    }

    @Override public void destroy() {
        try {
            // LegacyApplication cleans app states, not manually attached scene
            // processors. Release them while this render context is still live.
            if (viewPort != null) viewPort.clearProcessors();
        } catch (RuntimeException | Error error) {
            if (failure == null) fail(error);
            else failure.addSuppressed(error);
        } finally {
            try {
                super.destroy();
            } finally {
                completed.countDown();
            }
        }
    }

    @Override public void handleError(String message, Throwable error) {
        fail(error == null ? new IllegalStateException(message) : error);
    }

    @Override public void simpleInitApp() {
        try {
            flyCam.setEnabled(output == null);
            flyCam.setMoveSpeed(regionCells == RegionalHeightfieldSource.FAR_CELLS ? 120 : 24);
            flyCam.setDragToRotate(true);
            HeightfieldGrid.requireSupported(renderer);
            patchGrid = new HeightfieldGrid(patchCells);
            residency = new HeightfieldResidency(patchCells, pageBytes,
                    new HeightfieldResidency.Limits(capacity, MAX_JOBS, MAX_JOBS * pageBytes,
                            6L * pageBytes, 2, 2L * pageBytes, 12, 12));
            atlas = createAtlas();
            if (sourceGradientMode) gradientAtlas = createGradientAtlas();
            if (largeRegionMode) {
                RegionalHeightfieldSource initialSource = new RegionalHeightfieldSource(regionCells, 0);
                source = initialSource;
                initialMetadataPending = asynchronousInitialMetadata;
                hierarchy = initialMetadataPending ? initialSource.bootstrapHierarchy(patchCells) : measureRegion(source, 0);
                if (!synchronousRegionEdits) {
                    revisionPreparation = new RegionalRevisionPreparation((sourceRevision, cancelled) -> {
                        if (revisionStress && sourceRevision == 2) {
                            throw new IllegalStateException("injected regional metadata failure");
                        }
                        return RegionalRevisionPreparation.prepare(patchCells, regionCells, sourceRevision, cancelled);
                    });
                    if (initialMetadataPending) revisionPreparation.request(0);
                }
            } else {
                page = makePage(0);
                source = page;
                hierarchy = new HeightfieldHierarchy(page, patchCells, 0);
            }
            if (largeRegionMode) {
                selectorCamera = new Camera(cam.getWidth(), cam.getHeight());
                terrainParent = new com.jme3.scene.Node("regional-terrain-world");
                float worldOrigin = ((RegionalHeightfieldSource) source).getWorldOrigin();
                terrainParent.setLocalTranslation(worldOrigin, 0, worldOrigin);
                rootNode.attachChild(terrainParent);
            }
            if (surfaceCacheMode) {
                int surfaceCells = regionCells == RegionalHeightfieldSource.FAR_CELLS
                        ? 2048 : regionCells;
                surfaces = new RegionalSurfaceCache(renderer, surfaceCells);
                surfaces.requestRevision(0);
                if (regionCells == RegionalHeightfieldSource.FAR_CELLS) {
                    rockDetail = new RegionalRockDetail(assetManager);
                    System.out.println("HEIGHTFIELD_ROCK_DETAIL baseImageBytes=" + rockDetail.getBaseImageBytes()
                            + " estimatedRgb8MipBytes=" + rockDetail.getEstimatedMipChainRgb8Bytes()
                            + " heightBaseImageBytes=" + rockDetail.getHeightBaseImageBytes()
                            + " heightMipBytes=" + rockDetail.getHeightMipChainBytes()
                            + " heightMipLevels=" + rockDetail.getHeightMipLevels());
                }
            }
            if (largeRegionMode && patchCells > DEFAULT_PATCH_CELLS && !synchronousRegionPages) {
                pagePreparation = new RegionalPagePreparation(patchCells, MAX_JOBS, regionCells);
            }
            installRoot(hierarchy, revision);
            rebuildScene();
            if (this.gpuSelectionMode) initializeGpuSelection();
            addLightingAndCapture();
            cameraPath = new CameraPath();
            cameraPath.apply(0);
            if (forestMode) {
                inputManager.addMapping("forest-focus", new com.jme3.input.controls.KeyTrigger(com.jme3.input.KeyInput.KEY_4));
                inputManager.addListener((com.jme3.input.controls.ActionListener) (name, pressed, tpf) -> {
                    if (pressed) focusForest();
                }, "forest-focus");
            }
        } catch (Throwable error) { fail(error); }
    }

    private TextureArray createAtlas() {
        return createAtlas(false);
    }

    private TextureArray createGradientAtlas() {
        return createAtlas(true);
    }

    private TextureArray createAtlas(boolean gradients) {
        List<Image> layers = new ArrayList<>();
        float[] zeros = new float[(patchCells + 3) * (patchCells + 3)];
        for (int slot = 0; slot < capacity; slot++) {
            HeightfieldPage empty = new HeightfieldPage(patchCells, SPACING, zeros,
                    gradients ? new float[zeros.length * 2] : null);
            layers.add(gradients ? empty.createGradientImage() : empty.createImage());
        }
        TextureArray result = new TextureArray(layers);
        result.setMinFilter(Texture.MinFilter.NearestNoMipMaps);
        result.setMagFilter(Texture.MagFilter.Nearest);
        result.setWrap(Texture.WrapMode.EdgeClamp);
        return result;
    }

    private static HeightfieldPage makePage(long sourceRevision) {
        int side = CELLS + 3;
        float[] samples = new float[side * side];
        for (int z = -1; z <= CELLS + 1; z++) for (int x = -1; x <= CELLS + 1; x++) {
            samples[(z + 1) * side + x + 1] = (float) height(x * (double) SPACING,
                    z * (double) SPACING, sourceRevision);
        }
        return new HeightfieldPage(CELLS, SPACING, samples);
    }

    private static double height(double x, double z) { return height(x, z, 0); }
    private static double height(double x, double z, long sourceRevision) {
        double rolling = 1.1 * Math.sin(.19 * x) * Math.cos(.16 * z)
                + .42 * Math.sin(.31 * (x + .7 * z));
        double ridge = 5.2 * Math.exp(-((x - 17.5) * (x - 17.5)) / 30.0)
                * Math.exp(-((z - 31.0) * (z - 31.0)) / 520.0);
        double spike = 7.0 * Math.exp(-((x - 43.0) * (x - 43.0)
                + (z - 22.0) * (z - 22.0)) / 7.0);
        return rolling + ridge + spike + sourceRevision * 2.0
                * Math.exp(-((x - 35) * (x - 35) + (z - 30) * (z - 30)) / 180.0);
    }

    private PageKey key(Node node, long sourceRevision) {
        if (node == null) throw new IllegalArgumentException("node is required");
        if (hierarchy == pageKeyHierarchy && revision == pageKeyRevision
                && sourceRevision == revision) {
            PageKey cached = pageKeys.get(node);
            if (cached != null) return cached;
        }
        return new PageKey(sourceRevision, node.getOriginX(), node.getOriginZ(), node.getStride());
    }

    private HeightfieldPage prepare(Node node, long sourceRevision) {
        return prepare(node, sourceRevision, sourceRevision == revision ? source : pendingSource);
    }

    private HeightfieldPage prepare(Node node, long sourceRevision, HeightfieldSource snapshot) {
        if (largeRegionMode) {
            require(snapshot != null && ((RegionalHeightfieldSource) snapshot).getRevision() == sourceRevision,
                    "preparation retains its immutable source snapshot");
            HeightfieldSource samples = patchCells >= 32 && node.getStride() <= 2
                    ? snapshot : ((RegionalHeightfieldSource) snapshot).pageSamples(node.getStride());
            return HeightfieldPage.fromSource(samples, node.getOriginX(), node.getOriginZ(),
                    patchCells, node.getStride(), sourceGradientMode);
        }
        int side = patchCells + 3, stride = node.getStride();
        float[] samples = new float[side * side];
        float[] gradients = sourceGradientMode ? new float[samples.length * 2] : null;
        for (int z = -1; z <= patchCells + 1; z++) for (int x = -1; x <= patchCells + 1; x++) {
            samples[(z + 1) * side + x + 1] = (float) height(
                    (node.getOriginX() + x * stride) * (double) SPACING,
                    (node.getOriginZ() + z * stride) * (double) SPACING, sourceRevision);
            if (gradients != null) {
                int gx = node.getOriginX() + x * stride;
                int gz = node.getOriginZ() + z * stride;
                float left = (float) height((gx - 1) * (double) SPACING, gz * (double) SPACING, sourceRevision);
                float right = (float) height((gx + 1) * (double) SPACING, gz * (double) SPACING, sourceRevision);
                float back = (float) height(gx * (double) SPACING, (gz - 1) * (double) SPACING, sourceRevision);
                float front = (float) height(gx * (double) SPACING, (gz + 1) * (double) SPACING, sourceRevision);
                int offset = 2 * ((z + 1) * side + x + 1);
                gradients[offset] = left * .5f - right * .5f;
                gradients[offset + 1] = back * .5f - front * .5f;
            }
        }
        return new HeightfieldPage(patchCells, SPACING * stride, samples, gradients);
    }

    private void installRoot(HeightfieldHierarchy source, long sourceRevision) {
        installRoot(source, sourceRevision, null);
    }

    private void installRoot(HeightfieldHierarchy source, long sourceRevision, HeightfieldPage replay) {
        PageKey root = key(source.getRoot(), sourceRevision);
        Request request = residency.request(root);
        require(request != null && residency.complete(request, replay == null ? prepare(source.getRoot(), sourceRevision) : replay),
                "bootstrap root preparation fits bounded storage");
        uploadPrepared(false);
        require(residency.ready(root) != null && residency.pin(root), "ready root pinned before drawing");
    }

    private HeightfieldHierarchy measureRegion(HeightfieldSource snapshot, long sourceRevision) {
        long begin = System.nanoTime();
        HeightfieldHierarchy result = snapshot instanceof RegionalHeightfieldSource
                ? ((RegionalHeightfieldSource) snapshot).measuredHierarchy(patchCells)
                : HeightfieldMetadataBuilder.build(snapshot, snapshot, patchCells, sourceRevision, 0);
        System.out.printf(Locale.ROOT,
                "HEIGHTFIELD_REGION_METADATA revision=%d cells=%d nodes=%d constructionMs=%.3f retainedDenseHeightBytes=0%n",
                sourceRevision, result.getCells(), result.getNodes().size(), (System.nanoTime() - begin) / 1e6);
        return result;
    }

    private void rebuildScene() {
        ensurePageKeyCache();
        if (terrainBatches != null) {
            terrainBatches.detach();
            retiredTerrainBatches.add(new RetiredTerrainBatches(terrainBatches, renderer.createFence()));
            terrainBatches = null;
        }
        for (Node node : displayed) detachTerrain(geometryByNode.get(node));
        displayed.clear();
        if (largeRegionMode) spareGeometry.addAll(geometryByNode.values());
        geometryByNode.clear();
        if (gpuProbe != null) gpuProbe.close();
        gpuProbe = output == null || profileMode ? null : largeRegionMode
                ? HeightfieldLodGpuProbe.forSource(assetManager, renderManager, renderer,
                        ((RegionalHeightfieldSource) source).pageSamples(2), atlas)
                : new HeightfieldLodGpuProbe(assetManager, renderManager, renderer, page);
        lod = new HeightfieldLod(hierarchy);
        transition = new Transition(hierarchy);
        List<Node> demandOrder = hierarchy.getNodes();
        if (largeRegionMode) {
            demandOrder = new ArrayList<>(demandOrder);
            demandOrder.sort(java.util.Comparator.comparingInt(Node::getLevel));
        }
        List<PageKey> orderedDemandKeys = new ArrayList<>(demandOrder.size());
        for (Node node : demandOrder) orderedDemandKeys.add(key(node, revision));
        demandPageKeys = orderedDemandKeys;
        if (batchTerrain) {
            terrainBatches = new RegionalTerrainBatches(renderer, hierarchy, patchGrid, capacity,
                    terrainParent, this::createTerrainMaterial,
                    material -> { if (regionalVisibility != null) regionalVisibility.removeReceiver(material); });
        }
        if (gpuSelectionMode || largeRegionMode) return;
        for (Node node : hierarchy.getNodes()) geometryByNode.put(node, createPatchGeometry(node));
    }

    /** Reuse immutable current-revision keys without retaining previous trees. */
    private void ensurePageKeyCache() {
        if (pageKeyHierarchy == hierarchy && pageKeyRevision == revision) return;
        Map<Node, PageKey> keys = new IdentityHashMap<>(hierarchy.getNodes().size());
        List<PageKey> orderedKeys = new ArrayList<>(hierarchy.getNodes().size());
        for (Node node : hierarchy.getNodes()) {
            PageKey pageKey = new PageKey(revision, node.getOriginX(), node.getOriginZ(), node.getStride());
            keys.put(node, pageKey);
            orderedKeys.add(pageKey);
        }
        pageKeys = keys;
        hierarchyPageKeys = orderedKeys;
        pageKeyHierarchy = hierarchy;
        pageKeyRevision = revision;
    }

    private Geometry createPatchGeometry(Node node) {
        Geometry geometry = new Geometry("stream-patch", patchGrid.createLodPatchMesh(node));
        geometry.setShadowMode(ShadowMode.CastAndReceive);
        // Opaque terrain and shadows finish before Capture.postFrame fences it.
        geometry.setQueueBucket(RenderQueue.Bucket.Opaque);
        geometry.setMaterial(createTerrainMaterial());
        configurePatchGeometry(geometry, node);
        if (largeRegionMode) {
            createdGeometry++;
            require(createdGeometry <= capacity, "geometry pool is bounded by physical page capacity");
        }
        return geometry;
    }

    private Material createTerrainMaterial() {
        Material material = new Material(assetManager, "Common/MatDefs/Terrain/Heightfield/Heightfield.j3md");
        material.setBoolean("UseHeightfieldLod", true);
        material.setInt("PageCells", patchCells);
        material.setInt("PatchGridCells", patchCells);
        material.setVector2("PatchLatticeOrigin", new Vector2f(0, 0));
        material.setInt("SampleStride", 1);
        material.setFloat("SnowLine", largeRegionMode ? 210f : 7.8f);
        material.setFloat("SnowFade", largeRegionMode ? 35f : 1.5f);
        material.setColor("GrassColor", new ColorRGBA(.12f, .20f, .075f, 1));
        material.setColor("RockColor", new ColorRGBA(.29f, .26f, .22f, 1));
        material.setColor("SnowColor", new ColorRGBA(.83f, .87f, .9f, 1));
        if (skyIrradiance != null) skyIrradiance.bind(material);
        if (rockDetail != null) {
            rockDetail.bind(material);
            rockDetail.bindHeight(material);
        }
        material.setTexture("HeightPages", atlas);
        if (largeRegionMode) {
            material.setTexture("HeightGradientPages", gradientAtlas);
            material.setBoolean("UseHeightfieldSourceGradients", true);
            if (regionCells == RegionalHeightfieldSource.FAR_CELLS && surfaces != null) {
                material.setBoolean("UseHeightfieldSurfaceRegionFallback", true);
                material.setFloat("SurfaceRegionFade", 64f);
                // Align broad colors with cached materials, retaining the far
                // classification and continuous noise rather than adding soil.
                material.setColor("GrassColor", new ColorRGBA(.20f, .35f, .12f, 1));
                material.setColor("RockColor", new ColorRGBA(.34f, .33f, .28f, 1));
                material.setColor("SnowColor", new ColorRGBA(.76f, .79f, .84f, 1));
            }
        }
        if (regionalVisibility != null) regionalVisibility.addReceiver(material);
        return material;
    }

    private void pollRetiredTerrainBatches() {
        for (Iterator<RetiredTerrainBatches> it = retiredTerrainBatches.iterator(); it.hasNext();) {
            RetiredTerrainBatches retired = it.next();
            if (!renderer.isFenceSignaled(retired.fence)) continue;
            retired.batches.close();
            renderer.deleteFence(retired.fence);
            it.remove();
        }
    }

    private static final class RetiredTerrainBatches {
        final RegionalTerrainBatches batches;
        final GLFence fence;
        RetiredTerrainBatches(RegionalTerrainBatches batches, GLFence fence) {
            this.batches = batches; this.fence = fence;
        }
    }

    private void configurePatchGeometry(Geometry geometry, Node node) {
        Vector3f origin = new Vector3f(node.getOriginX() * SPACING, 0, node.getOriginZ() * SPACING);
        BoundingBox bound = node.getBound();
        bound.setCenter(bound.getCenter().subtract(origin));
        geometry.getMesh().setBound(bound);
        geometry.setLocalTranslation(origin);
        Material material = geometry.getMaterial();
        material.setTexture("HeightPages", atlas);
        material.setFloat("SampleSpacing", SPACING * node.getStride());
        material.setVector2("PageLatticeOrigin", new Vector2f(
                node.getOriginX() / node.getStride(), node.getOriginZ() / node.getStride()));
        if (largeRegionMode) {
            material.setTexture("HeightGradientPages", gradientAtlas);
            material.setBoolean("UseHeightfieldSourceGradients", true);
            material.setFloat("SourceSampleSpacing", SPACING);
            if (regionCells == RegionalHeightfieldSource.FAR_CELLS && surfaces != null) {
                material.setBoolean("UseHeightfieldSurfaceRegionFallback", true);
                material.setFloat("SurfaceRegionFade", 64f);
            }
        }
    }

    private void initializeGpuSelection() throws java.io.IOException {
        activateGpuResources(createGpuResources(hierarchy, atlas, gradientAtlas));
    }

    private GpuResourceBundle createGpuResources(HeightfieldHierarchy targetHierarchy,
            TextureArray targetAtlas, TextureArray targetGradients) throws java.io.IOException {
        HeightfieldGpuPageBindings pageBindings = null;
        HeightfieldGpuSelection selection = null;
        HeightfieldGpuTransitions transitions = null;
        HeightfieldGpuCoverFeedback feedback = null;
        Mesh patchMesh = null;
        try {
            pageBindings = new HeightfieldGpuPageBindings(renderer, targetHierarchy, capacity);
            if (gpuTransitionsMode) {
                transitions = new HeightfieldGpuTransitions(assetManager, renderer, targetHierarchy);
                feedback = new HeightfieldGpuCoverFeedback(assetManager, renderer, targetHierarchy);
            } else {
                selection = new HeightfieldGpuSelection(assetManager, renderer, targetHierarchy,
                        HeightfieldGpuSelection.DrawCover.Complete);
            }
            patchMesh = new HeightfieldGrid(patchCells).createLodPatchMesh(targetHierarchy.getRoot());
            patchMesh.setStatic();
            IndirectGeometry geometry = new IndirectGeometry("stream-GPU-selection", patchMesh,
                    gpuTransitionsMode ? transitions.getCommand() : selection.getCommand(),
                    0, targetHierarchy.getRoot().getBound());
            geometry.setCullHint(Spatial.CullHint.Never);
            geometry.setQueueBucket(RenderQueue.Bucket.Opaque);
            geometry.setShadowMode(ShadowMode.CastAndReceive);
            Material material = new Material(assetManager,
                    "Common/MatDefs/Terrain/Heightfield/Heightfield.j3md");
            if (gpuTransitionsMode) transitions.configureMaterial(material);
            else selection.configureMaterial(material);
            pageBindings.configureMaterial(material);
            material.setTexture("HeightPages", targetAtlas);
            if (targetGradients != null) {
                material.setTexture("HeightGradientPages", targetGradients);
                material.setBoolean("UseHeightfieldSourceGradients", true);
            }
            material.setColor("GrassColor", new ColorRGBA(.12f, .20f, .075f, 1));
            material.setColor("RockColor", new ColorRGBA(.29f, .26f, .22f, 1));
            material.setColor("SnowColor", new ColorRGBA(.83f, .87f, .9f, 1));
            material.setFloat("SnowLine", 7.8f);
            material.setFloat("SnowFade", 1.5f);
            material.setInt("HeightLayer", 0);
            material.setFloat("SampleSpacing", SPACING);
            material.setInt("PageCells", CELLS);
            material.setInt("PatchGridCells", patchCells);
            material.setBoolean("UseHeightfieldLod", true);
            material.setVector2("PageLatticeOrigin", new Vector2f(0, 0));
            geometry.setMaterial(material);
            return new GpuResourceBundle(selection, transitions, feedback, pageBindings,
                    patchMesh, geometry, material, null);
        } catch (Throwable error) {
            new GpuResourceBundle(selection, transitions, feedback, pageBindings,
                    patchMesh, null, null, null).close(renderer);
            throw error;
        }
    }

    private void activateGpuResources(GpuResourceBundle resources) {
        gpuSelection = resources.selection;
        gpuTransitions = resources.transitions;
        gpuFeedback = resources.feedback;
        gpuPageBindings = resources.pageBindings;
        gpuPatchMesh = resources.patchMesh;
        gpuGeometry = resources.geometry;
        gpuMaterial = resources.material;
        if (gpuTransitionsMode) gpuCurrentCover.add(hierarchy.getRoot());
        rootNode.attachChild(gpuGeometry);
        rootNode.updateGeometricState();
    }

    private GpuResourceBundle detachGpuResources(GLFence lastUse) {
        if (gpuGeometry != null) rootNode.detachChild(gpuGeometry);
        GpuResourceBundle retired = new GpuResourceBundle(gpuSelection, gpuTransitions,
                gpuFeedback, gpuPageBindings, gpuPatchMesh, gpuGeometry, gpuMaterial, lastUse);
        gpuSelection = null;
        gpuTransitions = null;
        gpuFeedback = null;
        gpuPageBindings = null;
        gpuPatchMesh = null;
        gpuGeometry = null;
        gpuMaterial = null;
        return retired;
    }

    private void retireGpuResources() {
        if (gpuSelection == null && gpuTransitions == null && gpuFeedback == null
                && gpuPageBindings == null && gpuPatchMesh == null) return;
        require(!gpuFeedbackPending, "GPU feedback is drained before retiring its hierarchy resources");
        retiredGpuResources.add(detachGpuResources(renderer.createFence()));
        peakRetiredGpuBundles = Math.max(peakRetiredGpuBundles, retiredGpuResources.size());
    }

    private void pollRetiredGpuResources() {
        Iterator<GpuResourceBundle> iterator = retiredGpuResources.iterator();
        while (iterator.hasNext()) {
            GpuResourceBundle retired = iterator.next();
            if (!renderer.isFenceSignaled(retired.lastUse)) {
                retiredGpuFencePending++;
                continue;
            }
            retiredGpuFenceSignaled++;
            retired.close(renderer);
            renderer.deleteFence(retired.lastUse);
            iterator.remove();
            retiredGpuBundlesClosed++;
        }
    }

    private void closeActiveGpuResources() {
        GpuResourceBundle active = detachGpuResources(null);
        active.close(renderer);
    }

    @Override public void simpleUpdate(float tpf) {
        if (failure != null || hierarchy == null) return;
        try {
            if (profileMode && !samplingStopped) timings.beginFrame(timingPhase(frame + 1));
            frame++;
            if (initialMetadataPending) initialPreparingFrames++;
            if (validationEnabled && !initialMetadataPending && frame == pressureEndFrame
                    && fullAtlasFrames == 0) {
                // Worker throughput changes with generation speed and CPU load.
                // Keep this finite fixture in pressure until it observes a full atlas.
                require(frame - pressureStartFrame < pressureFrameCount * 8,
                        "validation pressure filled the atlas within its bounded phase");
                pressureEndFrame += pressureFrameCount;
                System.out.println("HEIGHTFIELD_PRESSURE_EXTEND frame=" + frame
                        + " through=" + pressureEndFrame);
            }
            require(frameLease == null, "preceding opaque terrain and shadow reads fenced");
            if (validationEnabled && frame == 500 && gpuTransitionsMode) beginGpuRecreationDrain();
            else if (validationEnabled && !gpuSelectionMode && (surfaces == null && regionCells == 1024 ? frame == 500
                    : recreations == 0 && edits == 1 && frame >= publishedFrame + 120
                    && (patchCells == DEFAULT_PATCH_CELLS || frame >= pressureEndFrame + 30))) {
                retiringStorage = true;
            }
            residency.poll();
            boolean feedbackPollAttempt = gpuTransitionsMode && gpuFeedbackPending
                    && frame >= feedbackNotBeforeFrame;
            long feedbackStart = profileMode ? System.nanoTime() : 0;
            if (gpuTransitionsMode) pollGpuFeedback();
            if (gpuSelectionMode) pollRetiredGpuResources();
            if (batchTerrain) pollRetiredTerrainBatches();
            if (profileMode) {
                recordStage(feedbackPollNanos, System.nanoTime() - feedbackStart, feedbackPollAttempt);
            }
            if (gpuRecreationDraining) {
                if (gpuFeedbackPending || residency.stats().frames != 0
                        || !renderer.isFenceSignaled(gpuRecreationFence)
                        || !retiredGpuResources.isEmpty()) {
                    recreationDrainFrames++;
                    return;
                }
                finishGpuRecreation();
            }
            if (profileMode && samplingStopped) {
                List<PageKey> drainKeys = new ArrayList<>();
                if (gpuSelectionMode) {
                    for (PageKey candidate : hierarchyPageKeys) {
                        if (residency.ready(candidate) != null) drainKeys.add(candidate);
                    }
                } else {
                    for (Patch patch : transition.getPatches()) {
                        drainKeys.add(key(patch.getNode(), revision));
                    }
                }
                frameLease = residency.acquire(drainKeys);
                require(frameLease != null, "drain frame keeps the last cover leased");
                if (surfaces != null) surfaces.beginFrame(revision, cam);
                sampleBudgets();
                return;
            }
            Iterator<PageKey> oldKeys = obsolete.iterator();
            while (oldKeys.hasNext()) {
                PageKey old = oldKeys.next();
                residency.evict(old);
                residency.cancelPrepared(old);
                if (residency.ready(old) == null) oldKeys.remove();
            }
            if (output != null) cameraPath.apply(frame);
            if (regionCells == RegionalHeightfieldSource.FAR_CELLS) updateSelectorCamera();
            if (surfaces != null) {
                surfaces.pump(cam);
                if (!surfaces.isRevisionReady(revision)) surfaceFallbackFrames++;
                else if (surfaceActivationFrame < 0) {
                    surfaceActivationFrame = frame;
                    surfaceActivations++;
                    revisionCapture = "surface-ready-r" + revision + "-g" + recreations;
                    System.out.println("HEIGHTFIELD_SURFACE_READY frame=" + frame
                            + " revision=" + revision + " generation=" + recreations);
                }
            }
            if (revisionStress) {
                // Expanded coarse material roots take longer to compose. Keep
                // an original coherent surface before deliberately superseding it.
                if (stressRequestFrame < 0 && frame >= 90 && fullAtlasFrames > 0
                        && (surfaces == null || surfaces.isRevisionReady(revision))) {
                    stressRequestFrame = frame;
                    requestRegionalRevision(1);
                } else if (frame == stressRequestFrame + 1) {
                    requestRegionalRevision(2);
                }
            } else if (validationEnabled && (!gpuSelectionMode || gpuTransitionsMode) && edits == 0
                    && !initialMetadataPending && requestedRevision == 0 && pendingHierarchy == null
                    && frame >= Math.max(320, pressureEndFrame + 30)
                    && (surfaces == null ? frame == Math.max(320, pressureEndFrame + 30)
                    : surfaceActivationFrame >= 0 && frame >= surfaceActivationFrame + 120)) beginEdit();
            pollRegionalRevision();
            // Avoid accumulating whole retired arrays outside the slot budget.
            // Keep the old cover if a real fence still reports pending, and
            // replace storage only when every old terrain use has completed.
            if (!gpuSelectionMode && retiringStorage && residency.stats().frames == 0
                    && (surfaces == null || surfaces.canRecreate())) {
                recreate();
                retiringStorage = false;
            }
            double target = output == null ? PIXEL_TARGET : requestedPixels(frame);
            // The conservative bootstrap is a valid rendered cover, not a hint
            // to extract the entire world before measured metadata is available.
            Selection ideal = lod.select(terrainCamera(),
                    target, initialMetadataPending ? 1 : patchBudget, node -> true);
            Set<PageKey> retained = new LinkedHashSet<>();
            if (gpuTransitionsMode) {
                if (gpuFeedbackPending) {
                    for (PageKey ready : hierarchyPageKeys) {
                        if (residency.ready(ready) != null) retained.add(ready);
                    }
                } else {
                    for (Node node : gpuCurrentCover) addAncestors(retained, node, revision);
                }
            } else {
                for (Patch patch : transition.getPatches()) addAncestors(retained, patch.getNode(), revision);
            }
            Set<PageKey> wanted = new LinkedHashSet<>();
            if (pendingHierarchy != null) wanted.add(key(pendingHierarchy.getRoot(), pendingRevision));
            wanted.add(key(hierarchy.getRoot(), revision));
            // Parent-first ordering keeps coarse fallback and sibling transactions useful.
            Set<PageKey> idealAncestors = new LinkedHashSet<>();
            for (Node leaf : ideal.getLeaves()) addAncestors(idealAncestors, leaf, revision);
            if (!initialMetadataPending) for (PageKey candidate : demandPageKeys) {
                if (idealAncestors.contains(candidate)) wanted.add(candidate);
            }
            if (validationEnabled && !initialMetadataPending
                    && frame >= pressureStartFrame && frame < pressureEndFrame) {
                // Deliberately retain more desired cache pages than the atlas can
                // hold. This is a finite capacity-pressure test, not useful prefetch.
                int pressurePages = 0;
                List<PageKey> pressureOrder = largeRegionMode ? demandPageKeys : hierarchyPageKeys;
                for (PageKey candidate : pressureOrder) {
                    wanted.add(candidate);
                    if (largeRegionMode ? ++pressurePages >= capacity + 8 : wanted.size() >= capacity + 8) break;
                }
            }
            if (!initialMetadataPending) for (PageKey candidate : hierarchyPageKeys) {
                if (!retained.contains(candidate) && !wanted.contains(candidate)) {
                    residency.evict(candidate);
                    residency.cancelPrepared(candidate);
                }
            }
            for (Job job : jobs) {
                if (!wanted.contains(job.request.getKey()) && !retained.contains(job.request.getKey())) {
                    residency.cancel(job.request);
                }
            }
            long jobUploadStart = profileMode ? System.nanoTime() : 0;
            schedule(wanted);
            deliverJobs();
            uploadPrepared(validationEnabled);
            if (gpuTransitionsMode) publishGpuEdit();
            else if (!gpuSelectionMode) publishEdit();
            if (profileMode) recordStage(jobUploadNanos, System.nanoTime() - jobUploadStart, true);
            long oracleNanos = 0;
            long oracleStart = profileMode ? System.nanoTime() : 0;
            if (gpuSelectionMode) {
                gpuPageBindings.update(node -> {
                    Handle handle = residency.ready(key(node, revision));
                    return handle == null ? -1 : handle.getSlot();
                });
                currentSelection = lod.select(terrainCamera(),
                        target, patchBudget, gpuPageBindings::isReady);
            } else {
                currentSelection = lod.select(terrainCamera(), target, patchBudget,
                        node -> residency.ready(key(node, revision)) != null);
            }
            if (profileMode) oracleNanos += System.nanoTime() - oracleStart;
            if (currentSelection.isReadinessLimited()) waitingFrames++;
            long gpuDispatchStart = -1;
            if (gpuTransitionsMode) {
                if (!gpuFeedbackPending && (output == null || frame < MAX_FRAMES)) {
                    oracleStart = profileMode ? System.nanoTime() : 0;
                    transition.advance(currentSelection, TRANSITION_STEP);
                    pendingOracleNodes = oracleNodes(transition.getPatches());
                    if (profileMode) oracleNanos += System.nanoTime() - oracleStart;
                    gpuDispatchStart = profileMode ? System.nanoTime() : 0;
                    gpuTransitions.dispatch(terrainCamera(),
                            target, (int) revision, patchBudget, TRANSITION_STEP, gpuPageBindings::isReady);
                    gpuFeedbackPending = true;
                    pendingFeedbackSequence = ++gpuDispatchSequence;
                    gpuFeedback.submit(gpuTransitions.getPatchRecords(), 4,
                            pendingFeedbackSequence, (int) revision);
                    if (profileMode) {
                        recordStage(gpuDispatchNanos, System.nanoTime() - gpuDispatchStart, true);
                    }
                    if (validationEnabled && frame >= 200 && frame < 280) feedbackNotBeforeFrame = frame + 3;
                    else feedbackNotBeforeFrame = frame;
                } else if (gpuFeedbackPending) {
                    feedbackSkippedDispatches++;
                    // This is a synthetic CPU-side delay; native query readiness
                    // is reported independently by the pending counter above.
                    if (frame < feedbackNotBeforeFrame) artificialFeedbackDelaySkipped++;
                }
            } else if (gpuSelectionMode) {
                oracleStart = profileMode ? System.nanoTime() : 0;
                convergeOracle(currentSelection);
                if (profileMode) oracleNanos += System.nanoTime() - oracleStart;
            } else {
                oracleStart = profileMode ? System.nanoTime() : 0;
                transition.advance(currentSelection, TRANSITION_STEP);
                if (profileMode) oracleNanos += System.nanoTime() - oracleStart;
            }
            if (profileMode) recordStage(cpuOracleNanos, oracleNanos, true);
            if (surfaces != null) surfaces.beginFrame(revision, cam);
            List<Patch> patches = transition.getPatches();
            verifyFrame(patches);
            List<PageKey> drawKeys = new ArrayList<>();
            if (gpuSelectionMode) {
                // GPU instance count stays opaque, so lease every ready page
                // this frame's complete-cover selector could address.
                for (PageKey candidate : hierarchyPageKeys) {
                    if (residency.ready(candidate) != null) drawKeys.add(candidate);
                }
            } else {
                for (Patch patch : patches) drawKeys.add(key(patch.getNode(), revision));
            }
            frameLease = residency.acquire(drawKeys);
            require(frameLease != null, "whole draw cover acquired atomically");
            if (gpuTransitionsMode) {
                // The temporal helper owns the indirect command and dispatch.
            } else if (gpuSelectionMode) {
                if (!profileMode || frame <= MAX_FRAMES) {
                    gpuDispatchStart = profileMode ? System.nanoTime() : 0;
                    gpuSelection.dispatch(terrainCamera(),
                            target, (int) revision, patchBudget, gpuPageBindings::isReady);
                    if (profileMode) {
                        recordStage(gpuDispatchNanos, System.nanoTime() - gpuDispatchStart, true);
                    }
                }
            } else {
                syncDrawSet(patches);
                if (forest != null) {
                    forest.update((RegionalHeightfieldSource) source, hierarchy, revision, patches, node -> {
                        Handle handle = residency.ready(key(node, revision));
                        require(handle != null && residency.isRetained(handle), "forest ground uses retained current pages");
                        return residency.get(handle);
                    }, cam, !initialMetadataPending && !transition.isTransitioning()
                            && !currentSelection.isReadinessLimited()
                            && displayed.size() == currentSelection.getLeaves().size()
                            && displayed.containsAll(currentSelection.getLeaves()));
                }
            }
            if (!validationEnabled && !explorationReadyReported && !initialMetadataPending
                    && (surfaces == null || surfaces.isRevisionReady(revision))
                    && !currentSelection.isReadinessLimited() && !currentSelection.isBudgetLimited()
                    && !currentSelection.isSourceLimited() && matchesDesiredCover(currentSelection)) {
                double pixels = transition.getMaximumPixels(terrainCamera());
                if (pixels <= PIXEL_TARGET) {
                    explorationReadyReported = true;
                    System.out.printf(Locale.ROOT,
                            "HEIGHTFIELD_EXPLORATION_READY cells=%d frame=%d startupMs=%.3f maximumPixels=%.6f patches=%d%n",
                            regionCells, frame, (System.nanoTime() - startupStartedNanos) / 1e6,
                            pixels, patches.size());
                }
            }
            sampleBudgets();
            if (output != null && !profileMode && (isCaptureFrame(frame)
                    || revisionPreparation != null && publishedFrame > 0 && frame == publishedFrame + 120
                    || isSurfaceCaptureFrame() || isFinalDetailFrame())) {
                if (gpuTransitionsMode) {
                    verifyGpuTransitionRecords(patches);
                } else if (gpuSelectionMode) {
                    verifyGpuPageRecords(patches);
                } else if (gpuProbe != null && (!largeRegionMode || frame == 350 || frame == 530
                        || revisionPreparation != null && publishedFrame > 0 && frame == publishedFrame + 120
                        || isSurfaceCaptureFrame() || isFinalDetailFrame())) {
                    for (Patch patch : verificationPatches(patches)) {
                        Handle handle = residency.ready(key(patch.getNode(), revision));
                        HeightfieldLodGpuProbe.Verification result = gpuProbe.verifyNodePage(patch,
                                residency.get(handle), atlas, handle.getSlot(), captureTarget);
                        comparedChannels += result.getComparedChannels();
                        maximumGpuError = Math.max(maximumGpuError, result.getMaximumAbsoluteError());
                    }
                }
                printMetrics(patches, target);
                if (isSurfaceCaptureFrame() && frame - surfaceActivationFrame == 35) {
                    surfaces.verifyNativeAlbedo(captureTarget);
                }
            }
            if (profileMode && readyToFinish()) {
                samplingEndFrame = frame;
                samplingStopped = true;
                timings.stopSampling();
            }
        } catch (Throwable error) { fail(error); }
    }

    private void addAncestors(Set<PageKey> keys, Node node, long sourceRevision) {
        for (Node current = node; current != null; current = current.getParent()) keys.add(key(current, sourceRevision));
    }

    private void schedule(Set<PageKey> wanted) {
        for (PageKey candidate : wanted) {
            if (jobs.size() >= MAX_JOBS) break;
            Request request = residency.request(candidate);
            if (request == null) continue;
            Node node = findNode(candidate);
            require(node != null, "request matches an immutable source node");
            requestedAt.put(candidate, frame);
            int levelDelay = largeRegionMode ? Math.min(node.getLevel(), 2) : node.getLevel();
            int syntheticDelay = validationEnabled
                    ? (candidate.revision != revision ? 8 : 2 + levelDelay * 2)
                    : 0;
            jobs.add(new Job(request, node, candidate.revision,
                    frame + syntheticDelay,
                    candidate.revision == revision ? source : pendingSource,
                    pagePreparation == null ? null : pagePreparation.submit(node, candidate.revision)));
            if (validationEnabled && !injectedCancellation && frame > 15 && node.getLevel() > 0) {
                require(residency.cancel(request), "explicit cancellation accepted");
                injectedCancellation = true;
                cancellations++;
            }
        }
    }

    private Node findNode(PageKey candidate) {
        if (candidate == null) return null;
        HeightfieldHierarchy source = candidate.revision == revision ? hierarchy
                : candidate.revision == pendingRevision ? pendingHierarchy : null;
        if (source == null) return null;
        int cells = source.getCells();
        long spanLong = (long) patchCells * candidate.stride;
        if (candidate.x < 0 || candidate.z < 0 || spanLong <= 0 || spanLong > cells
                || (long) candidate.x + spanLong > cells || (long) candidate.z + spanLong > cells
                || candidate.x % spanLong != 0 || candidate.z % spanLong != 0) return null;
        int targetSpan = (int) spanLong;
        Node node = source.getRoot();
        while (node != null) {
            if (node.getSpan() == targetSpan) {
                return node.getOriginX() == candidate.x && node.getOriginZ() == candidate.z
                        && node.getStride() == candidate.stride ? node : null;
            }
            if (node.getSpan() < targetSpan || node.getChildren().isEmpty()) return null;
            int half = node.getSpan() / 2;
            boolean east = candidate.x >= node.getOriginX() + half;
            boolean north = candidate.z >= node.getOriginZ() + half;
            int child = (north ? 2 : 0) + (east ? 1 : 0);
            node = node.getChildren().get(child);
        }
        return null;
    }

    private void deliverJobs() {
        Iterator<Job> iterator = jobs.iterator();
        while (iterator.hasNext()) {
            Job job = iterator.next();
            if (frame < job.due) continue;
            if (validationEnabled && !injectedPreparation && frame > 5 && job.node.getLevel() > 0) {
                residency.cancel(job.request);
                if (job.future != null) pagePreparation.cancel(job.future);
                injectedPreparation = true;
                preparationFailures++;
                iterator.remove();
                continue;
            }
            if (job.future != null) {
                if (!residency.isPending(job.request)) {
                    pagePreparation.cancel(job.future);
                    staleResults++;
                    iterator.remove();
                    continue;
                }
                if (!job.future.isDone()) continue;
                if (job.payload == null) {
                    try { job.payload = job.future.get(); }
                    catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("interrupted while collecting a terrain page", interrupted);
                    } catch (java.util.concurrent.ExecutionException | java.util.concurrent.CancellationException error) {
                        throw new IllegalStateException("regional terrain page preparation failed", error);
                    }
                }
            }
            boolean accepted = residency.complete(job.request, job.payload == null
                    ? (job.payload = prepare(job.node, job.revision, job.source)) : job.payload);
            if (accepted || !residency.isPending(job.request)) {
                if (!accepted) staleResults++;
                iterator.remove();
            }
        }
    }

    private void uploadPrepared(boolean inject) {
        try {
            uploads += residency.uploadPrepared((slot, payload) -> {
                uploadImage(atlas, payload.createImage(), slot);
                if (inject && !injectedUpload && frame > 20) {
                    injectedUpload = true;
                    uploadFailures++;
                    throw new InjectedUploadFailure();
                }
                if (sourceGradientMode) uploadImage(gradientAtlas, payload.createGradientImage(), slot);
                // Only returning after both writes publishes the shared slot.
            });
        } catch (InjectedUploadFailure expected) {
            // A physical write happened, but its mapping was not published.
        }
        Iterator<Map.Entry<PageKey, Integer>> iterator = requestedAt.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<PageKey, Integer> entry = iterator.next();
            if (residency.ready(entry.getKey()) != null) {
                publicationLatency.add(frame - entry.getValue());
                iterator.remove();
            } else if (frame - entry.getValue() > 200) iterator.remove();
        }
    }

    private void uploadImage(TextureArray target, Image image, int slot) {
        try { renderer.modifyTexture(target, image, slot, 0, 0); }
        finally {
            // The client data was consumed; this temporary image was not registered.
            BufferUtils.destroyDirectBuffer(image.getData(0));
        }
    }

    private void beginEdit() {
        if (revisionPreparation != null) {
            requestRegionalRevision(++requestedRevision);
            return;
        }
        pendingRevision = revision + 1;
        if (largeRegionMode) {
            pendingSource = new RegionalHeightfieldSource(regionCells, pendingRevision);
            pendingHierarchy = measureRegion(pendingSource, pendingRevision);
        } else {
            pendingPage = makePage(pendingRevision);
            pendingSource = pendingPage;
            pendingHierarchy = new HeightfieldHierarchy(pendingPage, patchCells, 0);
        }
        if (surfaces != null) surfaces.requestRevision(pendingRevision);
        // Admission is retried by schedule(), with this root ahead of ordinary
        // refinement. The pinned old cover remains visible under queue pressure.
    }

    private void requestRegionalRevision(long sourceRevision) {
        // A staged root has never been visible or pinned. Retire it without
        // disturbing the currently published cover or its in-flight leases.
        if (pendingHierarchy != null) {
            PageKey abandoned = key(pendingHierarchy.getRoot(), pendingRevision);
            residency.cancelPrepared(abandoned);
            residency.evict(abandoned);
            obsolete.add(abandoned);
            for (Job job : jobs) {
                if (job.revision == pendingRevision) residency.cancel(job.request);
            }
            requestedAt.keySet().removeIf(key -> key.revision == pendingRevision);
            pendingHierarchy = null;
            pendingSource = null;
        }
        requestedRevision = sourceRevision;
        revisionPreparation.request(sourceRevision);
        if (surfaces != null) surfaces.requestRevision(sourceRevision);
        revisionCapture = "region-request-r" + sourceRevision;
        System.out.println("HEIGHTFIELD_REGION_REQUEST frame=" + frame + " revision=" + sourceRevision);
    }

    private void pollRegionalRevision() {
        if (revisionPreparation == null) return;
        if (revisionPreparation.hasPendingWork()) preparingFrames++;
        RegionalRevisionPreparation.Outcome outcome = revisionPreparation.poll();
        if (outcome == null) return;
        require(outcome.revision == requestedRevision, "only the latest requested source may be staged");
        if (!outcome.isSuccess()) {
            revisionFailures++;
            System.out.println("HEIGHTFIELD_REGION_PREPARATION_FAILED frame=" + frame
                    + " revision=" + outcome.revision + " visibleRevision=" + revision
                    + " reason=" + outcome.failure);
            require(!revisionStress || revision == 0, "failed preparation preserves the original cover");
            if (initialMetadataPending && outcome.revision == revision) {
                throw new IllegalStateException("Initial regional metadata preparation failed", outcome.failure);
            }
            if (revisionStress && outcome.revision == 2) requestRegionalRevision(3);
            return;
        }
        // Future completion transfers the immutable function, its thread-confined
        // cache and metadata to this owner. No worker touches them from here on.
        if (initialMetadataPending && outcome.revision == revision) {
            PageKey oldRoot = key(hierarchy.getRoot(), revision);
            require(oldRoot.equals(key(outcome.prepared.hierarchy.getRoot(), revision)),
                    "measured metadata preserves the canonical bootstrap root identity");
            HeightfieldPage rootPayload = residency.get(residency.ready(oldRoot));
            HeightfieldSource reference = outcome.prepared.source.pageSamples(hierarchy.getRoot().getStride());
            for (int z : new int[]{0, patchCells / 2, patchCells}) {
                for (int x : new int[]{0, patchCells / 2, patchCells}) {
                    require(rootPayload.getHeight(x, z) == reference.getHeight(
                            x * hierarchy.getRoot().getStride(), z * hierarchy.getRoot().getStride()),
                            "bootstrap publication keeps the same root heights");
                }
            }
            source = outcome.prepared.source;
            hierarchy = outcome.prepared.hierarchy;
            completeInitialMetadata();
            rebuildScene();
            revisionCapture = "region-initial-metadata-ready";
            System.out.printf(Locale.ROOT,
                    "HEIGHTFIELD_REGION_INITIAL_READY frame=%d cells=%d nodes=%d constructionMs=%.3f%n",
                    frame, regionCells, hierarchy.getNodes().size(), outcome.prepared.buildNanos / 1e6);
            return;
        }
        pendingSource = outcome.prepared.source;
        pendingHierarchy = outcome.prepared.hierarchy;
        pendingRevision = outcome.revision;
        System.out.printf(Locale.ROOT,
                "HEIGHTFIELD_REGION_PREPARED frame=%d revision=%d constructionMs=%.3f visibleRevision=%d%n",
                frame, pendingRevision, outcome.prepared.buildNanos / 1e6, revision);
    }

    private void completeInitialMetadata() {
        if (!initialMetadataPending) return;
        initialMetadataPending = false;
        initialMetadataCompletedFrame = frame;
        pressureStartFrame = Math.max(150, frame + 30);
        pressureEndFrame = pressureStartFrame + pressureFrameCount;
    }

    private boolean readyToFinish() {
        return !initialMetadataPending && frame >= MAX_FRAMES
                && (regionCells == 1024 || recreations == 1 && !retiringStorage)
                && (revisionPreparation == null
                || edits == 1 && pendingHierarchy == null && !revisionPreparation.hasPendingWork()
                && frame >= publishedFrame + 120)
                && (surfaces == null || recreations == 1 && surfaceActivationFrame >= 0
                && surfaces.isRevisionReady(revision) && frame >= surfaceActivationFrame + (regionCells > 1024 ? 200 : 120))
                && (!largeRegionMode || patchCells == DEFAULT_PATCH_CELLS
                || !currentSelection.isReadinessLimited() && matchesDesiredCover(currentSelection));
    }

    private void publishGpuEdit() {
        if (pendingHierarchy == null || gpuFeedbackPending
                || residency.ready(key(pendingHierarchy.getRoot(), pendingRevision)) == null) return;
        GpuResourceBundle replacement;
        try {
            replacement = createGpuResources(pendingHierarchy, atlas, gradientAtlas);
        } catch (java.io.IOException error) {
            throw new IllegalStateException("Could not prepare GPU resources for edited hierarchy", error);
        }
        if (!residency.pin(key(pendingHierarchy.getRoot(), pendingRevision))) {
            replacement.close(renderer);
            require(false, "new coherent GPU revision root pinned");
        }
        HeightfieldHierarchy old = hierarchy;
        long oldRevision = revision;
        // The previous frame's draw and selection writes precede this fence.
        // Keep their native resources alive while the new immutable hierarchy is installed.
        retireGpuResources();
        hierarchy = pendingHierarchy;
        page = pendingPage;
        source = pendingSource;
        pendingSource = null;
        pendingHierarchy = null;
        pendingPage = null;
        revision = pendingRevision;
        publishedFrame = frame;
        gpuCurrentCover.clear();
        gpuCurrentCover.add(hierarchy.getRoot());
        pendingOracleNodes = Collections.emptyList();
        rebuildScene();
        activateGpuResources(replacement);
        residency.unpin(key(old.getRoot(), oldRevision));
        for (Node node : old.getNodes()) obsolete.add(key(node, oldRevision));
        for (Job job : jobs) if (job.revision == oldRevision) residency.cancel(job.request);
        requestedAt.keySet().removeIf(oldKey -> oldKey.revision == oldRevision);
        edits++;
        System.out.println("HEIGHTFIELD_STREAM_EDIT frame=" + frame + " revision=" + revision);
    }

    private void publishEdit() {
        if (pendingHierarchy == null || residency.ready(key(pendingHierarchy.getRoot(), pendingRevision)) == null
                || surfaces != null && !surfaces.isRevisionReady(pendingRevision)) return;
        require(residency.pin(key(pendingHierarchy.getRoot(), pendingRevision)), "new coherent revision root pinned");
        HeightfieldHierarchy old = hierarchy;
        long oldRevision = revision;
        residency.unpin(key(old.getRoot(), oldRevision));
        hierarchy = pendingHierarchy;
        page = pendingPage;
        source = pendingSource;
        pendingSource = null;
        pendingHierarchy = null;
        pendingPage = null;
        revision = pendingRevision;
        publishedFrame = frame;
        completeInitialMetadata();
        rebuildScene();
        for (Node node : old.getNodes()) obsolete.add(key(node, oldRevision));
        // Old completions remain bounded in jobs and are rejected by cancellation.
        for (Job job : jobs) if (job.revision == oldRevision) residency.cancel(job.request);
        requestedAt.keySet().removeIf(oldKey -> oldKey.revision == oldRevision);
        edits++;
        if (surfaces != null) {
            surfaceActivationFrame = frame;
            surfaceActivations++;
        }
        if (revisionPreparation != null) revisionCapture = "region-published-r" + revision;
        System.out.println("HEIGHTFIELD_STREAM_EDIT frame=" + frame + " revision=" + revision);
    }

    private void recreate() {
        // Storage replacement on the same command stream is a recreation test,
        // not a simulated or actual device/context-loss proof. Deleting the old
        // image never aliases new storage. All prior atlas reads are proved done
        // before replacing it, so whole retired arrays cannot escape our budget.
        require(residency.stats().frames == 0, "old storage leases drained before replacement");
        HeightfieldPage replayRoot = residency.get(residency.ready(key(hierarchy.getRoot(), revision)));
        discardAtlas();
        if (gradientAtlas != null) discardAtlas(gradientAtlas);
        residency.reset();
        if (surfaces != null) {
            surfaces.recreate(revision);
            surfaceActivationFrame = -1;
        }
        obsolete.clear();
        atlas = createAtlas();
        if (sourceGradientMode) gradientAtlas = createGradientAtlas();
        installRoot(hierarchy, revision, replayRoot);
        rebuildScene();
        recreations++;
        System.out.println("HEIGHTFIELD_STREAM_RECREATE frame=" + frame);
    }

    private void beginGpuRecreationDrain() {
        if (gpuRecreationDraining) return;
        retiringStorage = true;
        gpuRecreationDraining = true;
        if (gpuGeometry != null) rootNode.detachChild(gpuGeometry);
        // No more GPU selection, terrain draws, or frame leases are submitted after this point.
        gpuRecreationFence = renderer.createFence();
    }

    private void finishGpuRecreation() {
        require(gpuRecreationDraining && !gpuFeedbackPending && residency.stats().frames == 0
                && retiredGpuResources.isEmpty(), "all old GPU and page users drained before atlas replacement");
        TextureArray replacementAtlas = createAtlas();
        TextureArray replacementGradients = sourceGradientMode ? createGradientAtlas() : null;
        GpuResourceBundle replacement;
        try {
            replacement = createGpuResources(hierarchy, replacementAtlas, replacementGradients);
        } catch (java.io.IOException | RuntimeException error) {
            for (ByteBuffer data : replacementAtlas.getImage().getData()) {
                BufferUtils.destroyDirectBuffer(data);
            }
            if (replacementGradients != null) {
                for (ByteBuffer data : replacementGradients.getImage().getData()) {
                    BufferUtils.destroyDirectBuffer(data);
                }
            }
            throw new IllegalStateException("Could not prepare replacement atlas GPU resources", error);
        }
        renderer.deleteFence(gpuRecreationFence);
        gpuRecreationFence = null;
        closeActiveGpuResources();
        for (Job job : jobs) residency.cancel(job.request);
        jobs.clear();
        requestedAt.clear();
        discardAtlas(atlas);
        if (gradientAtlas != null) discardAtlas(gradientAtlas);
        residency.reset();
        obsolete.clear();
        atlas = replacementAtlas;
        gradientAtlas = replacementGradients;
        installRoot(hierarchy, revision);
        rebuildScene();
        activateGpuResources(replacement);
        gpuCurrentCover.clear();
        gpuCurrentCover.add(hierarchy.getRoot());
        recreations++;
        gpuRecreationDraining = false;
        retiringStorage = false;
        System.out.println("HEIGHTFIELD_STREAM_RECREATE frame=" + frame);
    }

    private void discardAtlas() {
        discardAtlas(atlas);
    }

    private void discardAtlas(TextureArray discarded) {
        renderer.deleteImage(discarded.getImage());
        for (ByteBuffer data : discarded.getImage().getData()) BufferUtils.destroyDirectBuffer(data);
    }

    private double requestedPixels(int f) {
        return f < 31 || (f >= 210 && f < 270) || (f >= 460 && f < 500) ? 1000 : PIXEL_TARGET;
    }

    private void syncDrawSet(List<Patch> patches) {
        if (batchTerrain) {
            terrainBatches.update(patches, node -> {
                Handle handle = residency.ready(key(node, revision));
                require(handle != null && residency.isRetained(handle), "batch references a retained slot generation");
                return handle.getSlot();
            }, material -> { if (surfaces != null) surfaces.bind(material); });
            displayed.clear();
            for (Patch patch : patches) displayed.add(patch.getNode());
            peakBatchDraws = Math.max(peakBatchDraws, terrainBatches.getDrawCount());
            peakActiveGeometry = Math.max(peakActiveGeometry, terrainBatches.getDrawCount());
            return;
        }
        Set<Node> next = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Patch patch : patches) next.add(patch.getNode());
        if (largeRegionMode) {
            for (Node prior : displayed) if (!next.contains(prior)) {
                Geometry old = geometryByNode.remove(prior);
                detachTerrain(old);
                spareGeometry.add(old);
            }
        }
        for (Patch patch : patches) {
            Node node = patch.getNode();
            if (largeRegionMode && !geometryByNode.containsKey(node)) {
                Geometry geometry = spareGeometry.isEmpty() ? createPatchGeometry(node)
                        : spareGeometry.remove(spareGeometry.size() - 1);
                configurePatchGeometry(geometry, node);
                geometryByNode.put(node, geometry);
            }
            Handle handle = residency.ready(key(node, revision));
            require(handle != null && residency.isRetained(handle), "draw references a retained slot generation");
            Material material = geometryByNode.get(node).getMaterial();
            if (surfaces != null) surfaces.bind(material);
            material.setInt("HeightLayer", handle.getSlot());
            material.setFloat("InteriorMorph", patch.getMorph());
            material.setFloat("EdgeMorphWest", patch.getEdgeMorph(0));
            material.setFloat("EdgeMorphEast", patch.getEdgeMorph(1));
            material.setFloat("EdgeMorphSouth", patch.getEdgeMorph(2));
            material.setFloat("EdgeMorphNorth", patch.getEdgeMorph(3));
            if (!displayed.contains(node)) {
                Geometry geometry = geometryByNode.get(node);
                attachTerrain(geometry);
                if (largeRegionMode) {
                    Vector3f parentOrigin = terrainParent.getLocalTranslation();
                    float expectedOrigin = ((RegionalHeightfieldSource) source).getWorldOrigin();
                    require(geometry.getParent() == terrainParent
                                    && parentOrigin.x == expectedOrigin && parentOrigin.z == expectedOrigin,
                            "regional geometry is attached under its world-translated terrain parent");
                }
            }
        }
        if (!largeRegionMode) for (Node prior : displayed) if (!next.contains(prior)) {
            detachTerrain(geometryByNode.get(prior));
        }
        displayed.clear();
        displayed.addAll(next);
        peakActiveGeometry = Math.max(peakActiveGeometry, next.size());
    }

    private void attachTerrain(Geometry geometry) {
        if (largeRegionMode) terrainParent.attachChild(geometry);
        else rootNode.attachChild(geometry);
    }

    private void detachTerrain(Geometry geometry) {
        if (geometry == null) return;
        if (largeRegionMode) terrainParent.detachChild(geometry);
        else rootNode.detachChild(geometry);
    }

    private Camera terrainCamera() {
        return regionCells == RegionalHeightfieldSource.FAR_CELLS ? selectorCamera : cam;
    }

    private void updateSelectorCamera() {
        selectorCamera.copyFrom(cam);
        float worldOrigin = ((RegionalHeightfieldSource) source).getWorldOrigin();
        selectorLocation.set(cam.getLocation()).subtractLocal(worldOrigin, 0, worldOrigin);
        selectorCamera.setLocation(selectorLocation);
    }

    private void convergeOracle(Selection desired) {
        int maximumSteps = hierarchy.getNodes().size() * 2 + 8;
        for (int step = 0; step < maximumSteps; step++) {
            if (matchesDesiredCover(desired)) return;
            transition.advance(desired, 1f);
        }
        require(matchesDesiredCover(desired),
                "CPU oracle transition converges to the GPU selector's immediate desired cover");
    }

    private boolean matchesDesiredCover(Selection desired) {
        if (transition.isTransitioning()) return false;
        List<Patch> actual = transition.getPatches();
        if (actual.size() != desired.getLeaves().size()) return false;
        Set<Node> expected = Collections.newSetFromMap(new IdentityHashMap<>());
        expected.addAll(desired.getLeaves());
        for (Patch patch : actual) if (!expected.remove(patch.getNode())) return false;
        return expected.isEmpty();
    }

    private List<Node> oracleNodes(List<Patch> patches) {
        List<Node> result = new ArrayList<>(patches.size());
        for (Patch patch : patches) result.add(patch.getNode());
        List<Node> hierarchyNodes = hierarchy.getNodes();
        Map<Node, Integer> order = new IdentityHashMap<>();
        for (int i = 0; i < hierarchyNodes.size(); i++) order.put(hierarchyNodes.get(i), i);
        Collections.sort(result, (a, b) -> Integer.compare(order.get(a), order.get(b)));
        return result;
    }

    private void pollGpuFeedback() {
        if (!gpuFeedbackPending || frame < feedbackNotBeforeFrame) return;
        HeightfieldGpuCoverFeedback.Snapshot snapshot = gpuFeedback.poll();
        if (snapshot == null) {
            feedbackNativePendingPolls++;
            return;
        }
        require(snapshot.getSequence() == pendingFeedbackSequence,
                "GPU feedback sequence matches the submitted transition dispatch");
        require(snapshot.getRevisionLabel() == (int) revision,
                "GPU feedback source revision matches the immutable hierarchy");
        require(snapshot.getLeafCount() == pendingOracleNodes.size(),
                "GPU feedback leaf count matches the CPU transition oracle");
        Set<Node> expected = Collections.newSetFromMap(new IdentityHashMap<>());
        expected.addAll(pendingOracleNodes);
        gpuCurrentCover.clear();
        for (Node node : snapshot.getNodes()) {
            require(expected.remove(node), "GPU feedback leaf matches CPU transition oracle");
            gpuCurrentCover.add(node);
        }
        require(expected.isEmpty(), "GPU feedback contains the complete CPU oracle cover");
        gpuFeedbackPending = false;
        feedbackReads++;
    }

    private String timingPhase(int frame) {
        if (initialMetadataPending) return "region-initial-metadata";
        if (surfaces != null && regionCells > 1024 && surfaceActivationFrame >= 0
                && frame - surfaceActivationFrame >= 125 && frame - surfaceActivationFrame < 170) return "region-distant";
        if (surfaces != null && !surfaces.isRevisionReady(revision)) return "region-surface-fallback";
        if (surfaces != null && requestedRevision != revision) return "region-surface-edit-prepare";
        if (largeRegionMode) {
            if (frame >= pressureStartFrame && frame < pressureEndFrame) return "region-pressure";
            if (frame < 61 || frame >= 210 && frame < 291 || frame >= 460 && frame < 501) return "region-overview";
            return frame < 210 ? "region-moving" : "region-close";
        }
        if (frame < 31) return "overview";
        if (frame < 211) return "valley";
        if (frame < 291) return "overview";
        if (frame < 391) return "close";
        if (frame < 501) return "overhead";
        return "overview";
    }

    private void recordStage(List<Long> samples, long elapsedNanos, boolean applicable) {
        if (profileMode && applicable && frame > PROFILE_WARMUP && !samplingStopped
                && samples.size() < MAX_PROFILE_STAGE_SAMPLES) {
            samples.add(elapsedNanos);
        }
    }

    private void writeProfileStageSummary() throws IOException {
        Path summary = output.resolve("heightfield-frame-stage-timings.csv");
        try (BufferedWriter writer = Files.newBufferedWriter(summary, StandardCharsets.UTF_8)) {
            writer.write("stage,samples,p50_ms,p95_ms,p99_ms\n");
            writeStageStats(writer, "feedback_poll_and_cover_validate_cpu", feedbackPollNanos);
            writeStageStats(writer, "job_schedule_prepare_upload_publish_cpu", jobUploadNanos);
            writeStageStats(writer, "page_binding_cpu_oracle_select_transition_cpu", cpuOracleNanos);
            String dispatchStage = gpuTransitionsMode ? "gpu_transition_dispatch_feedback_submit_cpu"
                    : gpuSelectionMode ? "gpu_selection_dispatch_cpu" : "gpu_dispatch_not_applicable";
            writeStageStats(writer, dispatchStage, gpuDispatchNanos);
        }
    }

    private static void writeStageStats(BufferedWriter writer, String name, List<Long> values) throws IOException {
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        writer.write(name + "," + sorted.size() + ","
                + stagePercentile(sorted, .50) + "," + stagePercentile(sorted, .95) + ","
                + stagePercentile(sorted, .99) + "\n");
    }

    private static String stagePercentile(List<Long> sorted, double percentile) {
        if (sorted.isEmpty()) return "n/a";
        int index = (int) Math.ceil(percentile * sorted.size()) - 1;
        return String.format(Locale.ROOT, "%.3f", sorted.get(Math.max(0, index)) / 1_000_000.0);
    }

    private void verifyGpuTransitionRecords(List<Patch> patches)
            throws java.io.IOException, com.jme3.renderer.TextureUnitException {
        List<Node> nodes = hierarchy.getNodes();
        Map<Node, Integer> order = new IdentityHashMap<>();
        for (int i = 0; i < nodes.size(); i++) order.put(nodes.get(i), i);
        List<Patch> ordered = new ArrayList<>(patches);
        Collections.sort(ordered, (a, b) -> Integer.compare(order.get(a.getNode()), order.get(b.getNode())));
        HeightfieldLodGpuProbe.Verification result = gpuProbe.verifyPageMorphRecords(ordered,
                gpuTransitions.getPatchRecords(), gpuTransitions.getCommand(), 0,
                gpuPageBindings.getBuffer(), atlas, captureTarget);
        comparedChannels += result.getComparedChannels();
        maximumGpuError = Math.max(maximumGpuError, result.getMaximumAbsoluteError());
        if (frame == 350 || frame == 530 || sourceGradientMode && (frame == 120 || frame == 215)) {
            try (HeightfieldMorphRenderProbe spatial = new HeightfieldMorphRenderProbe(
                    assetManager, renderManager, renderer, hierarchy, gpuTransitions)) {
                spatial.verifyNodePages(cam, ordered, gpuPageBindings, atlas,
                        gradientAtlas, "stream-depth-f" + frame, output, captureTarget);
            }
        }
    }

    private void verifyGpuPageRecords(List<Patch> patches) {
        List<Node> nodes = hierarchy.getNodes();
        Map<Node, Integer> order = new IdentityHashMap<>();
        for (int i = 0; i < nodes.size(); i++) order.put(nodes.get(i), i);
        List<Patch> ordered = new ArrayList<>(patches);
        Collections.sort(ordered, (a, b) -> Integer.compare(order.get(a.getNode()), order.get(b.getNode())));
        HeightfieldLodGpuProbe.Verification result = gpuProbe.verifyPageRecords(ordered,
                gpuSelection.getPatchRecords(), gpuSelection.getCommand(), 0, gpuPageBindings.getBuffer(), atlas, captureTarget);
        comparedChannels += result.getComparedChannels();
        maximumGpuError = Math.max(maximumGpuError, result.getMaximumAbsoluteError());
    }

    private void sampleBudgets() {
        Stats stats = residency.stats();
        peakRequests = Math.max(peakRequests, stats.requests);
        peakQueuedBytes = Math.max(peakQueuedBytes, stats.queuedBytes);
        peakPreparedBytes = Math.max(peakPreparedBytes, stats.preparedBytes);
        peakReady = Math.max(peakReady, stats.readySlots);
        peakRetired = Math.max(peakRetired, stats.retiredSlots);
        peakFrames = Math.max(peakFrames, stats.frames);
        if (stats.readySlots + stats.retiredSlots == capacity) fullAtlasFrames++;
        require(stats.requests <= MAX_JOBS && stats.queuedBytes <= MAX_JOBS * pageBytes
                && stats.preparedBytes <= 6L * pageBytes && stats.retiredSlots <= 12
                && stats.readySlots + stats.retiredSlots <= capacity && stats.frames <= 12
                && jobs.size() <= MAX_JOBS, "independent residency/preparation/frame budgets hold");
        require(residency.ready(key(hierarchy.getRoot(), revision)) != null, "root fallback remains ready");
        if (surfaces != null) surfaces.assertBounds();
        if (pagePreparation != null) pagePreparation.assertBounds();
    }

    private void verifyFrame(List<Patch> patches) {
        int[] coverage = largeRegionMode ? null : new int[CELLS * CELLS];
        long area = 0;
        boolean verifySamples = !largeRegionMode || output != null && !profileMode && isCaptureFrame(frame);
        Set<Node> sampleNodes = Collections.newSetFromMap(new IdentityHashMap<>());
        if (verifySamples) for (Patch patch : verificationPatches(patches)) sampleNodes.add(patch.getNode());
        for (Patch patch : patches) {
            Node node = patch.getNode();
            Handle handle = residency.ready(key(node, revision));
            require(handle != null, "all cover pages share current ready revision");
            HeightfieldPage payload = residency.get(handle);
            HeightfieldSource reference = largeRegionMode && patchCells > DEFAULT_PATCH_CELLS && node.getStride() > 2
                    ? ((RegionalHeightfieldSource) source).pageSamples(node.getStride()) : source;
            if (sampleNodes.contains(node)) for (int z = 0; z <= patchCells; z++) for (int x = 0; x <= patchCells; x++) {
                require(payload.getHeight(x, z) == reference.getHeight(node.getOriginX() + x * node.getStride(),
                        node.getOriginZ() + z * node.getStride()), "node payload retains exact canonical samples");
                if (sourceGradientMode) {
                    int gx = node.getOriginX() + x * node.getStride();
                    int gz = node.getOriginZ() + z * node.getStride();
                    require(payload.getSourceGradientX(x, z) == reference.getHeight(gx - 1, gz) * .5f
                            - reference.getHeight(gx + 1, gz) * .5f
                            && payload.getSourceGradientZ(x, z) == reference.getHeight(gx, gz - 1) * .5f
                            - reference.getHeight(gx, gz + 1) * .5f,
                            "height and gradient payloads share the canonical revision");
                }
            }
            if (largeRegionMode) {
                require(node.getOriginX() >= 0 && node.getOriginZ() >= 0
                        && node.getOriginX() + node.getSpan() <= source.getCells()
                        && node.getOriginZ() + node.getSpan() <= source.getCells(), "patch remains in the source");
                area += (long) node.getSpan() * node.getSpan();
                for (Patch other : patches) {
                    Node b = other.getNode();
                    if (b == node) continue;
                    boolean overlap = Math.max(node.getOriginX(), b.getOriginX())
                            < Math.min(node.getOriginX() + node.getSpan(), b.getOriginX() + b.getSpan())
                            && Math.max(node.getOriginZ(), b.getOriginZ())
                            < Math.min(node.getOriginZ() + node.getSpan(), b.getOriginZ() + b.getSpan());
                    require(!overlap, "regional cover rectangles do not overlap");
                }
            } else for (int z = node.getOriginZ(); z < node.getOriginZ() + node.getSpan(); z++) {
                for (int x = node.getOriginX(); x < node.getOriginX() + node.getSpan(); x++) coverage[z * CELLS + x]++;
            }
        }
        if (largeRegionMode) require(area == (long) source.getCells() * source.getCells(), "regional cover owns the whole source");
        else for (int count : coverage) require(count == 1, "draw cover owns every cell exactly once");
    }

    private void printMetrics(List<Patch> patches, double target) {
        String mode = gpuTransitionsMode ? "gpu-transitions" : gpuSelectionMode ? "gpu-selection" : "cpu";
        System.out.println("HEIGHTFIELD_STREAM frame=" + frame + " revision=" + revision
                + " mode=" + mode + " largeRegion=" + largeRegionMode
                + " cpuOraclePatches=" + patches.size() + " target=" + target
                + " cpuOraclePixels=" + currentSelection.getMaximumPixels()
                + " drawPixels=" + transition.getMaximumPixels(terrainCamera())
                + " patchCells=" + patchCells + " patchBudget=" + patchBudget
                + (gpuSelectionMode ? " gpuInstanceCount=GPU-owned" : "")
                + " budgetLimited=" + currentSelection.isBudgetLimited()
                + " readinessLimited=" + currentSelection.isReadinessLimited()
                + " sourceLimited=" + currentSelection.isSourceLimited()
                + " ready=" + residency.stats().readySlots
                + " retired=" + residency.stats().retiredSlots
                + " pending=" + residency.stats().requests
                + " prepared=" + residency.stats().preparedPages
                + " gpuChannels=" + comparedChannels
                + " gpuError=" + maximumGpuError);
    }

    private static boolean isCaptureFrame(int f) {
        return f == 1 || f == 45 || f == 120 || f == 215 || f == 280 || f == 328
                || f == 350 || f == 499 || f == 500 || f == 530 || f == 650;
    }
    private List<Patch> verificationPatches(List<Patch> patches) {
        if (patchCells == DEFAULT_PATCH_CELLS || patches.size() <= 8) return patches;
        List<Patch> selected = new ArrayList<>(8);
        Set<Integer> levels = new LinkedHashSet<>();
        for (Patch patch : patches) if (levels.add(patch.getNode().getLevel())) selected.add(patch);
        for (int i = 0; selected.size() < 8 && i < patches.size(); i++) {
            Patch patch = patches.get(i * patches.size() / 8);
            if (!selected.contains(patch)) selected.add(patch);
        }
        return selected;
    }

    private boolean isFinalDetailFrame() {
        return largeRegionMode && patchCells > DEFAULT_PATCH_CELLS && readyToFinish();
    }

    private boolean isSurfaceCaptureFrame() {
        if (surfaces == null || surfaceActivationFrame < 0) return false;
        int elapsed = frame - surfaceActivationFrame;
        return elapsed == 35 || elapsed == 75 || elapsed == 115 || regionCells > 1024 && elapsed == 155;
    }

    private String captureLabel(int f) {
        if (revisionCapture != null) {
            String label = revisionCapture;
            revisionCapture = null;
            return label;
        }
        if (isSurfaceCaptureFrame()) {
            int elapsed = f - surfaceActivationFrame;
            return "surface-r" + revision + "-g" + recreations
                    + (elapsed == 35 ? "-overview" : elapsed == 75 ? "-moving" : elapsed == 155 ? "-distant" : "-close");
        }
        if (revisionPreparation != null && publishedFrame > 0 && f == publishedFrame + 120) {
            return "region-settled-r" + revision;
        }
        if (isFinalDetailFrame()) return "region-detail-final";
        return isCaptureFrame(f) ? "stream-f" + String.format("%03d", f) : null;
    }

    private void finish() {
        if (asynchronousInitialMetadata) {
            require(initialPreparingFrames > 0 && initialMetadataCompletedFrame > 0 && !initialMetadataPending,
                    "initial conservative cover renders while metadata prepares");
            System.out.println("HEIGHTFIELD_REGION_STARTUP_PASS cells=" + regionCells
                    + " coarseFrames=" + initialPreparingFrames
                    + " initialMetadataCompletedFrame=" + initialMetadataCompletedFrame);
        }
        if (batchTerrain) {
            System.out.println("HEIGHTFIELD_CPU_BATCH_PASS patches=" + displayed.size()
                    + " draws=" + terrainBatches.getDrawCount() + " peakDraws=" + peakBatchDraws
                    + " allocatedBatches=" + terrainBatches.getAllocatedCount()
                    + " ownedBufferCapacityBytes=" + terrainBatches.getBufferCapacityBytes());
        }
        if (forest != null) {
            require(forest.getRevision() == revision
                            && (forest.getSiteCount() == 0 || forest.getInstanceCount() > 0),
                    "forest roots follow the published terrain revision");
            forest.printStats();
        }
        if (pagePreparation != null) pagePreparation.printStats();
        if (largeRegionMode && patchCells > DEFAULT_PATCH_CELLS) {
            require(!currentSelection.isReadinessLimited() && matchesDesiredCover(currentSelection),
                    "dense terrain finished the requested ready cover and morphs");
            require(transition.getMaximumPixels(terrainCamera()) <= PIXEL_TARGET
                    && !currentSelection.isBudgetLimited() && !currentSelection.isSourceLimited(),
                    "settled dense side view meets the requested projected error");
            System.out.println("HEIGHTFIELD_REGION_DETAIL_PASS patchCells=" + patchCells
                    + " patchBudget=" + patchBudget + " drawPatches=" + transition.getPatches().size()
                    + " maximumPixels=" + transition.getMaximumPixels(terrainCamera())
                    + " targetPixels=" + PIXEL_TARGET + " budgetLimited=" + currentSelection.isBudgetLimited()
                    + " sourceLimited=" + currentSelection.isSourceLimited());
        }
        if (surfaces != null) {
            require(surfaceActivations >= 3 && surfaces.isRevisionReady(revision),
                    "surfaces activated coherently before and after edit and storage recreation");
            surfaces.printStats();
            System.out.println("HEIGHTFIELD_SURFACE_PASS activations=" + surfaceActivations
                    + " fallbackFrames=" + surfaceFallbackFrames + " revision=" + revision);
        }
        if (revisionPreparation != null) {
            require(preparingFrames > 0 && !revisionPreparation.hasPendingWork()
                    && pendingHierarchy == null && revision == (revisionStress ? 3 : 1)
                    && revisionFailures == (revisionStress ? 1 : 0)
                    && (!revisionStress || revisionPreparation.getSupersededBuilds() > 0),
                    "async revision preparation preserves cover through replacement, cancellation and failure");
            System.out.println("HEIGHTFIELD_REGION_ASYNC_PASS preparingFrames=" + preparingFrames
                    + " startedBuilds=" + revisionPreparation.getStartedBuilds()
                    + " supersededBuilds=" + revisionPreparation.getSupersededBuilds()
                    + " failures=" + revisionFailures + " publishedFrame=" + publishedFrame
                    + " visibleRevision=" + revision);
        }
        boolean sourceLifecyclePassed = gpuTransitionsMode
                ? edits == 1 && recreations == 1 && revision == 1
                : gpuSelectionMode ? edits == 0 && recreations == 0 && revision == 0
                : edits == 1 && recreations == 1;
        require(preparationFailures == 1 && uploadFailures == 1 && cancellations == 1
                && staleResults > 0 && sourceLifecyclePassed
                && peakRetired > 0 && peakFrames > 1 && waitingFrames > 0 && fenceSignaled > 0
                && (!gpuTransitionsMode || peakRetiredGpuBundles > 0 && retiredGpuBundlesClosed > 0)
                && fullAtlasFrames > 0 && (!gpuTransitionsMode || feedbackReads > 0 && !gpuFeedbackPending)
                && (!profileMode || profileWritten),
                "finite control exercised failures, cancellation, stale work, pressure and fences"
                        + " preparationFailures=" + preparationFailures + " uploadFailures=" + uploadFailures
                        + " cancellations=" + cancellations + " stale=" + staleResults
                        + " lifecycle=" + sourceLifecyclePassed + " retired=" + peakRetired
                        + " frames=" + peakFrames + " waiting=" + waitingFrames
                        + " fenceSignaled=" + fenceSignaled + " fullAtlas=" + fullAtlasFrames + " peakReady=" + peakReady);
        Collections.sort(publicationLatency);
        int p50 = percentile(.5), p95 = percentile(.95), maximum = percentile(1);
        String mode = gpuTransitionsMode ? "gpu-transitions" : gpuSelectionMode ? "gpu-selection" : "cpu";
        System.out.println((profileMode ? "HEIGHTFIELD_STREAM_PROFILE_PASS"
                : gpuTransitionsMode ? "HEIGHTFIELD_STREAM_GPU_TRANSITIONS_PASS"
                : gpuSelectionMode ? "HEIGHTFIELD_STREAM_GPU_SELECTION_PASS" : "HEIGHTFIELD_STREAM_PASS")
                + " mode=" + mode + " largeRegion=" + largeRegionMode
                + " frames=" + frame + " checks=" + checks
                + (profileMode ? " timingWarmup=" + PROFILE_WARMUP
                + " timingSamples=" + Math.min(MAX_PROFILE_STAGE_SAMPLES, samplingEndFrame - PROFILE_WARMUP)
                + " timedChecks=cpu-oracle-cover-and-residency-invariants"
                + " diagnosticGpuReadback=disabled pngCapture=disabled artifacts=csv-only" : "")
                + " uploads=" + uploads + " stale=" + staleResults
                + " preparationFailures=" + preparationFailures + " uploadFailures=" + uploadFailures
                + " peakRequests=" + peakRequests + " peakQueuedBytes=" + peakQueuedBytes
                + " peakPreparedBytes=" + peakPreparedBytes + " peakReady=" + peakReady
                + " peakRetired=" + peakRetired + " peakFrames=" + peakFrames
                + " sourceRevision=" + revision + " edits=" + edits + " recreations=" + recreations
                + " sourceGradients=" + sourceGradientMode + " atlasPayloadBytes=" + (capacity * pageBytes)
                + " publicationFrameLatencyP50=" + p50 + " p95=" + p95 + " max=" + maximum
                + " gpuChannels=" + comparedChannels + " maxGpuError=" + maximumGpuError
                + " waitingFrames=" + waitingFrames + " fullAtlasFrames=" + fullAtlasFrames
                + " nativeFencePolls=" + fencePolls + " nativePending=" + fencePending
                + " nativeSignaled=" + fenceSignaled
                + (gpuTransitionsMode ? " peakRetiredGpuBundles=" + peakRetiredGpuBundles
                + " retiredGpuFencePending=" + retiredGpuFencePending
                + " retiredGpuFenceSignaled=" + retiredGpuFenceSignaled
                + " retiredGpuBundlesClosed=" + retiredGpuBundlesClosed
                + " recreationDrainFrames=" + recreationDrainFrames : "")
                + (gpuTransitionsMode ? " feedbackReads=" + feedbackReads
                + " feedbackNativePendingPolls=" + feedbackNativePendingPolls
                + " feedbackSkippedDispatches=" + feedbackSkippedDispatches
                + " feedbackArtificialDelaySkipped=" + artificialFeedbackDelaySkipped : ""));
        if (largeRegionMode) {
            RegionalHeightfieldSource regional = (RegionalHeightfieldSource) source;
            System.out.println("HEIGHTFIELD_REGION_PASS cells=" + source.getCells()
                    + " nodes=" + hierarchy.getNodes().size() + " peakActiveGeometry=" + peakActiveGeometry
                    + " pooledGeometry=" + createdGeometry + " geometryLimit=" + capacity
                    + " sourceCachedFloatBytes=" + regional.getCachedFloatBytes()
                    + " sourceTransientFloatBytes=" + regional.getPeakTransientFloatBytes()
                    + " sourceTileLoads=" + regional.getTileLoads() + " directSamples=" + regional.getDirectSampleCount());
        }
        if (aerial != null) System.out.printf(Locale.ROOT,
                "HEIGHTFIELD_AERIAL_PASS atlasBytes=%d builds=%d meanSubmitMs=%.3f maxSubmitMs=%.3f%n",
                aerial.getEncodedBytes(), aerial.getBuilds(),
                aerial.getBuildNanos() / 1e6 / Math.max(1, aerial.getBuilds()),
                aerial.getMaximumBuildNanos() / 1e6);
        if (skyView != null) System.out.printf(Locale.ROOT,
                "HEIGHTFIELD_SKY_VIEW_PASS imageBytes=%d builds=%d meanSubmitMs=%.3f maxSubmitMs=%.3f%n",
                skyView.getEncodedBytes(), skyView.getBuilds(),
                skyView.getBuildNanos() / 1e6 / Math.max(1, skyView.getBuilds()),
                skyView.getMaximumBuildNanos() / 1e6);
        completed.countDown();
    }
    private int percentile(double p) {
        return publicationLatency.isEmpty() ? 0
                : publicationLatency.get(Math.max(0, (int) Math.ceil(p * publicationLatency.size()) - 1));
    }
    private void require(boolean condition, String message) {
        checks++;
        if (!condition) throw new IllegalStateException(message + " at frame " + frame);
    }
    private void fail(Throwable error) {
        failure = error;
        error.printStackTrace();
        // The render-thread uncaught handler checks the close flag immediately
        // after this callback. Set it before waking main, which waits for teardown.
        stop(false);
        completed.countDown();
    }
    private static final class InjectedUploadFailure extends RuntimeException { }
    private static final class Job {
        final Request request;
        final Node node;
        final long revision;
        final int due;
        final HeightfieldSource source;
        final java.util.concurrent.Future<HeightfieldPage> future;
        HeightfieldPage payload;
        Job(Request request, Node node, long revision, int due, HeightfieldSource source,
                java.util.concurrent.Future<HeightfieldPage> future) {
            this.request = request; this.node = node; this.revision = revision; this.due = due;
            this.source = source;
            this.future = future;
        }
    }
    private static final class GpuResourceBundle {
        final HeightfieldGpuSelection selection;
        final HeightfieldGpuTransitions transitions;
        final HeightfieldGpuCoverFeedback feedback;
        final HeightfieldGpuPageBindings pageBindings;
        final Mesh patchMesh;
        final Geometry geometry;
        final Material material;
        final GLFence lastUse;

        GpuResourceBundle(HeightfieldGpuSelection selection, HeightfieldGpuTransitions transitions,
                HeightfieldGpuCoverFeedback feedback, HeightfieldGpuPageBindings pageBindings,
                Mesh patchMesh, Geometry geometry, Material material, GLFence lastUse) {
            this.selection = selection;
            this.transitions = transitions;
            this.feedback = feedback;
            this.pageBindings = pageBindings;
            this.patchMesh = patchMesh;
            this.geometry = geometry;
            this.material = material;
            this.lastUse = lastUse;
        }

        void close(com.jme3.renderer.Renderer renderer) {
            if (selection != null) selection.close();
            if (transitions != null) transitions.close();
            if (feedback != null) feedback.close();
            if (pageBindings != null) pageBindings.close();
            if (patchMesh != null) {
                for (com.jme3.scene.VertexBuffer buffer : patchMesh.getBufferList().getArray()) {
                    renderer.deleteBuffer(buffer);
                }
            }
        }
    }
    private final class Completion implements HeightfieldResidency.Completion {
        private final GLFence fence = renderer.createFence();
        private final int release = frame + (validationEnabled ? (frame >= 200 && frame < 280 ? 8 : 3) : 0);
        @Override public boolean isComplete() {
            if (!retiringStorage && frame < release) return false;
            fencePolls++;
            boolean signaled = renderer.isFenceSignaled(fence);
            if (signaled) fenceSignaled++; else fencePending++;
            return signaled;
        }
        @Override public void close() { renderer.deleteFence(fence); }
    }
    private void addLightingAndCapture() {
        if (largeRegionMode) viewPort.setBackgroundColor(new ColorRGBA(.42f, .56f, .70f, 1));
        DirectionalLight sun = new DirectionalLight();
        sun.setDirection(new Vector3f(-.55f, -1f, -.42f).normalizeLocal());
        sun.setColor(new ColorRGBA(1.25f, 1.18f, 1.03f, 1));
        AmbientLight sky = new AmbientLight();
        sky.setColor(new ColorRGBA(.22f, .27f, .34f, 1));
        if (atmosphereLighting) {
            ReferenceAtmosphereSky.requireFloatTextureFiltering(renderer);
            ClearSkyAtmosphere atmosphere = new ClearSkyAtmosphere(sunElevation, 53);
            atmosphereSky = referenceSky ? new ReferenceAtmosphereSky(assetManager, atmosphere)
                    : new GpuAtmosphereSky(assetManager, renderManager, cam, atmosphere, !singleScattering);
            sun.setDirection(atmosphere.getSunDirection(new Vector3f()).negateLocal());
            sun.setColor(atmosphereSky.getSunIrradiance(new ColorRGBA()));
            long projectionStarted = System.nanoTime();
            if (!hemisphereFill) {
                skyIrradiance = HeightfieldSkyIrradiance.fromCubeMap(atmosphereSky.getSkyMap());
                for (Geometry geometry : geometryByNode.values()) skyIrradiance.bind(geometry.getMaterial());
            }
            // Keep meaningful ambient values for any ordinary scene material.
            // Opted-in heightfield materials use physical SH on their first pass.
            sky.setColor(atmosphereSky.getDiffuseFill(new ColorRGBA()));
            System.out.printf(Locale.ROOT,
                    "HEIGHTFIELD_SKY_IRRADIANCE mode=%s coefficientBytes=%d projectionMs=%.3f up=%s%n",
                    hemisphereFill ? "hemisphere" : "SH9", hemisphereFill ? 0 : 9 * 3 * Float.BYTES,
                    (System.nanoTime() - projectionStarted) / 1e6,
                    hemisphereFill ? atmosphereSky.getDiffuseFill(new ColorRGBA())
                            : skyIrradiance.evaluate(Vector3f.UNIT_Y, new ColorRGBA()));
            rootNode.attachChild(atmosphereSky.getSpatial());
            if (skyViewEnabled) {
                skyView = new GpuAtmosphereSkyView(assetManager, (GpuAtmosphereSky) atmosphereSky);
                viewPort.addProcessor(skyView);
            }
            System.out.println("HEIGHTFIELD_ATMOSPHERE elevationDegrees=" + sunElevation
                    + " directIrradiance=" + sun.getColor() + " diffuseFill=" + sky.getColor()
                    + " cubeTexels=" + atmosphereSky.getTexelCount()
                    + " encodedPayloadBytes=" + atmosphereSky.getEncodedBytes()
                    + " generationMs=" + atmosphereSky.getGenerationMillis()
                    + " model=" + (referenceSky ? "cpu-single-scattering" : singleScattering ? "gpu-single-scattering" : "gpu-isotropic-multiple-scattering")
                    + " fixedObserverAltitudeMeters=200");
        }
        rootNode.addLight(sun);
        rootNode.addLight(sky);

        FilterPostProcessor post = new FilterPostProcessor(assetManager);
        post.setFrameBufferFormat(Image.Format.RGBA16F);
        post.setFrameBufferDepthFormat(Image.Format.Depth24);
        if (largeRegionMode) {
            regionalVisibility = new DirectionalLightVisibilityRenderer(assetManager, 2048, 3);
            regionalVisibility.setLight(sun);
            if (regionCells == RegionalHeightfieldSource.FAR_CELLS) {
                // Low sun carries mountain shadows across the visible landscape.
                // Cover the camera range and fade near its far limit.
                regionalVisibility.setShadowZExtend(FAR_VIEW_DISTANCE);
                regionalVisibility.setShadowZFadeLength(FAR_VIEW_DISTANCE * .1f);
            } else {
                regionalVisibility.setShadowZExtend(1400);
            }
            regionalVisibility.setShadowIntensity(1);
            viewPort.addProcessor(regionalVisibility);
        } else {
            DirectionalLightShadowFilter shadows = new DirectionalLightShadowFilter(assetManager, 1024, 3);
            shadows.setLight(sun);
            shadows.setShadowIntensity(.45f);
            post.addFilter(shadows);
        }
        if (forestMode) {
            Material treeMaterial = jme3test.terrain.forest.OriginalConiferMaterial.create(
                    assetManager, sun.getDirection(), sun.getColor(), sky.getColor());
            if (skyIrradiance != null) skyIrradiance.bind(treeMaterial);
            forest = new RegionalConiferForest(renderManager, assetManager, terrainParent, treeMaterial,
                    capacity, forestImpostors, forestSpacing, forestPages, regionalVisibility::addReceiver);
            regionalVisibility.addReceiver(forest.getMaterial());
        }
        if (aerialPerspective) {
            aerial = new AtmosphereAerialPerspectiveFilter((GpuAtmosphereSky) atmosphereSky);
            post.addFilter(aerial);
        }
        post.addFilter(new KHRToneMapFilter());
        post.addFilter(new FXAAFilter());
        viewPort.addProcessor(post);
        if (profileMode) {
            timings = new HeightfieldFrameTimings(renderer, PROFILE_WARMUP);
            // Close intervals after post-processing but before capture readback.
            viewPort.addProcessor(timings);
        }
        if (output != null && !profileMode) {
            FrameBuffer target = new FrameBuffer(cam.getWidth(), cam.getHeight(), 1);
            target.addColorTarget(FrameBuffer.FrameBufferTarget.newTarget(Image.Format.RGBA16F));
            target.setDepthTarget(FrameBuffer.FrameBufferTarget.newTarget(Image.Format.Depth24));
            captureTarget = target;
            viewPort.setOutputFrameBuffer(target);
            viewPort.addProcessor(new Capture(target));
        } else {
            viewPort.addProcessor(new Capture(null));
        }
    }

    /** Jump to an admitted grove without putting the camera inside coarse terrain. */
    private boolean focusForest() {
        if (forest == null || forest.previewFocus(forestFocus) == null) return false;
        float origin = ((RegionalHeightfieldSource) source).getWorldOrigin();
        float end = origin + regionCells * SPACING;
        float x = Math.max(origin + 4, Math.min(end - 4, forestFocus.x + 45));
        float z = Math.max(origin + 4, Math.min(end - 4, forestFocus.z - 65));
        float y = cameraPath.visibleSurfaceHeight(x, z, cameraPath.worldHeight(x, z) + 45);
        cam.setParallelProjection(false);
        cam.setFrustumPerspective(55, (float) cam.getWidth() / cam.getHeight(), .5f,
                regionCells == RegionalHeightfieldSource.FAR_CELLS ? FAR_VIEW_DISTANCE : 2200f);
        cam.setLocation(new Vector3f(x, y, z));
        cam.lookAt(new Vector3f(forestFocus.x, forestFocus.y + 8, forestFocus.z), Vector3f.UNIT_Y);
        return true;
    }

    private final class CameraPath {
        void apply(int f) {
            if (surfaces != null && surfaceActivationFrame >= 0) {
                int elapsed = f - surfaceActivationFrame;
                if (forestMode && regionCells == 2048 && elapsed >= 85 && elapsed < 125 && focusForest()) return;
                if (regionCells == RegionalHeightfieldSource.FAR_CELLS && elapsed >= 85 && elapsed < 125) {
                    cam.setParallelProjection(false);
                    cam.setFrustumPerspective(55, (float) cam.getWidth() / cam.getHeight(), .5f, FAR_VIEW_DISTANCE);
                    float x = forestMode ? -1320 : -1450;
                    float z = 1000;
                    float y = visibleSurfaceHeight(x, z, worldHeight(x, z) + 55);
                    cam.setLocation(new Vector3f(x, y, z));
                    cam.lookAt(new Vector3f(x, worldHeight(x, forestMode ? 1200 : 1300), forestMode ? 1200 : 1300), Vector3f.UNIT_Y);
                    return;
                }
                if (regionCells > 1024 && elapsed >= 125 && elapsed < 170) {
                    cam.setParallelProjection(false);
                    cam.setFrustumPerspective(55, (float) cam.getWidth() / cam.getHeight(), .5f, 2200f);
                    cam.setLocation(new Vector3f(120, 600, 60));
                    cam.lookAt(new Vector3f(520, 170, 760), Vector3f.UNIT_Y);
                    return;
                }
                f = elapsed < 40 ? 1 : elapsed < 85 ? 61 + (elapsed - 40) * 3 : 350;
            }
            if (largeRegionMode) { applyRegion(f); return; }
            float aspect = (float) cam.getWidth() / cam.getHeight();
            if (f >= 391 && f < 501) {
                cam.setParallelProjection(true);
                cam.setFrustum(.1f, 500f, -38f * aspect, 38f * aspect, 38f, -38f);
                cam.setLocation(new Vector3f(32, 110, 32));
                cam.lookAt(new Vector3f(32, 1, 32), Vector3f.UNIT_Z);
                return;
            }
            cam.setParallelProjection(false);
            float fov = f < 31 ? 58f : f < 211 ? 47f + 8f * (float) Math.sin(f * .025)
                    : f < 291 ? 27f : f < 391 ? 67f : 39f + 8f * (float) Math.sin(f * .02);
            cam.setFrustumPerspective(fov, aspect, .15f, 500f);
            if (f < 31) {
                cam.setLocation(new Vector3f(32, 86, -48));
                cam.lookAt(new Vector3f(32, 1, 32), Vector3f.UNIT_Y);
            } else if (f < 211) {
                float t = Math.min(1, (f - 31) / 180f);
                Vector3f location = new Vector3f(1 + 2 * t, 5.5f + .8f * (float) Math.sin(f * .035), 3 + 5 * t);
                Vector3f target = new Vector3f(54 - 5 * t, (float) height(44, 19) + .8f, 18 + 10 * t);
                cam.setLocation(location);
                cam.lookAt(target, Vector3f.UNIT_Y);
            } else if (f < 291) {
                cam.setLocation(new Vector3f(103, 64, -38));
                cam.lookAt(new Vector3f(32, 2, 32), Vector3f.UNIT_Y);
            } else if (f < 391) {
                cam.setLocation(new Vector3f(3, 7, 5));
                cam.lookAt(new Vector3f(52, (float) height(48, 19) + 1, 22), Vector3f.UNIT_Y);
            } else {
                cam.setLocation(new Vector3f(82, 38, 75));
                cam.lookAt(new Vector3f(32, 2, 32), Vector3f.UNIT_Y);
            }
        }

        private void applyRegion(int f) {
            float aspect = (float) cam.getWidth() / cam.getHeight();
            cam.setParallelProjection(false);
            if (regionCells == RegionalHeightfieldSource.FAR_CELLS) {
                cam.setFrustumPerspective(55, aspect, .5f, FAR_VIEW_DISTANCE);
                if (f < 61 || f >= 210 && f < 291 || f >= 460 && f < 501) {
                    cam.setLocation(new Vector3f(0, 1800, -2600));
                    cam.lookAt(new Vector3f(0, 0, 0), Vector3f.UNIT_Y);
                } else if (f < 210) {
                    float t = Math.min(1, (f - 61) / 149f);
                    float x = -1700 + 3400 * t, z = -1200 + 2400 * t;
                    float y = worldHeight(x, z) + 70;
                    y = visibleSurfaceHeight(x, z, y);
                    cam.setLocation(new Vector3f(x, y, z));
                    cam.lookAt(new Vector3f(x + 100, y + 12, z + 180), Vector3f.UNIT_Y);
                } else {
                    cam.setLocation(new Vector3f(1500, 1000, -1800));
                    cam.lookAt(new Vector3f(200, 80, 300), Vector3f.UNIT_Y);
                }
                return;
            }
            cam.setFrustumPerspective(55, aspect, .5f, 2200f);
            if (f < 61 || f >= 210 && f < 291 || f >= 460 && f < 501) {
                cam.setLocation(new Vector3f(256, 650, -300));
                cam.lookAt(new Vector3f(256, 110, 256), Vector3f.UNIT_Y);
            } else if (f < 210) {
                float t = Math.min(1, (f - 61) / 149f);
                float x = 45 + 350 * t, z = 50 + 120 * t;
                float y = worldHeight(x, z) + 24;
                // Readiness may temporarily draw a much coarser surface. Keep
                // the camera above its conservative bound while pages arrive.
                for (Patch patch : transition.getPatches()) {
                    Node node = patch.getNode();
                    float localX = x - ((RegionalHeightfieldSource) source).getWorldOrigin();
                    float localZ = z - ((RegionalHeightfieldSource) source).getWorldOrigin();
                    if (localX >= node.getOriginX() * SPACING
                            && localX <= (node.getOriginX() + node.getSpan()) * SPACING
                            && localZ >= node.getOriginZ() * SPACING
                            && localZ <= (node.getOriginZ() + node.getSpan()) * SPACING) {
                        // Bootstrap metadata has intentionally broad source
                        // bounds. Its single coarse page gives tighter bounds
                        // on the surface actually drawn beneath this camera.
                        BoundingBox bound = initialMetadataPending
                                ? residency.get(residency.ready(key(node, revision))).createBound() : node.getBound();
                        y = Math.max(y, bound.getCenter().y + bound.getYExtent() + 24);
                    }
                }
                cam.setLocation(new Vector3f(x, y, z));
                cam.lookAt(new Vector3f(x + 60, y + 15, z + 110), Vector3f.UNIT_Y);
            } else {
                cam.setLocation(new Vector3f(400, 360, -150));
                cam.lookAt(new Vector3f(230, 140, 280), Vector3f.UNIT_Y);
            }
        }

        private float worldHeight(float worldX, float worldZ) {
            float origin = ((RegionalHeightfieldSource) source).getWorldOrigin();
            int x = Math.round((worldX - origin) / SPACING);
            int z = Math.round((worldZ - origin) / SPACING);
            return source.getHeight(x, z);
        }

        private float visibleSurfaceHeight(float worldX, float worldZ, float cameraY) {
            float origin = ((RegionalHeightfieldSource) source).getWorldOrigin();
            float localX = worldX - origin;
            float localZ = worldZ - origin;
            for (Patch patch : transition.getPatches()) {
                Node node = patch.getNode();
                if (localX >= node.getOriginX() * SPACING
                        && localX <= (node.getOriginX() + node.getSpan()) * SPACING
                        && localZ >= node.getOriginZ() * SPACING
                        && localZ <= (node.getOriginZ() + node.getSpan()) * SPACING) {
                    BoundingBox bound = initialMetadataPending
                            ? residency.get(residency.ready(key(node, revision))).createBound() : node.getBound();
                    return Math.max(cameraY, bound.getCenter().y + bound.getYExtent() + 24);
                }
            }
            return cameraY;
        }
    }

    private final class Capture implements SceneProcessor {
        private final FrameBuffer target;
        private ByteBuffer captureReadback;
        private boolean initialized;
        private Capture(FrameBuffer target) { this.target = target; }
        @Override public void initialize(RenderManager rm, ViewPort vp) { initialized = true; }
        @Override public boolean isInitialized() { return initialized; }
        @Override public void reshape(ViewPort vp, int w, int h) { }
        @Override public void preFrame(float tpf) { }
        @Override public void postQueue(RenderQueue queue) { }
        @Override public void cleanup() {
            initialized = false;
            if (revisionPreparation != null) revisionPreparation.close();
            if (pagePreparation != null) pagePreparation.close();
            if (surfaces != null) surfaces.close();
            if (gpuSelectionMode && !drainGpuSelectionResources()) return;
            if (residency != null) {
                // Last-frame leases fence sky/post work as well as terrain. Drain
                // real uses before releasing the static cube and its CPU replay.
                retiringStorage = true;
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (residency.stats().frames != 0 && System.nanoTime() < deadline) {
                    residency.poll();
                    Thread.yield();
                }
                require(residency.stats().frames == 0, "draws drained before image disposal");
            }
            if (forest != null) {
                if (regionalVisibility != null) {
                    regionalVisibility.removeReceiver(forest.getMaterial());
                    if (forest.getFarMaterial() != null) regionalVisibility.removeReceiver(forest.getFarMaterial());
                }
                forest.close();
                forest = null;
            }
            if (batchTerrain) {
                if (terrainBatches != null) { terrainBatches.close(); terrainBatches = null; }
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!retiredTerrainBatches.isEmpty() && System.nanoTime() < deadline) {
                    pollRetiredTerrainBatches();
                    Thread.yield();
                }
                require(retiredTerrainBatches.isEmpty(), "retired batch buffers drained before disposal");
            }
            if (atmosphereSky != null) atmosphereSky.close(renderer);
            // Discard the physical storage before invalidating its leases.
            if (atlas != null) discardAtlas();
            if (gradientAtlas != null) discardAtlas(gradientAtlas);
            if (residency != null) residency.close();
            frameLease = null;
            if (gpuProbe != null) gpuProbe.close();
            if (target != null) renderer.deleteFrameBuffer(target);
            if (captureReadback != null) BufferUtils.destroyDirectBuffer(captureReadback);
            captureReadback = null;
            cleanupComplete = true;
            if (!validationEnabled) System.out.println("HEIGHTFIELD_EXPLORATION_STOP frames=" + frame
                    + " revision=" + revision + " edits=" + edits + " recreations=" + recreations
                    + " injectedPreparation=" + injectedPreparation + " injectedUpload=" + injectedUpload
                    + " injectedCancellation=" + injectedCancellation);
            System.out.println("HEIGHTFIELD_STREAM_CLEANUP_PASS workers=closed sky=closed frameLeases=drained");
        }
        @Override public void setProfiler(AppProfiler profiler) { }
        @Override public void postFrame(FrameBuffer out) {
            if (failure != null) return;
            try {
                if (gpuRecreationDraining && frameLease == null) return;
                require(frameLease != null, "terrain frame has an acquired lease");
                require(residency.submit(frameLease, new Completion()), "frame fence submitted once after opaque terrain and shadow reads");
                frameLease = null;
                if (surfaces != null) surfaces.endFrame();
                if (frame == 1 && asynchronousInitialMetadata) {
                    System.out.printf(Locale.ROOT,
                            "HEIGHTFIELD_REGION_FIRST_FRAME cells=%d startupMs=%.3f metadataPending=%s%n",
                            regionCells, (System.nanoTime() - startupStartedNanos) / 1e6, initialMetadataPending);
                }
                String label = captureLabel(frame);
                if (output != null && !profileMode && label != null) writeCapture(label);
                if (profileMode && samplingStopped && !profileWritten) {
                    if (gpuFeedbackPending || !timings.isDrained()) {
                        if (++profileDrainFrames > 300) {
                            throw new IllegalStateException("frame timing queries or GPU cover feedback did not drain");
                        }
                    } else {
                        timings.write(output);
                        writeProfileStageSummary();
                        profileWritten = true;
                        finish();
                    }
                } else if (!profileMode && output != null && readyToFinish()
                        && (!gpuTransitionsMode || !gpuFeedbackPending)) {
                    finish();
                }
            } catch (Throwable error) {
                fail(error);
            }
        }

        private boolean drainGpuSelectionResources() {
            if (gpuSelection == null && gpuTransitions == null && gpuFeedback == null
                    && gpuPageBindings == null && retiredGpuResources.isEmpty()
                    && gpuRecreationFence == null) return true;
            retiringStorage = true;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            try {
                while (System.nanoTime() < deadline) {
                    if (gpuFeedbackPending) {
                        HeightfieldGpuCoverFeedback.Snapshot snapshot = gpuFeedback.poll();
                        if (snapshot != null) gpuFeedbackPending = false;
                    }
                    residency.poll();
                    pollRetiredGpuResources();
                    boolean recreationFenceReady = gpuRecreationFence == null
                            || renderer.isFenceSignaled(gpuRecreationFence);
                    if (!gpuFeedbackPending && frameLease == null && residency.stats().frames == 0
                            && retiredGpuResources.isEmpty() && recreationFenceReady) break;
                    Thread.yield();
                }
                if (gpuFeedbackPending || frameLease != null || residency.stats().frames != 0
                        || !retiredGpuResources.isEmpty()
                        || gpuRecreationFence != null && !renderer.isFenceSignaled(gpuRecreationFence)) {
                    fail(new IllegalStateException("GPU selection resources retained because native use fences did not drain"));
                    return false;
                }
            } catch (RuntimeException error) {
                fail(error);
                return false;
            }
            if (gpuRecreationFence != null) {
                renderer.deleteFence(gpuRecreationFence);
                gpuRecreationFence = null;
            }
            closeActiveGpuResources();
            return true;
        }

        private void writeCapture(String label) throws Exception {
            if (regionCells == RegionalHeightfieldSource.FAR_CELLS) {
                float origin = ((RegionalHeightfieldSource) source).getWorldOrigin();
                if (forest != null) forest.validateWorldOrigin(origin);
                if (batchTerrain) terrainBatches.validateWorldOrigin(origin, displayed.size());
                else for (Node node : displayed) {
                    Geometry geometry = geometryByNode.get(node);
                    Vector3f world = geometry.getWorldTranslation();
                    require(geometry.getParent() == terrainParent
                                    && world.x == origin + node.getOriginX() * SPACING
                                    && world.y == 0 && world.z == origin + node.getOriginZ() * SPACING,
                            "rendered terrain geometry uses its world origin exactly once");
                }
            }
            Files.createDirectories(output);
            int width = target.getWidth(), height = target.getHeight();
            if (captureReadback == null) {
                captureReadback = BufferUtils.createByteBuffer(width * height * 16)
                        .order(ByteOrder.nativeOrder());
            }
            ByteBuffer bytes = captureReadback;
            bytes.clear();
            // Read the actual post-filter RGBA16F target as float, then apply
            // one display transfer instead of quantizing an intermediate to 8-bit linear.
            renderer.readFrameBufferWithFormat(target, bytes, Image.Format.RGBA32F);
            FloatBuffer linear = bytes.asFloatBuffer();
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int i = ((height - 1 - y) * width + x) * 4;
                    int r = display(linear.get(i));
                    int g = display(linear.get(i + 1));
                    int b = display(linear.get(i + 2));
                    image.setRGB(x, y, (r << 16) | (g << 8) | b);
                }
            }
            if (!ImageIO.write(image, "png", output.resolve(label + ".png").toFile())) {
                throw new IllegalStateException("PNG writer unavailable");
            }
            System.out.println("HEIGHTFIELD_STREAM_CAPTURE " + label);
        }

        private int display(float linear) {
            if (!Float.isFinite(linear)) throw new IllegalStateException("non-finite post-filter HDR pixel");
            linear = Math.max(0, Math.min(1, linear));
            float encoded = linear <= .0031308f ? linear * 12.92f
                    : 1.055f * (float) Math.pow(linear, 1.0 / 2.4) - .055f;
            return Math.min(255, Math.max(0, Math.round(encoded * 255)));
        }
    }
}
