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
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A
 * PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER
 * OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.jme3.terrain.heightfield;

/**
 * Read-only square heightfield on a canonical integer lattice. A source supplies
 * finite samples from -1 through cells+1 on both axes, including a one-sample
 * halo. Spacing and heights use the same physical units. A source may load tiles
 * on demand rather than retaining a complete heightmap.
 *
 * <p>All samples used for one hierarchy or page transaction must belong to one
 * immutable source revision. The owner supplies that revision to hierarchy
 * metadata and residency keys; this sampling interface does not own scheduling,
 * persistence or revision allocation. Coordinates are region-local integers;
 * an owner maps them to its long-valued world lattice before sampling and uses
 * region-relative cameras/transforms for rendering.
 */
public interface HeightfieldSource {
    /** Number of fine cells per edge, positive and no greater than 2^20. */
    int getCells();

    /** Positive finite physical spacing of canonical fine samples. */
    float getSampleSpacing();

    /** Finite height at a lattice point, including the complete fine halo. */
    float getHeight(int x, int z);
}
