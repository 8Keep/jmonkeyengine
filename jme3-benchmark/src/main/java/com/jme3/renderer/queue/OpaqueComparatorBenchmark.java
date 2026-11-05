/*
 * Copyright (c) 2009-2026 jMonkeyEngine
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * * Redistributions of source code must retain the above copyright notice,
 *   this list of conditions and the following disclaimer.
 * * Redistributions in binary form must reproduce the above copyright
 *   notice, this list of conditions and the following disclaimer in the
 *   documentation and/or other materials provided with the distribution.
 *
 * * Neither the name of 'jMonkeyEngine' nor the names of its contributors may
 *   be used to endorse or promote products derived from this software without
 *   specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES.
 */
package com.jme3.renderer.queue;

import com.jme3.bounding.BoundingBox;
import com.jme3.material.*;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;
import com.jme3.renderer.RenderManager;
import com.jme3.system.NullRenderer;
import com.jme3.scene.*;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

/** Complete restored queue sorts with a live moving camera. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
public class OpaqueComparatorBenchmark {
    @Param({"64", "512"}) public int geometryCount;
    @Param({"1", "8"}) public int materialCount;
    @Param({"none", "box"}) public String bounds;
    private Camera camera;
    private GeometryList queue;
    private Geometry[] scrambled;
    private boolean alternate;
    private final Vector3f locationA = new Vector3f(1, -2, .25f), locationB = new Vector3f(-1, 2, -.25f);
    private final Vector3f directionA = new Vector3f(.35f, .1f, 1), directionB = new Vector3f(-.35f, .1f, 1);
    @Setup public void setup() {
        camera = new Camera(1280, 720);
        OpaqueComparator comparator = new OpaqueComparator();
        queue = new GeometryList(comparator);
        RenderManager manager = new RenderManager(new NullRenderer());
        manager.setPreferredLightMode(TechniqueDef.LightMode.Disable);
        java.util.Set<Integer> sortIds = new java.util.HashSet<>();
        Material[] materials = new Material[materialCount];
        for (int i = 0; i < materialCount; i++) {
            MaterialDef definition = new MaterialDef(null, "benchmark-" + i);
            TechniqueDef technique = new TechniqueDef(TechniqueDef.DEFAULT_TECHNIQUE_NAME, i);
            technique.setLightMode(TechniqueDef.LightMode.Disable);
            definition.addTechniqueDef(technique);
            materials[i] = new Material(definition);
            materials[i].selectTechnique(TechniqueDef.DEFAULT_TECHNIQUE_NAME, manager);
            sortIds.add(materials[i].getSortId());
        }
        if (sortIds.size() != materialCount || sortIds.contains(-1)) {
            throw new IllegalStateException("Expected distinct initialized material sort groups");
        }
        scrambled = new Geometry[geometryCount];
        for (int i = 0; i < geometryCount; i++) {
            int index = i * 37 % geometryCount;
            Geometry geometry = new Geometry("geometry-" + index, new Mesh());
            geometry.setMaterial(materials[index % materialCount]);
            if (bounds.equals("box")) geometry.setModelBound(new BoundingBox(Vector3f.ZERO, 1, 1, 1));
            geometry.setLocalTranslation(index % 9, index % 5, 1 + index);
            geometry.updateGeometricState(); scrambled[i] = geometry; queue.add(geometry);
        }
        camera.setLocation(locationA); camera.lookAtDirection(directionA, Vector3f.UNIT_Y);
        queue.setCamera(camera); queue.sort();
        for (int i = 1; i < geometryCount; i++) {
            Geometry previous = queue.get(i - 1), current = queue.get(i);
            int compared = Integer.compare(previous.getMaterial().getSortId(), current.getMaterial().getSortId());
            if (compared > 0 || compared == 0 && previous.queueDistance > current.queueDistance) {
                throw new IllegalStateException("Queue order preflight failed");
            }
        }
    }
    @Benchmark public void restoreAndSort(Blackhole bh) {
        alternate = !alternate;
        camera.setLocation(alternate ? locationA : locationB);
        camera.lookAtDirection(alternate ? directionA : directionB, Vector3f.UNIT_Y);
        queue.setCamera(camera);
        for (int i = 0; i < scrambled.length; i++) {
            scrambled[i].queueDistance = Float.NEGATIVE_INFINITY;
            queue.set(i, scrambled[i]);
        }
        queue.sort();
        bh.consume(queue.get(0)); bh.consume(queue.get(geometryCount / 2)); bh.consume(queue.get(geometryCount - 1));
    }
}
