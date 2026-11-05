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
import com.jme3.export.JmeExporter;
import com.jme3.export.JmeImporter;
import com.jme3.util.clone.Cloner;
import com.jme3.light.DirectionalLight;
import com.jme3.material.Material;
import com.jme3.material.MatParam;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector2f;
import com.jme3.math.Vector4f;
import com.jme3.renderer.Camera;
import com.jme3.renderer.Caps;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.Renderer;
import com.jme3.renderer.ViewPort;
import com.jme3.renderer.queue.GeometryList;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.texture.FrameBuffer;
import com.jme3.texture.Image;
import com.jme3.texture.Image.Format;
import com.jme3.texture.Texture2D;
import java.lang.ref.WeakReference;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders stabilized directional-light cascades and makes their visibility
 * maps available to explicitly registered materials. The receiver shader
 * applies visibility to direct illumination; this processor does not render
 * the inherited colour-modulation pass.
 * <p>
 * A material may be registered with only one active visibility renderer. Its
 * previous values for the parameters owned here are restored when it is
 * removed or this processor is cleaned up.
 * <p>
 * Instances own viewport-bound native resources and receiver registrations;
 * serialization and cloning are unsupported. Configure, register and clean up
 * this processor on the rendering/context thread. Registered materials must be
 * dedicated to opaque receivers in this viewport; their feature flag is cleared
 * before the translucent queue. Do not also apply the stock post-shadow pass.
 * <p>
 * The designated light is matched by object identity while packing each PBR
 * light batch. Collinear lights remain independent. Custom receivers must use
 * the identity marker in the third packed light vector. Four depth samplers and four light matrices
 * consume additional fragment resources. Hardware comparison uses bilinear
 * filtering for the optional tent kernel; the raw-depth fallback compares
 * individual texels before applying the same weights. Older receiver
 * definitions without the optional filter and blend parameters remain valid
 * and use legacy filtering with no shader blend. Camera overlap is renderer-wide.
 */
public final class DirectionalLightVisibilityRenderer extends DirectionalLightShadowRenderer {

    /** Selects the receiver's shadow comparison kernel. */
    public enum ShadowFilterMode {
        /** Existing four comparison taps; this is the default. */
        LEGACY,
        /** Five-by-five tent filter, implemented with nine hardware or 36 manual taps. */
        PCF_5X5
    }

    private static final String ENABLED = "UseDirectionalVisibility";
    private static final String[] OWNED_PARAMETERS = createParameterNames();
    private static final List<ReceiverClaim> RECEIVER_CLAIMS = new ArrayList<>();
    private final Map<Material, Map<String, MatParam>> receivers = new IdentityHashMap<>();
    private final ColorRGBA worldSplits = new ColorRGBA();
    private final Vector4f worldBlendStarts = new Vector4f();
    private float depthBias = 0.0005f;
    private ShadowFilterMode shadowFilterMode = ShadowFilterMode.LEGACY;
    private float cascadeBlendFraction;
    private boolean anyShadowCasters;
    private boolean activatedThisFrame;

    private static final class ReceiverClaim extends WeakReference<Material> {
        final WeakReference<DirectionalLightVisibilityRenderer> owner;

        ReceiverClaim(Material material, DirectionalLightVisibilityRenderer owner) {
            super(material);
            this.owner = new WeakReference<>(owner);
        }
    }

    /**
     * Creates a visibility renderer using the directional shadow renderer's
     * stabilized parallel-split cascade generation.
     *
     * @param assetManager the application's asset manager
     * @param shadowMapSize the width and height of each depth map
     * @param nbSplits the number of cascades, from one through four
     */
    public DirectionalLightVisibilityRenderer(AssetManager assetManager, int shadowMapSize, int nbSplits) {
        super(assetManager, shadowMapSize, nbSplits);
        for (Texture2D shadowMap : shadowMaps) {
            shadowMap.getImage().setFormat(Format.Depth24);
        }
    }

    private static String[] createParameterNames() {
        List<String> names = new ArrayList<>();
        Collections.addAll(names, ENABLED, "DirectionalHardwareShadows", "DirectionalCascadeCount",
                "DirectionalShadowMapSize", "DirectionalShadowBias", "DirectionalShadowStrength",
                "DirectionalShadowSplits", "DirectionalShadowFade",
                "DirectionalShadowFilterMode", "DirectionalCascadeBlendFraction",
                "DirectionalCascadeBlendStarts");
        for (int i = 0; i < 4; i++) {
            names.add("DirectionalShadowMap" + i);
            names.add("DirectionalLightViewProjectionMatrix" + i);
        }
        return names.toArray(new String[0]);
    }

    /**
     * Registers a material as a receiver. The material definition must declare
     * all directional visibility parameters. Re-registering the same material
     * has no effect.
     *
     * @param material material whose direct directional illumination should be shadowed
     * @throws IllegalArgumentException if the material is null, does not define
     *         the feature parameters, or already enables this feature
     */
    public void addReceiver(Material material) {
        if (material == null) {
            throw new IllegalArgumentException("material cannot be null");
        }
        if (receivers.containsKey(material)) {
            return;
        }
        for (String name : OWNED_PARAMETERS) {
            if (!isOptionalParameter(name) && material.getMaterialDef().getMaterialParam(name) == null) {
                throw new IllegalArgumentException("Material definition does not declare " + name);
            }
        }
        MatParam enabled = material.getParam(ENABLED);
        if (enabled != null && Boolean.TRUE.equals(enabled.getValue())) {
            throw new IllegalArgumentException("Material already has directional visibility enabled");
        }
        Map<String, MatParam> saved = new HashMap<>();
        for (String name : OWNED_PARAMETERS) {
            MatParam old = material.getParam(name);
            if (old != null) {
                saved.put(name, old.clone());
            }
        }
        claimReceiver(material);
        receivers.put(material, saved);
    }

    private static boolean isOptionalParameter(String name) {
        return "DirectionalShadowFilterMode".equals(name)
                || "DirectionalCascadeBlendFraction".equals(name)
                || "DirectionalCascadeBlendStarts".equals(name);
    }

    /** Returns the selected receiver filter kernel. */
    public ShadowFilterMode getShadowFilterMode() {
        return shadowFilterMode;
    }

    /**
     * Selects the receiver filter kernel. The legacy kernel is the default;
     * receiver definitions without the optional filter parameter keep that kernel.
     */
    public void setShadowFilterMode(ShadowFilterMode mode) {
        if (mode == null) {
            throw new IllegalArgumentException("shadow filter mode cannot be null");
        }
        shadowFilterMode = mode;
    }

    /** Returns the cascade blend band as a fraction of the preceding cascade interval. */
    public float getCascadeBlendFraction() {
        return cascadeBlendFraction;
    }

    /**
     * Sets the cascade blend band as a fraction of each preceding cascade interval.
     * A nonzero band expands each following cascade's shadow-camera frustum so
     * both maps cover the complete transition interval. This camera overlap is
     * renderer-wide; older receiver definitions without the optional blend
     * parameters remain valid and do not blend their shader visibility.
     *
     * @param fraction finite fraction from zero through 0.2
     */
    public void setCascadeBlendFraction(float fraction) {
        if (!Float.isFinite(fraction) || fraction < 0f || fraction > 0.2f) {
            throw new IllegalArgumentException("cascade blend fraction must be finite and between zero and 0.2");
        }
        cascadeBlendFraction = fraction;
    }

    /**
     * Returns the designated light of a registered receiver, or null if there
     * is no live registration. Lighting techniques use this identity when
     * packing a light batch; direction equality is not light identity.
     * Registration and lighting are confined to the rendering thread.
     *
     * @param material the receiver material
     * @return the registered light, or null
     */
    public static DirectionalLight getLightForReceiver(Material material) {
        synchronized (RECEIVER_CLAIMS) {
            for (ReceiverClaim claim : RECEIVER_CLAIMS) {
                if (claim.get() == material) {
                    DirectionalLightVisibilityRenderer owner = claim.owner.get();
                    return owner == null ? null : owner.light;
                }
            }
        }
        return null;
    }

    private void claimReceiver(Material material) {
        synchronized (RECEIVER_CLAIMS) {
            for (int i = RECEIVER_CLAIMS.size() - 1; i >= 0; i--) {
                ReceiverClaim claim = RECEIVER_CLAIMS.get(i);
                Material claimed = claim.get();
                if (claimed == null || claim.owner.get() == null) {
                    RECEIVER_CLAIMS.remove(i);
                } else if (claimed == material) {
                    throw new IllegalArgumentException("Material is already registered with another visibility renderer");
                }
            }
            RECEIVER_CLAIMS.add(new ReceiverClaim(material, this));
        }
    }

    private void releaseReceiver(Material material) {
        synchronized (RECEIVER_CLAIMS) {
            RECEIVER_CLAIMS.removeIf(claim -> claim.get() == null
                    || claim.owner.get() == null
                    || (claim.owner.get() == this && claim.get() == material));
        }
    }

    /**
     * Removes a receiver and restores the material values captured when it was
     * registered.
     *
     * @param material material to remove
     * @return true if the material was registered
     */
    public boolean removeReceiver(Material material) {
        Map<String, MatParam> saved = receivers.remove(material);
        if (saved == null) {
            return false;
        }
        releaseReceiver(material);
        restore(material, saved);
        return true;
    }

    private static void restore(Material material, Map<String, MatParam> saved) {
        for (String name : OWNED_PARAMETERS) {
            MatParam prior = saved.get(name);
            MatParam current = material.getParam(name);
            if (prior == null) {
                if (current != null) {
                    material.clearParam(name);
                }
            } else {
                material.setParam(name, prior.getVarType(), prior.getValue());
                material.getParam(name).setTypeCheckEnabled(prior.isTypeCheckEnabled());
            }
        }
    }

    /**
     * Returns the receiver comparison bias in normalized depth units.
     *
     * @return nonnegative comparison bias
     */
    public float getDepthBias() {
        return depthBias;
    }

    /**
     * Sets the receiver comparison bias in normalized depth units. A small
     * positive default reduces self-shadowing. Its world-space equivalent depends
     * on each cascade projection depth range.
     *
     * @param depthBias finite, nonnegative depth comparison bias
     */
    public void setDepthBias(float depthBias) {
        if (!Float.isFinite(depthBias) || depthBias < 0f) {
            throw new IllegalArgumentException("depthBias must be finite and nonnegative");
        }
        this.depthBias = depthBias;
    }

    @Override
    public void setLambda(float lambda) {
        if (!Float.isFinite(lambda) || lambda < 0 || lambda > 1) {
            throw new IllegalArgumentException("lambda must be finite and between zero and one");
        }
        super.setLambda(lambda);
    }

    @Override
    public void setShadowZExtend(float zFar) {
        if (!Float.isFinite(zFar) || zFar < 0) {
            throw new IllegalArgumentException("shadow extent must be finite and nonnegative");
        }
        super.setShadowZExtend(zFar);
    }

    @Override
    public void setShadowZFadeLength(float length) {
        if (!Float.isFinite(length) || length < 0) {
            throw new IllegalArgumentException("shadow fade must be finite and nonnegative");
        }
        super.setShadowZFadeLength(length);
    }

    @Override
    public void initialize(RenderManager rm, ViewPort vp) {
        if (viewPort != null && viewPort != vp) {
            throw new IllegalStateException("DirectionalLightVisibilityRenderer is already bound to another viewport");
        }
        if (!rm.getRenderer().getCaps().contains(Caps.GLSL150)
                && !rm.getRenderer().getCaps().contains(Caps.OpenGLES30)) {
            throw new UnsupportedOperationException("Directional visibility requires GLSL 150 or OpenGL ES 3.0");
        }
        checkFilterMode();
        super.initialize(rm, vp);
    }

    private void checkFilterMode() {
        if (edgeFilteringMode != EdgeFilteringMode.Bilinear) {
            throw new UnsupportedOperationException("Directional visibility requires the Bilinear depth-comparison setup");
        }
    }

    @Override
    protected void updateShadowCams(Camera viewCam) {
        if (light != null) {
            validateFrustumDepthRange(viewCam);
        }
        super.updateShadowCams(viewCam);
        if (light == null) {
            return;
        }
        float zFar = zFarOverride == 0f ? viewCam.getFrustumFar() : zFarOverride;
        float frustumNear = Math.max(viewCam.getFrustumNear(), 0.001f);
        PssmShadowUtil.updateFrustumSplits(splitsArray, frustumNear, zFar, lambda);
        setWorldSplits();
    }

    private void validateFrustumDepthRange(Camera viewCam) {
        float near = Math.max(viewCam.getFrustumNear(), 0.001f);
        float far = zFarOverride == 0f ? viewCam.getFrustumFar() : zFarOverride;
        if (!Float.isFinite(near) || !Float.isFinite(far) || far <= near) {
            throw new IllegalStateException("Directional visibility requires finite camera depths with far > near");
        }
    }

    private void setWorldSplits() {
        float last = splitsArray[nbShadowMaps];
        worldSplits.set(nbShadowMaps > 0 ? splitsArray[1] : last,
                nbShadowMaps > 1 ? splitsArray[2] : last,
                nbShadowMaps > 2 ? splitsArray[3] : last,
                nbShadowMaps > 3 ? splitsArray[4] : last);
        worldBlendStarts.set(worldSplits.r, worldSplits.g, worldSplits.b, worldSplits.a);
        for (int boundary = 1; boundary < nbShadowMaps; boundary++) {
            float split = splitsArray[boundary];
            float previous = splitsArray[boundary - 1];
            float start = calculateCascadeBlendStart(previous, split, cascadeBlendFraction);
            switch (boundary) {
                case 1:
                    worldBlendStarts.x = start;
                    break;
                case 2:
                    worldBlendStarts.y = start;
                    break;
                case 3:
                    worldBlendStarts.z = start;
                    break;
                default:
                    throw new IllegalStateException("Unsupported cascade boundary: " + boundary);
            }
        }
    }

    static float calculateCascadeBlendStart(float previousSplit, float split, float fraction) {
        return split - (split - previousSplit) * fraction;
    }

    @Override
    protected float getCascadeNearDepth(int shadowMapIndex, float splitNear) {
        if (shadowMapIndex == 0 || cascadeBlendFraction == 0f) {
            return splitNear;
        }
        return calculateCascadeBlendStart(splitsArray[shadowMapIndex - 1], splitNear, cascadeBlendFraction);
    }

    @Override
    protected GeometryList getOccludersToRender(
            int shadowMapIndex, GeometryList occluders) {
        GeometryList result = super.getOccludersToRender(shadowMapIndex, occluders);
        anyShadowCasters |= result.size() != 0;
        return result;
    }

    @Override
    public void postQueue(RenderQueue rq) {
        checkFilterMode();
        disableReceivers();
        activatedThisFrame = false;
        anyShadowCasters = false;
        DirectionalLight currentLight = light;
        if (currentLight == null) {
            skipPostPass = true;
            return;
        }
        validateSunDirection(currentLight);
        // The inherited intensity setter is final, so validate before native use.
        if (!Float.isFinite(shadowIntensity)) {
            throw new IllegalStateException("shadow intensity must be finite");
        }
        super.postQueue(rq);
        if (skipPostPass || !anyShadowCasters || currentLight != light) {
            return;
        }
        setWorldSplits();
        for (Material material : receivers.keySet()) {
            MatParam enabled = material.getParam(ENABLED);
            if (enabled != null && Boolean.TRUE.equals(enabled.getValue())) {
                throw new IllegalStateException("A receiver material is shared by directional visibility renderers");
            }
            setReceiverParameters(material);
            material.setBoolean(ENABLED, true);
        }
        activatedThisFrame = !receivers.isEmpty();
    }

    private static void validateSunDirection(DirectionalLight sun) {
        float x = sun.getDirection().x;
        float y = sun.getDirection().y;
        float z = sun.getDirection().z;
        float lengthSquared = x * x + y * y + z * z;
        if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)
                || !Float.isFinite(lengthSquared) || Math.abs(lengthSquared - 1f) > 1e-4f) {
            throw new IllegalStateException("Directional light direction must be finite and normalized");
        }
    }

    private void disableReceivers() {
        for (Material material : receivers.keySet()) {
            material.setBoolean(ENABLED, false);
        }
    }

    private void setReceiverParameters(Material material) {
        material.setBoolean("DirectionalHardwareShadows", getShadowCompareMode() == CompareMode.Hardware);
        material.setInt("DirectionalCascadeCount", nbShadowMaps);
        material.setFloat("DirectionalShadowMapSize", shadowMapSize);
        material.setFloat("DirectionalShadowBias", depthBias);
        material.setFloat("DirectionalShadowStrength", shadowIntensity);
        material.setColor("DirectionalShadowSplits", worldSplits);
        if (material.getMaterialDef().getMaterialParam("DirectionalShadowFilterMode") != null) {
            material.setInt("DirectionalShadowFilterMode", shadowFilterMode == ShadowFilterMode.PCF_5X5 ? 1 : 0);
        }
        boolean supportsBlend = material.getMaterialDef().getMaterialParam("DirectionalCascadeBlendFraction") != null
                && material.getMaterialDef().getMaterialParam("DirectionalCascadeBlendStarts") != null;
        if (supportsBlend) {
            material.setFloat("DirectionalCascadeBlendFraction", cascadeBlendFraction);
            material.setVector4("DirectionalCascadeBlendStarts", worldBlendStarts);
        }
        if (fadeInfo == null) {
            material.setVector2("DirectionalShadowFade", new Vector2f(0f, 0f));
        } else {
            material.setVector2("DirectionalShadowFade", fadeInfo);
        }
        for (int i = 0; i < 4; i++) {
            int source = Math.min(i, nbShadowMaps - 1);
            material.setTexture("DirectionalShadowMap" + i, shadowMaps[source]);
            material.setMatrix4("DirectionalLightViewProjectionMatrix" + i,
                    lightViewProjectionsMatrices[source]);
        }
    }

    @Override
    public void postFrame(FrameBuffer out) {
        // postFrame precedes the translucent queue in the viewport pipeline.
        if (activatedThisFrame) {
            disableReceivers();
            activatedThisFrame = false;
        }
    }

    @Override
    public void cleanup() {
        for (Map.Entry<Material, Map<String, MatParam>> entry : receivers.entrySet()) {
            restore(entry.getKey(), entry.getValue());
            releaseReceiver(entry.getKey());
        }
        receivers.clear();
        activatedThisFrame = false;
        if (renderManager != null && viewPort != null) {
            Renderer renderer = renderManager.getRenderer();
            for (FrameBuffer frameBuffer : shadowFB) {
                if (frameBuffer != null) {
                    renderer.deleteFrameBuffer(frameBuffer);
                }
            }
            for (Texture2D shadowMap : shadowMaps) {
                deleteImage(renderer, shadowMap.getImage());
            }
            deleteImage(renderer, dummyTex.getImage());
        }
        viewPort = null;
        renderManager = null;
        super.cleanup();
    }

    private static void deleteImage(Renderer renderer, Image image) {
        if (image != null) {
            renderer.deleteImage(image);
        }
    }

    @Override
    public Object jmeClone() {
        throw new UnsupportedOperationException("DirectionalLightVisibilityRenderer cannot be cloned");
    }

    @Override
    public void cloneFields(Cloner cloner, Object original) {
        throw new UnsupportedOperationException("DirectionalLightVisibilityRenderer cannot be cloned");
    }

    @Override
    public void read(JmeImporter importer) throws IOException {
        throw new IOException("DirectionalLightVisibilityRenderer cannot be deserialized");
    }

    @Override
    public void write(JmeExporter exporter) throws IOException {
        throw new IOException("DirectionalLightVisibilityRenderer cannot be serialized");
    }
}
