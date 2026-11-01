/*
 * Copyright (c) 2009-2026 jMonkeyEngine
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 * * Redistributions of source code must retain the above copyright notice,
 *   this list of conditions and the following disclaimer.
 * * Redistributions in binary form must reproduce the above copyright
 *   notice, this list of conditions and the following disclaimer in the
 *   documentation and/or other materials provided with the distribution.
 * * Neither the name of 'jMonkeyEngine' nor the names of its contributors
 *   may be used to endorse or promote products derived from this software
 *   without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * 'AS IS' AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
 * TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
 * PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS;
 * OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR
 * OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package jme3test.terrain;

import com.jme3.app.Application;
import com.jme3.app.SimpleApplication;
import com.jme3.app.state.BaseAppState;
import com.jme3.font.BitmapText;
import com.jme3.input.FlyByCamera;
import com.jme3.input.InputManager;
import com.jme3.input.KeyInput;
import com.jme3.input.controls.ActionListener;
import com.jme3.input.controls.KeyTrigger;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.material.RenderState;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.Node;
import com.jme3.ui.Picture;

/** Manual fly-camera speed controls and a small lower-left help panel. */
final class HeightfieldExplorerControls extends BaseAppState {
    static final String SPEED_DOWN = "heightfield-explorer-speed-down";
    static final String SPEED_UP = "heightfield-explorer-speed-up";
    static final String BOOST_LEFT = "heightfield-explorer-boost-left";
    static final String BOOST_RIGHT = "heightfield-explorer-boost-right";
    private static final float MIN_SPEED = 2f;
    private static final float MAX_SPEED = 512f;
    private static final float BOOST_FACTOR = 4f;
    private static final float MAX_BOOSTED_SPEED = 1024f;

    private final FlyByCamera flyCam;
    private final boolean forestAvailable;
    private float baseSpeed;
    private boolean leftShift, rightShift;
    private InputManager inputManager;
    private Node guiNode;
    private Picture panel;
    private BitmapText text;
    private ActionListener listener;
    private boolean mappingsRegistered;
    private String displayedText;
    private int layoutWidth;

    HeightfieldExplorerControls(FlyByCamera flyCam, boolean forestAvailable) {
        if (flyCam == null) throw new IllegalArgumentException("fly camera is required");
        this.flyCam = flyCam;
        this.forestAvailable = forestAvailable;
    }

    @Override
    protected void initialize(Application application) {
        if (!(application instanceof SimpleApplication)) {
            throw new IllegalArgumentException("heightfield explorer controls require SimpleApplication");
        }
        SimpleApplication app = (SimpleApplication) application;
        inputManager = app.getInputManager();
        guiNode = app.getGuiNode();
        baseSpeed = clamp(flyCam.getMoveSpeed(), MIN_SPEED, MAX_SPEED);

        panel = new Picture("heightfield-explorer-controls-panel");
        Material background = new Material(app.getAssetManager(), "Common/MatDefs/Misc/Unshaded.j3md");
        background.setColor("Color", new ColorRGBA(.015f, .022f, .028f, .82f));
        background.getAdditionalRenderState().setBlendMode(RenderState.BlendMode.Alpha);
        panel.setMaterial(background);
        panel.setQueueBucket(RenderQueue.Bucket.Gui);

        text = new BitmapText(app.getAssetManager().loadFont("Interface/Fonts/Default.fnt"));
        text.setSize(15f);
        text.setColor(new ColorRGBA(.92f, .94f, .91f, 1f));
        text.setQueueBucket(RenderQueue.Bucket.Gui);

        listener = (name, pressed, tpf) -> onAction(name, pressed);
    }

    @Override
    protected void onEnable() {
        registerMappings();
        if (panel != null && !panel.hasAncestor(guiNode)) guiNode.attachChild(panel);
        if (text != null && !text.hasAncestor(guiNode)) guiNode.attachChild(text);
        applySpeed();
    }

    @Override
    protected void onDisable() {
        unregisterMappings();
        leftShift = false;
        rightShift = false;
        flyCam.setMoveSpeed(baseSpeed);
        detachHud();
        displayedText = null;
    }

    @Override
    protected void cleanup(Application application) {
        unregisterMappings();
        detachHud();
        leftShift = false;
        rightShift = false;
        flyCam.setMoveSpeed(baseSpeed);
        inputManager = null;
        guiNode = null;
        panel = null;
        text = null;
        listener = null;
        displayedText = null;
    }

    /** Clears held modifier state when the application loses input focus. */
    void clearBoost() {
        if (!leftShift && !rightShift) return;
        leftShift = false;
        rightShift = false;
        applySpeed();
    }

    float getBaseSpeed() {
        return baseSpeed;
    }

    boolean isBoosted() {
        return leftShift || rightShift;
    }

    private void registerMappings() {
        if (inputManager == null || mappingsRegistered) return;
        inputManager.addMapping(SPEED_DOWN, new KeyTrigger(KeyInput.KEY_LBRACKET));
        inputManager.addMapping(SPEED_UP, new KeyTrigger(KeyInput.KEY_RBRACKET));
        inputManager.addMapping(BOOST_LEFT, new KeyTrigger(KeyInput.KEY_LSHIFT));
        inputManager.addMapping(BOOST_RIGHT, new KeyTrigger(KeyInput.KEY_RSHIFT));
        inputManager.addListener(listener, SPEED_DOWN, SPEED_UP, BOOST_LEFT, BOOST_RIGHT);
        mappingsRegistered = true;
    }

    private void unregisterMappings() {
        if (inputManager == null || !mappingsRegistered) return;
        inputManager.removeListener(listener);
        removeMapping(SPEED_DOWN);
        removeMapping(SPEED_UP);
        removeMapping(BOOST_LEFT);
        removeMapping(BOOST_RIGHT);
        mappingsRegistered = false;
    }

    private void removeMapping(String mapping) {
        if (inputManager.hasMapping(mapping)) inputManager.deleteMapping(mapping);
    }

    private void onAction(String mapping, boolean pressed) {
        if (SPEED_DOWN.equals(mapping) && pressed) {
            baseSpeed = clamp(baseSpeed * .5f, MIN_SPEED, MAX_SPEED);
            applySpeed();
        } else if (SPEED_UP.equals(mapping) && pressed) {
            baseSpeed = clamp(baseSpeed * 2f, MIN_SPEED, MAX_SPEED);
            applySpeed();
        } else if (BOOST_LEFT.equals(mapping)) {
            leftShift = pressed;
            applySpeed();
        } else if (BOOST_RIGHT.equals(mapping)) {
            rightShift = pressed;
            applySpeed();
        }
    }

    private void applySpeed() {
        float speed = isBoosted() ? Math.min(baseSpeed * BOOST_FACTOR, MAX_BOOSTED_SPEED) : baseSpeed;
        flyCam.setMoveSpeed(speed);
        updateHud();
    }

    @Override
    public void update(float tpf) {
        if (getApplication().getCamera().getWidth() != layoutWidth) updateHud();
    }

    private void updateHud() {
        if (text == null || panel == null) return;
        int width = getApplication().getCamera().getWidth();
        String value = "WASD/QZ move | Left-drag look"
                + "\n[ ] speed | Shift boost | " + Math.round(flyCam.getMoveSpeed()) + " m/s"
                + (isBoosted() ? " (boost)" : "")
                + "\n" + (forestAvailable ? "4 grove | " : "") + "Esc close";
        if (value.equals(displayedText) && layoutWidth == width) return;
        layoutWidth = width;
        displayedText = value;
        text.setText(value);
        float panelWidth = Math.min(400f, Math.max(140f, width - 24f));
        float panelHeight = 69f;
        text.setSize(width < 400 ? 10f : 15f);
        panel.setWidth(panelWidth);
        panel.setHeight(panelHeight);
        panel.setLocalTranslation(12f, 12f, 0f);
        text.setLocalTranslation(21f, 12f + panelHeight - 9f, 1f);
    }

    private void detachHud() {
        if (panel != null && panel.getParent() != null) panel.removeFromParent();
        if (text != null && text.getParent() != null) text.removeFromParent();
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
