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
package com.jme3.scene.instancing;

import com.jme3.bounding.BoundingVolume;
import com.jme3.export.JmeExporter;
import com.jme3.export.JmeImporter;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.scene.VertexBuffer.Type;
import com.jme3.shader.bufferobject.BufferObject;
import com.jme3.util.clone.Cloner;
import java.io.IOException;

/**
 * A scene-graph geometry drawn with one indexed indirect command stored in a
 * renderer-local buffer object. The command is GPU-owned and is never read
 * back by the scene graph.
 *
 * <p>The supplied model bound must conservatively contain every possible
 * result of the command. Keep it current when GPU command data changes so
 * frustum culling remains correct. Ordinary Geometry transforms, materials,
 * and material overrides apply to this batch. The command buffer and this
 * runtime geometry cannot be cloned or serialized.
 * CPU mesh collision does not represent the displaced or instanced GPU batch;
 * applications must query their authoritative source for collision.
 * Commands and instance records are view-specific: a shadow view needs its own
 * caster cover, including relevant instances outside the main camera frustum.
 */
public class IndirectGeometry extends Geometry {

    private BufferObject commandBuffer;
    private int commandOffset;
    private BoundingVolume conservativeBound;

    /** For Savable construction only. Instances must be configured before use. */
    public IndirectGeometry() {
        super();
        setBatchHint(BatchHint.Never);
    }

    /**
     * Creates an indirect batch with an explicit conservative model-space bound.
     *
     * @param name geometry name
     * @param mesh indexed mesh to draw
     * @param commandBuffer renderer-local buffer containing the command
     * @param commandOffset byte offset of the 20-byte command
     * @param conservativeBound caller-provided bound for the complete batch
     */
    public IndirectGeometry(String name, Mesh mesh, BufferObject commandBuffer,
                            int commandOffset, BoundingVolume conservativeBound) {
        super(name, requireIndexedMesh(mesh));
        setBatchHint(BatchHint.Never);
        setDrawCommand(commandBuffer, commandOffset);
        setModelBound(conservativeBound);
    }

    private static Mesh requireIndexedMesh(Mesh mesh) {
        if (mesh == null) {
            throw new IllegalArgumentException("mesh cannot be null");
        }
        if (mesh.getBuffer(Type.Index) == null) {
            throw new IllegalArgumentException("IndirectGeometry requires an indexed mesh");
        }
        return mesh;
    }

    @Override
    public void setMesh(Mesh mesh) {
        super.setMesh(requireIndexedMesh(mesh));
    }

    /**
     * Returns the renderer-local buffer containing this geometry's command.
     *
     * @return command buffer
     */
    public BufferObject getCommandBuffer() {
        return commandBuffer;
    }

    /**
     * Returns the byte offset of the 20-byte indexed indirect command.
     *
     * @return command byte offset
     */
    public int getCommandOffset() {
        return commandOffset;
    }

    /**
     * Sets the command buffer and aligned byte offset used for rendering.
     * The renderer validates the buffer range at draw time. The producer owns
     * command values and instance-data bounds; these are not read back.
     *
     * @param commandBuffer renderer-local command buffer
     * @param commandOffset byte offset of the 20-byte command
     * @throws IllegalArgumentException if the buffer is null or the offset is
     * negative or not four-byte aligned
     */
    public void setDrawCommand(BufferObject commandBuffer, int commandOffset) {
        if (commandBuffer == null) {
            throw new IllegalArgumentException("commandBuffer cannot be null");
        }
        if (commandOffset < 0 || (commandOffset & 3) != 0) {
            throw new IllegalArgumentException("commandOffset must be non-negative and four-byte aligned");
        }
        this.commandBuffer = commandBuffer;
        this.commandOffset = commandOffset;
    }

    /**
     * Returns the caller-provided conservative model-space bound.
     *
     * @return conservative batch bound, or null before configuration
     */
    @Override
    public BoundingVolume getModelBound() {
        return conservativeBound;
    }

    /**
     * Sets the conservative bound used for culling this complete batch.
     * This does not derive a bound from the mesh, because command contents may
     * change on the GPU without being visible to the CPU.
     *
     * @param modelBound conservative model-space bound
     * @throws IllegalArgumentException if modelBound is null
     */
    @Override
    public void setModelBound(BoundingVolume modelBound) {
        if (modelBound == null) {
            throw new IllegalArgumentException("A conservative batch bound is required");
        }
        conservativeBound = modelBound.clone(null);
        worldBound = null;
        setBoundRefresh();
    }

    /**
     * Refreshes the world bound from the explicit bound. Mesh vertices do not
     * describe the full GPU-generated batch.
     */
    @Override
    public void updateModelBound() {
        setBoundRefresh();
    }

    @Override
    protected void updateWorldBound() {
        super.updateWorldBound();
        if (conservativeBound == null) {
            throw new IllegalStateException("IndirectGeometry requires a conservative batch bound");
        }
        if (ignoreTransform) {
            worldBound = conservativeBound.clone(worldBound);
        } else {
            worldBound = conservativeBound.transform(worldTransform, worldBound);
        }
    }

    @Override
    public void cloneFields(Cloner cloner, Object original) {
        throw new UnsupportedOperationException("IndirectGeometry is runtime GPU state and cannot be cloned");
    }

    @Override
    public void write(JmeExporter exporter) throws IOException {
        throw new IOException("IndirectGeometry is runtime GPU state and cannot be serialized");
    }

    @Override
    public void read(JmeImporter importer) throws IOException {
        throw new IOException("IndirectGeometry is runtime GPU state and cannot be deserialized");
    }
}
