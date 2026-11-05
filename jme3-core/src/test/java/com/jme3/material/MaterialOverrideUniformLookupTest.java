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

import com.jme3.asset.AssetManager;
import com.jme3.light.LightList;
import com.jme3.material.Material.BindUnits;
import com.jme3.material.TechniqueDef.LightMode;
import com.jme3.material.logic.TechniqueDefLogic;
import com.jme3.renderer.Caps;
import com.jme3.renderer.RenderManager;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.shader.DefineList;
import com.jme3.shader.Shader;
import com.jme3.shader.ShaderBufferBlock.BufferType;
import com.jme3.shader.VarType;
import com.jme3.shader.bufferobject.BufferObject;
import com.jme3.system.NullRenderer;
import com.jme3.texture.Image;
import com.jme3.texture.Texture;
import com.jme3.texture.Texture2D;
import com.jme3.texture.image.ColorSpace;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MaterialOverrideUniformLookupTest {
    @Test
    void preservesOverridePrecedenceAndInactiveParameters() {
        Fixture f = new Fixture();
        f.material.setFloat("Value", 1f);
        f.material.setFloat("Disabled", 7f);
        f.material.setFloat("Mismatch", 8f);
        f.material.setFloat("Untouched", 9f);
        f.shader.getUniform("m_Clear").setValue(VarType.Float, 13f);
        f.geometry.addMatParamOverride(new MatParamOverride(VarType.Float, "Value", 2f));
        f.geometry.addMatParamOverride(new MatParamOverride(VarType.Float, "Clear", null));
        MatParamOverride disabled = new MatParamOverride(VarType.Float, "Disabled", 3f);
        disabled.setEnabled(false);
        f.geometry.addMatParamOverride(disabled);
        f.geometry.addMatParamOverride(new MatParamOverride(VarType.Int, "Mismatch", 42));
        f.geometry.addMatParamOverride(new MatParamOverride(VarType.Float, "Unknown", 4f));
        f.manager.addForcedMatParam(new MatParamOverride(VarType.Float, "Value", 3f));
        f.render();
        assertEquals(3f, f.shader.getUniform("m_Value").getValue());
        assertEquals(0f, f.shader.getUniform("m_Clear").getValue());
        assertEquals(7f, f.shader.getUniform("m_Disabled").getValue());
        assertEquals(8f, f.shader.getUniform("m_Mismatch").getValue());
        assertEquals(9f, f.shader.getUniform("m_Untouched").getValue());
        assertFalse(f.shader.getUniformMap().containsKey("m_Unknown"));
        f.geometry.clearMatParamOverrides();
        f.manager.getForcedMatParams().clear();
        f.render();
        assertEquals(1f, f.shader.getUniform("m_Value").getValue());
        assertEquals(0f, f.shader.getUniform("m_Clear").getValue());
    }

    @Test
    void preservesTextureUnitAssignmentAndMaterialFallback() {
        Fixture f = new Fixture();
        Texture2D base = new Texture2D(1, 1, Image.Format.RGBA8);
        Texture2D world = new Texture2D(1, 1, Image.Format.RGBA8);
        Texture2D forced = new Texture2D(1, 1, Image.Format.RGBA8);
        f.material.setTexture("Texture", base);
        f.geometry.addMatParamOverride(new MatParamOverride(VarType.Texture2D, "Texture", world));
        f.manager.addForcedMatParam(new MatParamOverride(VarType.Texture2D, "Texture", forced));
        f.render();
        assertEquals(1, f.shader.getUniform("m_Texture").getValue());
        assertEquals(2, f.renderer.textures.size());
        assertSame(world, f.renderer.textures.get(0));
        assertSame(forced, f.renderer.textures.get(1));
        f.geometry.clearMatParamOverrides();
        f.manager.getForcedMatParams().clear();
        f.renderer.textures.clear();
        f.render();
        assertEquals(0, f.shader.getUniform("m_Texture").getValue());
        assertEquals(1, f.renderer.textures.size());
        assertSame(base, f.renderer.textures.get(0));
    }

    @Test
    void preservesBufferBindingsAndUniformMapEntries() {
        Fixture f = new Fixture();
        BufferObject worldUniform = new BufferObject();
        BufferObject worldStorage = new BufferObject();
        BufferObject forcedUniform = new BufferObject();
        BufferObject forcedStorage = new BufferObject();
        f.geometry.addMatParamOverride(new MatParamOverride(VarType.UniformBufferObject, "UniformBlock", worldUniform));
        f.geometry.addMatParamOverride(new MatParamOverride(VarType.ShaderStorageBufferObject, "StorageBlock", worldStorage));
        f.manager.addForcedMatParam(new MatParamOverride(VarType.UniformBufferObject, "UniformBlock", forcedUniform));
        f.manager.addForcedMatParam(new MatParamOverride(VarType.ShaderStorageBufferObject, "StorageBlock", forcedStorage));
        assertFalse(f.shader.getUniformMap().containsKey("m_UniformBlock"));
        for (int pass = 0; pass < 2; pass++) {
            f.render();
            assertEquals(2, f.renderer.uniformBuffers.size());
            assertEquals(2, f.renderer.storageBuffers.size());
            assertSame(worldUniform, f.renderer.uniformBuffers.get(0));
            assertSame(worldStorage, f.renderer.storageBuffers.get(1));
            assertSame(forcedUniform, f.renderer.uniformBuffers.get(2));
            assertSame(forcedStorage, f.renderer.storageBuffers.get(3));
            assertSame(forcedUniform, f.shader.getBufferBlock("m_UniformBlock").getBufferObject());
            assertSame(forcedStorage, f.shader.getBufferBlock("m_StorageBlock").getBufferObject());
            assertEquals(BufferType.UniformBufferObject, f.shader.getBufferBlock("m_UniformBlock").getType());
            assertEquals(BufferType.ShaderStorageBufferObject, f.shader.getBufferBlock("m_StorageBlock").getType());
            assertTrue(f.shader.getUniformMap().containsKey("m_UniformBlock"));
            assertTrue(f.shader.getUniformMap().containsKey("m_StorageBlock"));
        }
    }

    private static class Fixture {
        final Shader shader = new Shader();
        final ObservingRenderer renderer = new ObservingRenderer();
        final RenderManager manager = new RenderManager(renderer);
        final Geometry geometry = new Geometry("geometry", new Mesh());
        final LightList lights = new LightList(geometry);
        final Material material;

        Fixture() {
            manager.setPreferredLightMode(LightMode.Disable);
            MaterialDef definition = new MaterialDef(null, "test");
            String[] names = {"Value", "Clear", "Disabled", "Mismatch", "Untouched"};
            for (int i = 0; i < names.length; i++) {
                definition.addMaterialParam(VarType.Float, names[i], null);
                shader.getUniform("m_" + names[i]).setLocation(i);
            }
            definition.addMaterialParamTexture(VarType.Texture2D, "Texture", ColorSpace.Linear, null);
            shader.getUniform("m_Texture").setLocation(names.length);
            definition.addMaterialParam(VarType.UniformBufferObject, "UniformBlock", null);
            definition.addMaterialParam(VarType.ShaderStorageBufferObject, "StorageBlock", null);
            TechniqueDef technique = new TechniqueDef(TechniqueDef.DEFAULT_TECHNIQUE_NAME, 1);
            technique.setLogic(new NoopLogic(shader));
            definition.addTechniqueDef(technique);
            material = new Material(definition);
            material.selectTechnique(TechniqueDef.DEFAULT_TECHNIQUE_NAME, manager);
        }

        void render() {
            geometry.updateGeometricState();
            material.render(geometry, lights, manager);
        }
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

    private static final class ObservingRenderer extends NullRenderer {
        private final EnumSet<Caps> caps = EnumSet.noneOf(Caps.class);
        final Map<Integer, Texture> textures = new LinkedHashMap<>();
        final Map<Integer, BufferObject> uniformBuffers = new LinkedHashMap<>();
        final Map<Integer, BufferObject> storageBuffers = new LinkedHashMap<>();

        @Override
        public EnumSet<Caps> getCaps() {
            return caps;
        }

        @Override
        public void setTexture(int unit, Texture texture) {
            textures.put(unit, texture);
        }

        @Override
        public void setUniformBufferObject(int unit, BufferObject buffer) {
            uniformBuffers.put(unit, buffer);
        }

        @Override
        public void setShaderStorageBufferObject(int unit, BufferObject buffer) {
            storageBuffers.put(unit, buffer);
        }
    }
}
