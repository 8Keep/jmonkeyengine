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
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED
 * TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.jme3.shadow;

import com.jme3.asset.AssetManager;
import com.jme3.asset.DesktopAssetManager;
import com.jme3.light.DirectionalLight;
import com.jme3.material.Material;
import com.jme3.material.MaterialDef;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.renderer.Camera;
import com.jme3.renderer.Caps;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.ViewPort;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.shape.Box;
import com.jme3.shader.VarType;
import com.jme3.system.NullRenderer;
import com.jme3.texture.FrameBuffer;
import com.jme3.texture.Image;
import com.jme3.texture.image.ColorSpace;
import com.jme3.util.clone.Cloner;
import java.io.IOException;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DirectionalLightVisibilityRendererTest {

    @Test
    void validatesDepthBias() {
        DirectionalLightVisibilityRenderer renderer = newRenderer();

        assertEquals(0.0005f, renderer.getDepthBias());
        renderer.setDepthBias(0.002f);
        assertEquals(0.002f, renderer.getDepthBias());
        assertThrows(IllegalArgumentException.class, () -> renderer.setDepthBias(-0.1f));
        assertThrows(IllegalArgumentException.class, () -> renderer.setDepthBias(Float.NaN));
        assertThrows(IllegalArgumentException.class, () -> renderer.setDepthBias(Float.POSITIVE_INFINITY));
    }

    @Test
    void rejectsNonfiniteCascadeSettingsBeforeNativeUse() {
        DirectionalLightVisibilityRenderer renderer = newRenderer();
        assertThrows(IllegalArgumentException.class, () -> renderer.setLambda(Float.NaN));
        assertThrows(IllegalArgumentException.class, () -> renderer.setLambda(1.1f));
        assertThrows(IllegalArgumentException.class, () -> renderer.setShadowZExtend(Float.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> renderer.setShadowZFadeLength(-1));
        renderer.setLight(new DirectionalLight(new Vector3f(0, -1, 0)));
        renderer.setShadowIntensity(Float.NaN);
        assertThrows(IllegalStateException.class, () -> renderer.postQueue(null));
    }

    @Test
    void restoresOwnedMaterialValuesWhenReceiverIsRemoved() {
        DirectionalLightVisibilityRenderer renderer = newRenderer();
        Material material = receiverMaterial();
        material.setBoolean("UseDirectionalVisibility", false);
        material.setFloat("DirectionalShadowBias", 0.125f);
        ColorRGBA originalSplits = new ColorRGBA(2f, 4f, 8f, 16f);
        material.setColor("DirectionalShadowSplits", originalSplits);

        renderer.addReceiver(material);
        renderer.addReceiver(material);
        material.setBoolean("UseDirectionalVisibility", true);
        material.setFloat("DirectionalShadowBias", 0.5f);

        assertTrue(renderer.removeReceiver(material));
        assertFalse(renderer.removeReceiver(material));
        assertFalse((Boolean) material.getParamValue("UseDirectionalVisibility"));
        assertEquals(0.125f, (Float) material.getParamValue("DirectionalShadowBias"));
        assertEquals(originalSplits, material.getParamValue("DirectionalShadowSplits"));
        assertTrue(material.getParamValue("DirectionalShadowMap0") == null);
        assertTrue(material.getParamValue("DirectionalLightViewProjectionMatrix0") == null);
    }

    @Test
    void rejectsAlreadyEnabledMaterialAndDefinitionsWithoutFeatureParameters() {
        DirectionalLightVisibilityRenderer renderer = newRenderer();
        Material enabled = receiverMaterial();
        enabled.setBoolean("UseDirectionalVisibility", true);
        assertThrows(IllegalArgumentException.class, () -> renderer.addReceiver(enabled));

        Material ordinary = new Material(new MaterialDef(assetManager(), "ordinary"));
        assertThrows(IllegalArgumentException.class, () -> renderer.addReceiver(ordinary));
    }

    @Test
    void preventsMaterialSharingAcrossRendererInstances() {
        DirectionalLightVisibilityRenderer first = newRenderer();
        DirectionalLightVisibilityRenderer second = newRenderer();
        Material material = receiverMaterial();
        first.addReceiver(material);

        assertThrows(IllegalArgumentException.class, () -> second.addReceiver(material));
        assertTrue(first.removeReceiver(material));
        second.addReceiver(material);
        assertTrue(second.removeReceiver(material));
    }

    @Test
    void rejectsInvalidMutableSunDirectionBeforeRendering() {
        DirectionalLightVisibilityRenderer renderer = newRenderer();
        DirectionalLight light = new DirectionalLight();
        light.getDirection().set(Float.NaN, 0f, 0f);
        renderer.setLight(light);

        assertThrows(IllegalStateException.class, () -> renderer.postQueue(null));
    }

    @Test
    void rejectsUnsupportedShaderCapsBeforeBindingViewport() {
        DirectionalLightVisibilityRenderer renderer = newRenderer();

        assertThrows(UnsupportedOperationException.class,
                () -> renderer.initialize(renderManager(EnumSet.noneOf(Caps.class)), viewPort()));
        assertFalse(renderer.isInitialized());
    }

    @Test
    void rejectsFilteringModesTheReceiverShaderCannotRepresent() {
        DirectionalLightVisibilityRenderer renderer = newRenderer();
        renderer.setEdgeFilteringMode(EdgeFilteringMode.Nearest);

        assertThrows(UnsupportedOperationException.class,
                () -> renderer.initialize(renderManager(EnumSet.of(Caps.GLSL150)), viewPort()));
        assertFalse(renderer.isInitialized());
    }

    @Test
    void orthographicSplitsRemainInViewDistanceUnitsForCasterSelection() {
        DirectionalLightVisibilityRenderer renderer = newRenderer();
        renderer.setLight(new DirectionalLight(new Vector3f(-1f, -2f, -3f).normalizeLocal()));
        Camera camera = new Camera(800, 600);
        camera.setParallelProjection(true);
        camera.setFrustum(1f, 100f, -10f, 10f, 10f, -10f);
        camera.update();

        renderer.updateShadowCams(camera);

        assertTrue(renderer.splitsArray[1] > 1f);
        assertEquals(100f, renderer.splitsArray[2]);
    }

    @Test
    void rejectsInvalidNearAndFarBeforeUpdatingShadowCameras() {
        DirectionalLightVisibilityRenderer renderer = newRenderer();
        renderer.setLight(new DirectionalLight(new Vector3f(-1f, -2f, -3f).normalizeLocal()));
        Camera camera = new Camera(800, 600);
        camera.setFrustum(10f, 5f, -10f, 10f, 10f, -10f);

        assertThrows(IllegalStateException.class, () -> renderer.updateShadowCams(camera));
    }

    @Test
    void usesDepth24AndCleanupRestoresMaterialsAndReleasesOwnedResources() {
        DirectionalLightVisibilityRenderer renderer = newRenderer();
        Material material = receiverMaterial();
        renderer.addReceiver(material);
        material.setBoolean("UseDirectionalVisibility", true);
        TrackingRenderer trackingRenderer = new TrackingRenderer();
        renderer.initialize(new RenderManager(trackingRenderer), viewPort());

        assertEquals(Image.Format.Depth24, renderer.shadowMaps[0].getImage().getFormat());
        assertEquals(Image.Format.Depth24, renderer.shadowMaps[1].getImage().getFormat());
        renderer.cleanup();

        assertFalse(renderer.isInitialized());
        assertFalse((Boolean) material.getParamValue("UseDirectionalVisibility"));
        assertEquals(2, trackingRenderer.deletedFrameBuffers);
        assertEquals(3, trackingRenderer.deletedImages);
        renderer.cleanup();
        assertEquals(2, trackingRenderer.deletedFrameBuffers);
        assertEquals(3, trackingRenderer.deletedImages);
        DirectionalLightVisibilityRenderer second = newRenderer();
        second.addReceiver(material);
        assertTrue(second.removeReceiver(material));
    }

    @Test
    void enablesReceiversWhenCastersExistBeforeTheShadowQueueConsumesThem() {
        DirectionalLightVisibilityRenderer renderer = newRenderer();
        renderer.setLight(new DirectionalLight(new Vector3f(-1f, -2f, -3f).normalizeLocal()));
        Material material = receiverMaterial();
        renderer.addReceiver(material);

        Camera camera = new Camera(640, 480);
        camera.setFrustumPerspective(45f, 640f / 480f, 1f, 100f);
        camera.lookAt(new Vector3f(0f, 0f, -1f), Vector3f.UNIT_Y);
        camera.update();
        ViewPort viewPort = new ViewPort("caster-test", camera);
        Node scene = new Node("scene");
        Geometry casterAndReceiver = new Geometry("box", new Box(1f, 1f, 1f));
        casterAndReceiver.setLocalTranslation(0f, 0f, -10f);
        casterAndReceiver.setMaterial(material);
        casterAndReceiver.setShadowMode(RenderQueue.ShadowMode.CastAndReceive);
        scene.attachChild(casterAndReceiver);
        scene.updateGeometricState();
        viewPort.attachScene(scene);
        renderer.initialize(renderManager(EnumSet.of(Caps.GLSL150)), viewPort);

        renderer.postQueue(viewPort.getQueue());

        assertTrue((Boolean) material.getParamValue("UseDirectionalVisibility"),
                "caster presence must be captured before renderShadowQueue clears the GeometryList");
        renderer.postFrame(null);
        assertFalse((Boolean) material.getParamValue("UseDirectionalVisibility"));
        renderer.cleanup();
    }

    @Test
    void rejectsCloneAndSerializationEntryPoints() {
        DirectionalLightVisibilityRenderer renderer = newRenderer();

        assertThrows(UnsupportedOperationException.class, renderer::jmeClone);
        assertThrows(UnsupportedOperationException.class, () -> renderer.cloneFields(new Cloner(), renderer));
        assertThrows(IOException.class, () -> renderer.write(null));
        assertThrows(IOException.class, () -> renderer.read(null));
    }

    private static DirectionalLightVisibilityRenderer newRenderer() {
        return new DirectionalLightVisibilityRenderer(assetManager(), 128, 2);
    }

    private static AssetManager assetManager() {
        return new DesktopAssetManager(true);
    }

    private static Material receiverMaterial() {
        MaterialDef def = new MaterialDef(assetManager(), "directional-visibility-test");
        def.addMaterialParam(VarType.Boolean, "UseDirectionalVisibility", false);
        def.addMaterialParam(VarType.Boolean, "DirectionalHardwareShadows", false);
        def.addMaterialParam(VarType.Int, "DirectionalCascadeCount", 1);
        def.addMaterialParam(VarType.Float, "DirectionalShadowMapSize", 128f);
        def.addMaterialParam(VarType.Float, "DirectionalShadowBias", 0f);
        def.addMaterialParam(VarType.Float, "DirectionalShadowStrength", 0.7f);
        def.addMaterialParam(VarType.Vector4, "DirectionalShadowSplits", null);
        def.addMaterialParam(VarType.Vector3, "DirectionalShadowLightDir", null);
        def.addMaterialParam(VarType.Vector2, "DirectionalShadowFade", null);
        for (int i = 0; i < 4; i++) {
            def.addMaterialParamTexture(VarType.Texture2D, "DirectionalShadowMap" + i, ColorSpace.Linear, null);
            def.addMaterialParam(VarType.Matrix4, "DirectionalLightViewProjectionMatrix" + i, null);
        }
        return new Material(def);
    }

    private static RenderManager renderManager(EnumSet<Caps> caps) {
        return new RenderManager(new TrackingRenderer() {
            @Override
            public EnumSet<Caps> getCaps() {
                return caps;
            }
        });
    }

    private static ViewPort viewPort() {
        return new ViewPort("test", new Camera(1, 1));
    }

    private static class TrackingRenderer extends NullRenderer {
        int deletedFrameBuffers;
        int deletedImages;

        @Override
        public void deleteFrameBuffer(FrameBuffer frameBuffer) {
            deletedFrameBuffers++;
            super.deleteFrameBuffer(frameBuffer);
        }

        @Override
        public void deleteImage(Image image) {
            deletedImages++;
            super.deleteImage(image);
        }
    }
}
