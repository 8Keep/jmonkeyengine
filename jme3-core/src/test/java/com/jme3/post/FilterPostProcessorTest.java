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
package com.jme3.post;

import com.jme3.asset.AssetManager;
import com.jme3.material.Material;
import com.jme3.math.Matrix4f;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.Renderer;
import com.jme3.renderer.ViewPort;
import com.jme3.system.NullRenderer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

class FilterPostProcessorTest {

    @Test
    void processingCameraCopiesSceneStateWithoutMutatingIt() {
        RenderManager renderManager = new RenderManager(new NullRenderer());
        Camera sceneCamera = new Camera(320, 240);
        sceneCamera.setParallelProjection(true);
        sceneCamera.setFrustum(-5f, 40f, -7f, 9f, 6f, -4f);
        sceneCamera.setLocation(new Vector3f(3f, 7f, 11f));
        sceneCamera.setRotation(new Quaternion().fromAngles(0.2f, -0.4f, 0.1f));

        ViewPort viewPort = renderManager.createMainView("main", sceneCamera);
        FilterPostProcessor processor = new FilterPostProcessor(null);
        viewPort.addProcessor(processor);
        renderManager.notifyReshape(320, 240, 640, 480);

        Matrix4f sceneView = sceneCamera.getViewMatrix().clone();
        Matrix4f sceneProjection = sceneCamera.getProjectionMatrix().clone();
        Camera processingCamera = processor.prepareProcessingCamera(320, 240, 0f, 1f, 0f, 1f);

        assertNotSame(sceneCamera, processingCamera);
        assertEquals(sceneCamera.getLocation(), processingCamera.getLocation());
        assertEquals(sceneCamera.getRotation(), processingCamera.getRotation());
        assertEquals(sceneView, processingCamera.getViewMatrix());
        assertEquals(sceneProjection, processingCamera.getProjectionMatrix());
        assertEquals(sceneCamera.getFrustumNear(), processingCamera.getFrustumNear());
        assertEquals(sceneCamera.getFrustumFar(), processingCamera.getFrustumFar());
        assertEquals(sceneCamera.isParallelProjection(), processingCamera.isParallelProjection());
        assertEquals(320, processingCamera.getWidth());
        assertEquals(240, processingCamera.getHeight());
        assertEquals(320, sceneCamera.getWidth());
        assertEquals(240, sceneCamera.getHeight());
        assertEquals(sceneView, sceneCamera.getViewMatrix());
        assertEquals(sceneProjection, sceneCamera.getProjectionMatrix());
    }

    @Test
    void lateCreatedMultiViewFppInheritsPhysicalDefaultFramebufferSize() {
        RenderManager renderManager = new RenderManager(new NullRenderer());
        renderManager.notifyReshape(320, 240, 640, 480);

        Camera sceneCamera = new Camera(320, 240);
        sceneCamera.setViewPort(0.5f, 1f, 0f, 1f);
        ViewPort lateView = renderManager.createMainView("late right", sceneCamera);
        RecordingFilter filter = new RecordingFilter();
        FilterPostProcessor processor = new FilterPostProcessor(null);
        processor.addFilter(filter);

        processor.initialize(renderManager, lateView);

        assertEquals(320, filter.width);
        assertEquals(480, filter.height);
        assertEquals(320, filter.cameraWidthDuringInit);
        assertEquals(240, filter.cameraHeightDuringInit);
        assertEquals(320, sceneCamera.getWidth());
        assertEquals(240, sceneCamera.getHeight());
    }

    @Test
    void multiViewRightHalfAtTwoXDisplayScaleUsesLogicalCameraAndPhysicalFppTarget() {
        RecordingRenderer renderer = new RecordingRenderer();
        RenderManager renderManager = new RenderManager(renderer);
        Camera sceneCamera = new Camera(320, 240);
        sceneCamera.setViewPort(0.5f, 1f, 0f, 1f);

        ViewPort viewPort = renderManager.createMainView("right", sceneCamera);
        RecordingFilter filter = new RecordingFilter();
        FilterPostProcessor processor = new FilterPostProcessor(null);
        processor.addFilter(filter);
        viewPort.addProcessor(processor);

        renderManager.notifyReshape(320, 240, 640, 480);

        Matrix4f sceneProjection = sceneCamera.getProjectionMatrix().clone();
        processor.preFrame(0f);

        Camera renderCamera = viewPort.getCamera();
        assertNotSame(sceneCamera, renderCamera);
        assertEquals(160, renderCamera.getWidth());
        assertEquals(240, renderCamera.getHeight());
        assertEquals(0f, renderCamera.getViewPortLeft());
        assertEquals(1f, renderCamera.getViewPortRight());
        assertEquals(0f, renderCamera.getViewPortBottom());
        assertEquals(1f, renderCamera.getViewPortTop());
        assertEquals(sceneProjection, renderCamera.getProjectionMatrix());

        assertEquals(320, sceneCamera.getWidth());
        assertEquals(240, sceneCamera.getHeight());
        assertEquals(0.5f, sceneCamera.getViewPortLeft());
        assertEquals(1f, sceneCamera.getViewPortRight());
        assertEquals(sceneProjection, sceneCamera.getProjectionMatrix());
        assertEquals(320, filter.width);
        assertEquals(480, filter.height);
        assertEquals(320, viewPort.getRenderTargetWidth());
        assertEquals(480, viewPort.getRenderTargetHeight());

        renderManager.applyViewPort(viewPort);
        assertEquals(0, renderer.viewPortX);
        assertEquals(0, renderer.viewPortY);
        assertEquals(320, renderer.viewPortWidth);
        assertEquals(480, renderer.viewPortHeight);

        // Avoid rendering the test filter's null material, then exercise the
        // normal post-frame restoration used before the translucent bucket.
        filter.setEnabled(false);
        processor.postFrame(null);

        assertSame(sceneCamera, viewPort.getCamera());
        assertEquals(320, renderer.viewPortX);
        assertEquals(0, renderer.viewPortY);
        assertEquals(320, renderer.viewPortWidth);
        assertEquals(480, renderer.viewPortHeight);
        assertEquals(320, sceneCamera.getWidth());
        assertEquals(240, sceneCamera.getHeight());
        assertEquals(0.5f, sceneCamera.getViewPortLeft());
        assertEquals(1f, sceneCamera.getViewPortRight());
        assertEquals(sceneProjection, sceneCamera.getProjectionMatrix());
    }

    @Test
    void fullViewAtDisplayScaleUsesPhysicalFppTargetAndLogicalCamera() {
        RecordingRenderer renderer = new RecordingRenderer();
        RenderManager renderManager = new RenderManager(renderer);
        Camera camera = new Camera(320, 240);
        ViewPort viewPort = renderManager.createMainView("main", camera);
        renderManager.notifyReshape(320, 240, 640, 480);

        RecordingFilter filter = new RecordingFilter();
        FilterPostProcessor processor = new FilterPostProcessor(null);
        processor.addFilter(filter);
        viewPort.addProcessor(processor);

        renderManager.notifyReshape(320, 240, 640, 480);

        assertEquals(640, filter.width);
        assertEquals(480, filter.height);
        assertEquals(320, filter.cameraWidthDuringInit);
        assertEquals(240, filter.cameraHeightDuringInit);
        assertSame(camera, viewPort.getCamera());
        assertEquals(320, camera.getWidth());
        assertEquals(240, camera.getHeight());
        assertEquals(640, viewPort.getRenderTargetWidth());
        assertEquals(480, viewPort.getRenderTargetHeight());

        renderManager.applyViewPort(viewPort);
        assertEquals(640, renderer.viewPortWidth);
        assertEquals(480, renderer.viewPortHeight);

        renderManager.notifyReshape(320, 240, 800, 600);

        assertEquals(800, filter.width);
        assertEquals(600, filter.height);
        assertEquals(320, filter.cameraWidthDuringInit);
        assertEquals(240, filter.cameraHeightDuringInit);
        assertEquals(320, camera.getWidth());
        assertEquals(240, camera.getHeight());
        assertEquals(1, filter.cleanupCount);

        filter.setEnabled(false);
        processor.postFrame(null);

        assertEquals(320, camera.getWidth());
        assertEquals(240, camera.getHeight());
        assertEquals(800, renderer.viewPortWidth);
        assertEquals(600, renderer.viewPortHeight);
    }

    @Test
    void initializationUsesFilterFlagsChangedAfterAdding() throws ReflectiveOperationException {
        RenderManager manager = new RenderManager(new NullRenderer());
        ViewPort view = manager.createMainView("main", new Camera(320, 240));
        FilterPostProcessor processor = new FilterPostProcessor(null);
        RecordingFilter first = new RecordingFilter();
        first.setEnabled(false);
        RecordingFilter trailing = new RecordingFilter();
        processor.addFilter(first);
        processor.addFilter(trailing);
        // Neither filter has its processor reference until initialization.
        first.setEnabled(true);
        trailing.setEnabled(false);
        processor.initialize(manager, view);

        java.lang.reflect.Field last = FilterPostProcessor.class.getDeclaredField("lastFilterIndex");
        last.setAccessible(true);
        assertEquals(0, last.getInt(processor), "the enabled filter must render to the final target");

        // Cleanup clears Filter.processor; flag changes before reuse must work too.
        processor.cleanup();
        first.setEnabled(false);
        trailing.setEnabled(true);
        processor.initialize(manager, view);
        assertEquals(1, last.getInt(processor));
        processor.cleanup();
    }

    @Test
    void allFiltersDisabledBeforeInitializationKeepOriginalOutput() {
        RenderManager manager = new RenderManager(new NullRenderer());
        ViewPort view = manager.createMainView("main", new Camera(320, 240));
        com.jme3.texture.FrameBuffer output = new com.jme3.texture.FrameBuffer(320, 240, 1);
        view.setOutputFrameBuffer(output);
        FilterPostProcessor processor = new FilterPostProcessor(null);
        RecordingFilter filter = new RecordingFilter();
        processor.addFilter(filter);
        filter.setEnabled(false);
        processor.initialize(manager, view);

        assertSame(output, view.getOutputFrameBuffer());
        processor.cleanup();
    }

    @Test
    void sampledEsInputsUseNegotiatedCountWhileAutomaticRetainsResolvedInputs() {
        NullRenderer renderer = new NullRenderer() {
            @Override public int getPreferredFrameBufferSamples(com.jme3.texture.Image.Format color,
                    com.jme3.texture.Image.Format depth, int requested) {
                assertEquals(3, requested);
                assertEquals(com.jme3.texture.Image.Format.RGBA8, color);
                assertEquals(com.jme3.texture.Image.Format.Depth24, depth);
                return 2;
            }
        };
        renderer.getCaps().clear();
        renderer.getCaps().addAll(java.util.EnumSet.of(com.jme3.renderer.Caps.OpenGLES31,
                com.jme3.renderer.Caps.FrameBufferMultisample, com.jme3.renderer.Caps.TextureMultisample));
        RenderManager manager = new RenderManager(renderer);
        ViewPort view = manager.createMainView("samples", new Camera(32, 32));
        FilterPostProcessor processor = new FilterPostProcessor(null);
        processor.setFrameBufferFormat(com.jme3.texture.Image.Format.RGBA8);
        processor.setFrameBufferDepthFormat(com.jme3.texture.Image.Format.Depth24);
        processor.setNumSamples(3);
        processor.addFilter(new RecordingFilter());
        processor.initialize(manager, view);
        assertEquals(1, processor.getFilterTexture().getImage().getMultiSamples(),
                "existing ES300 filters must keep resolved input unless explicitly opted in");
        processor.cleanup();

        processor.setMultisampleMode(FilterPostProcessor.MultisampleMode.Sampled);
        processor.initialize(manager, view);
        assertEquals(2, view.getOutputFrameBuffer().getSamples());
        assertEquals(2, processor.getFilterTexture().getImage().getMultiSamples());
        assertEquals(2, processor.getDepthTexture().getImage().getMultiSamples());
        processor.cleanup();

        processor.setMultisampleMode(FilterPostProcessor.MultisampleMode.Resolved);
        processor.initialize(manager, view);
        assertEquals(1, processor.getFilterTexture().getImage().getMultiSamples());
        processor.cleanup();
    }

    @Test
    void sampledRequestFallsBackWithoutTextureCapabilityAndCanReturnAfterSingleSampleReuse() {
        NullRenderer renderer = new NullRenderer();
        renderer.initialize();
        renderer.getCaps().clear();
        renderer.getCaps().add(com.jme3.renderer.Caps.FrameBufferMultisample);
        RenderManager manager = new RenderManager(renderer);
        ViewPort view = manager.createMainView("fallback", new Camera(32, 32));
        FilterPostProcessor processor = new FilterPostProcessor(null);
        processor.addFilter(new RecordingFilter());
        processor.setMultisampleMode(FilterPostProcessor.MultisampleMode.Sampled);
        processor.setNumSamples(4);
        processor.initialize(manager, view);
        assertEquals(1, processor.getFilterTexture().getImage().getMultiSamples());
        processor.cleanup();

        renderer.getCaps().add(com.jme3.renderer.Caps.TextureMultisample);
        for (int samples : new int[]{4, 1, 4}) {
            processor.setNumSamples(samples);
            processor.initialize(manager, view);
            assertEquals(samples, processor.getFilterTexture().getImage().getMultiSamples());
            processor.cleanup();
        }
    }

    private static class RecordingRenderer extends NullRenderer {
        private int viewPortX;
        private int viewPortY;
        private int viewPortWidth;
        private int viewPortHeight;

        @Override
        public void setViewPort(int x, int y, int width, int height) {
            viewPortX = x;
            viewPortY = y;
            viewPortWidth = width;
            viewPortHeight = height;
        }
    }

    private static class RecordingFilter extends Filter {
        private int width;
        private int height;
        private int cameraWidthDuringInit;
        private int cameraHeightDuringInit;
        private int cleanupCount;

        @Override
        protected void initFilter(AssetManager manager, RenderManager renderManager, ViewPort vp, int w, int h) {
            width = w;
            height = h;
            cameraWidthDuringInit = vp.getCamera().getWidth();
            cameraHeightDuringInit = vp.getCamera().getHeight();
        }

        @Override
        protected Material getMaterial() {
            return null;
        }

        @Override
        protected void cleanUpFilter(Renderer r) {
            cleanupCount++;
        }
    }
}
