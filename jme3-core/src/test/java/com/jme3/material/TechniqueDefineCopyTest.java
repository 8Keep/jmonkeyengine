/*
 * Copyright (c) 2009-2026 jMonkeyEngine
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
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
package com.jme3.material;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jme3.asset.AssetManager;
import com.jme3.light.LightList;
import com.jme3.material.Material.BindUnits;
import com.jme3.material.logic.TechniqueDefLogic;
import com.jme3.renderer.Caps;
import com.jme3.renderer.RenderManager;
import com.jme3.scene.Geometry;
import com.jme3.shader.DefineList;
import com.jme3.shader.Shader;
import com.jme3.shader.VarType;
import com.jme3.system.NullRenderer;
import com.jme3.util.SafeArrayList;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;

class TechniqueDefineCopyTest {
    @Test
    void restoresBaseDefinesAfterOverridesAndShaderLogicChanges() {
        MaterialDef materialDef = new MaterialDef(null, "test");
        TechniqueDef definition = new TechniqueDef(TechniqueDef.DEFAULT_TECHNIQUE_NAME, 1);
        definition.addShaderParamDefine("Base", VarType.Int, "BASE");
        definition.addShaderParamDefine("Flag", VarType.Boolean, "FLAG");
        definition.addShaderParamDefine("Extra", VarType.Int, "EXTRA");
        definition.setLogic(new NoopLogic(new Shader()));
        Technique technique = new Technique(new Material(materialDef), definition);
        technique.notifyParamChanged("Base", VarType.Int, 1);
        technique.notifyParamChanged("Flag", VarType.Boolean, true);
        final int baseSortId = technique.getSortId();
        final RenderManager manager = new RenderManager(new NullRenderer());
        SafeArrayList<MatParamOverride> world = new SafeArrayList<>(MatParamOverride.class);
        world.add(new MatParamOverride(VarType.Int, "Base", 2));
        world.add(new MatParamOverride(VarType.Boolean, "Flag", false));
        SafeArrayList<MatParamOverride> forced = new SafeArrayList<>(MatParamOverride.class);
        forced.add(new MatParamOverride(VarType.Int, "Base", 3));
        forced.add(new MatParamOverride(VarType.Int, "Extra", 5));
        LightList lights = new LightList(null);
        EnumSet<Caps> caps = EnumSet.noneOf(Caps.class);
        technique.makeCurrent(manager, world, forced, lights, caps);
        DefineList dynamic = technique.getDynamicDefines();
        assertEquals(3, dynamic.getInt(0));
        assertFalse(dynamic.isSet(1));
        assertEquals(5, dynamic.getInt(2));
        assertEquals(baseSortId, technique.getSortId());
        technique.makeCurrent(manager, null, null, lights, caps);
        assertEquals(1, dynamic.getInt(0));
        assertTrue(dynamic.isSet(1));
        assertEquals(1, dynamic.getInt(1));
        assertFalse(dynamic.isSet(2));
        assertEquals(0, dynamic.getInt(2));
        dynamic.set(2, 99);
        technique.notifyParamChanged("Base", VarType.Int, 0);
        technique.notifyParamChanged("Flag", VarType.Boolean, false);
        technique.makeCurrent(manager, null, null, lights, caps);
        assertTrue(dynamic.isSet(0), "An enabled integer zero is distinct from an unset define");
        assertEquals(0, dynamic.getInt(0));
        assertFalse(dynamic.isSet(1));
        assertFalse(dynamic.isSet(2));
        assertEquals(0, dynamic.getInt(2));
    }

    private static final class NoopLogic implements TechniqueDefLogic {
        private final Shader shader;

        NoopLogic(Shader shader) {
            this.shader = shader;
        }

        @Override
        public Shader makeCurrent(AssetManager manager, RenderManager manager2,
                EnumSet<Caps> caps, LightList list, DefineList defines) {
            return shader;
        }

        @Override
        public void render(RenderManager manager, Shader shader, Geometry geometry,
                LightList list, BindUnits units) {
        }
    }

}
