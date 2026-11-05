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
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.jme3.scene.control;

import com.jme3.light.PointLight;
import com.jme3.light.DirectionalLight;
import com.jme3.light.SpotLight;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import com.jme3.scene.Node;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LightControlTest {

    @Test
    void pointLightTracksInheritedWorldTranslationAcrossUpdates() {
        Node parent = new Node("parent");
        parent.setLocalTranslation(-2f, 4f, 6f);
        parent.setLocalRotation(new Quaternion().fromAngles(0.4f, -1.2f, 0.7f));
        parent.setLocalScale(1.5f, 0.5f, 2f);
        Node child = new Node("child");
        child.setLocalTranslation(3f, -5f, 7f);
        parent.attachChild(child);

        PointLight light = new PointLight();
        LightControl control = new LightControl(light);
        control.setSpatial(child);
        control.update(0f);
        assertEquals(child.getWorldTranslation(), light.getPosition());

        parent.setLocalTranslation(8f, -3f, 1f);
        child.setLocalTranslation(-4f, 2f, 9f);
        control.update(0f);
        assertEquals(child.getWorldTranslation(), light.getPosition());
    }
    @Test
    void pointPositionIsCopiedAndAxisSettingsDoNotAffectIt() {
        Node spatial = new Node("spatial");
        spatial.setLocalTranslation(3, -5, 7);
        PointLight light = new PointLight();
        LightControl control = new LightControl(light);
        control.setSpatial(spatial);
        for (LightControl.Axis axis : LightControl.Axis.values()) {
            for (boolean invert : new boolean[]{false, true}) {
                control.setAxisRotation(axis);
                control.setInvertAxisDirection(invert);
                control.update(0);
                assertEquals(new Vector3f(3, -5, 7), light.getPosition());
                org.junit.jupiter.api.Assertions.assertNotSame(spatial.getWorldTranslation(), light.getPosition());
                light.getPosition().set(99, 98, 97);
                assertEquals(new Vector3f(3, -5, 7), spatial.getWorldTranslation());
            }
        }
    }

    @Test
    void directionalAndSpotAxisControlsRetainTheirBehavior() {
        Node parent = new Node("parent");
        parent.setLocalRotation(new Quaternion().fromAngles(.4f, -1.2f, .7f));
        parent.setLocalScale(1.5f, .5f, 2);
        Node spatial = new Node("spatial");
        spatial.setLocalTranslation(3, -5, 7);
        spatial.setLocalRotation(new Quaternion().fromAngles(-.7f, .2f, 1.1f));
        parent.attachChild(spatial);
        DirectionalLight directional = new DirectionalLight();
        SpotLight spot = new SpotLight();
        LightControl control = new LightControl(directional);
        control.setSpatial(spatial);
        for (LightControl.Axis axis : LightControl.Axis.values()) {
            for (boolean invert : new boolean[]{false, true}) {
                parent.setLocalTranslation(axis.ordinal(), invert ? -4 : 3, 6);
                control.setAxisRotation(axis);
                control.setInvertAxisDirection(invert);
                control.setLight(directional);
                control.update(0);
                Vector3f expected = spatial.getWorldRotation().getRotationColumn(axis.ordinal());
                if (invert) {
                    expected.negateLocal();
                }
                assertEquals(expected, directional.getDirection());
                control.setLight(spot);
                control.update(0);
                assertEquals(expected, spot.getDirection());
                assertEquals(spatial.getWorldTranslation(), spot.getPosition());
            }
        }
    }

    @Test
    void reverseDisabledAndNullControlsRetainTheirBehavior() {
        Node spatial = new Node("spatial");
        PointLight light = new PointLight();
        light.setPosition(new Vector3f(3, -5, 7));
        LightControl control = new LightControl(light, LightControl.ControlDirection.LightToSpatial);
        control.setSpatial(spatial);
        control.update(0);
        assertEquals(light.getPosition(), spatial.getLocalTranslation());
        control.setEnabled(false);
        light.setPosition(new Vector3f(-2, 4, 6));
        control.update(0);
        assertEquals(new Vector3f(3, -5, 7), spatial.getLocalTranslation());
        control.setEnabled(true);
        control.update(0);
        assertEquals(light.getPosition(), spatial.getLocalTranslation());
        control.setLight(null);
        control.update(0);
        assertEquals(new Vector3f(-2, 4, 6), spatial.getLocalTranslation());
    }

}
