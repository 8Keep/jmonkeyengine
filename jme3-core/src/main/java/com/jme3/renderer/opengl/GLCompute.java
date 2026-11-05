/*
 * Copyright (c) 2009-2026 jMonkeyEngine
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * * Redistributions of source code must retain the above copyright notice,
 *   this list of conditions and the following disclaimer.
 * * Redistributions in binary form must reproduce the above copyright notice,
 *   this list of conditions and the following disclaimer in the documentation
 *   and/or other materials provided with the distribution.
 * * Neither the name of 'jMonkeyEngine' nor the names of its contributors may
 *   be used to endorse or promote products derived from this software without
 *   specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package com.jme3.renderer.opengl;

import java.nio.IntBuffer;

/**
 * Compute shader and indexed integer-query functions available in desktop
 * OpenGL 4.3 and OpenGL ES 3.1.
 */
public interface GLCompute {

    int GL_COMPUTE_SHADER = 0x91B9;
    int GL_MAX_COMPUTE_WORK_GROUP_COUNT = 0x91BE;
    int GL_MAX_COMPUTE_WORK_GROUP_SIZE = 0x91BF;
    int GL_MAX_COMPUTE_WORK_GROUP_INVOCATIONS = 0x90EB;
    int GL_COMPUTE_WORK_GROUP_SIZE = 0x8267;
    int GL_MAX_COMPUTE_SHADER_STORAGE_BLOCKS = 0x90DB;
    int GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS = 0x90DD;
    int GL_MAX_SHADER_STORAGE_BLOCK_SIZE = 0x90DE;

    int GL_UNIFORM_BARRIER_BIT = 0x00000004;
    int GL_TEXTURE_FETCH_BARRIER_BIT = 0x00000008;
    int GL_SHADER_STORAGE_BARRIER_BIT = 0x00002000;
    int GL_VERTEX_ATTRIB_ARRAY_BARRIER_BIT = 0x00000001;
    int GL_COMMAND_BARRIER_BIT = 0x00000040;
    int GL_BUFFER_UPDATE_BARRIER_BIT = 0x00000200;

    /** Launches one or more compute work groups. */
    void glDispatchCompute(int numGroupsX, int numGroupsY, int numGroupsZ);

    /** Orders prior shader writes against subsequent accesses of the selected types. */
    void glMemoryBarrier(int barriers);

    /** Reads an indexed integer state value. */
    default void glGetIntegerIndexed(int pname, int index, IntBuffer params) {
        throw new UnsupportedOperationException("Indexed integer queries are not supported");
    }
}
