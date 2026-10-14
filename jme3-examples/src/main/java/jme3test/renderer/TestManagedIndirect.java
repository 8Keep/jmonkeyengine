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
package jme3test.renderer;

import com.jme3.app.SimpleApplication;
import com.jme3.math.ColorRGBA;
import com.jme3.renderer.Caps;
import com.jme3.renderer.MemoryBarrier;
import com.jme3.renderer.Renderer;
import com.jme3.scene.Mesh;
import com.jme3.scene.shape.Quad;
import com.jme3.shader.Shader;
import com.jme3.shader.ShaderBufferBlock.BufferType;
import com.jme3.shader.VarType;
import com.jme3.shader.bufferobject.BufferObject;
import com.jme3.system.AppSettings;
import com.jme3.texture.FrameBuffer;
import com.jme3.texture.Image;
import com.jme3.util.BufferUtils;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;

/** Finite compute-written indexed indirect command and ordinary draw control. */
public final class TestManagedIndirect extends SimpleApplication {
    private static final int WIDTH = 16, HEIGHT = 4;
    private static final float[][] COLORS = {{.8f,.15f,.1f,1}, {.1f,.8f,.2f,1}, {.1f,.2f,.8f,1}, {.8f,.7f,.1f,1}};
    private final CountDownLatch finished = new CountDownLatch(1);
    private final Path output;
    private volatile Throwable failure;
    private int checks;
    private float maximumError;

    private TestManagedIndirect(Path output) { this.output = output; }

    public static void main(String[] args) throws Exception {
        TestManagedIndirect app = new TestManagedIndirect(Paths.get(args.length > 1 ? args[1] : "build/managed-indirect"));
        AppSettings settings = new AppSettings(true);
        settings.setRenderer(args.length > 0 ? args[0] : AppSettings.LWJGL_OPENGL45);
        settings.setResolution(320,240); settings.setAudioRenderer(null);
        settings.setGraphicsDebug(true); settings.setVSync(false);
        if (args.length > 2 && "instrumented".equals(args[2])) {
            settings.setGraphicsTiming(true); settings.setGraphicsTrace(true);
        }
        app.setSettings(settings); app.setShowSettings(false); app.start();
        if (!app.finished.await(45, TimeUnit.SECONDS)) {
            app.stop(false); throw new IllegalStateException("Indirect probe timed out", app.failure);
        }
        app.stop(true);
        if (app.failure != null) throw new IllegalStateException("Indirect probe failed", app.failure);
        System.out.println("MANAGED_INDIRECT_PASS checks=" + app.checks + " maxError=" + app.maximumError);
    }

    @Override public void simpleInitApp() {
        try { flyCam.setEnabled(false); runProbe(renderManager.getRenderer()); }
        catch (Throwable error) { handleError("indirect",error); }
        finally { finished.countDown(); }
    }

    @Override public void handleError(String message, Throwable error) {
        failure = error == null ? new IllegalStateException(message) : error;
        failure.printStackTrace(); finished.countDown();
    }

    private void runProbe(Renderer renderer) throws Exception {
        require(renderer.getCaps().contains(Caps.ComputeShader)
                && renderer.getCaps().contains(Caps.IndexedIndirectDraw)
                && renderer.getCaps().contains(Caps.UniformBufferObject), "required managed capabilities");
        boolean es = renderer.getCaps().contains(Caps.OpenGLES31);
        BufferObject commands = new BufferObject();
        BufferObject payload = new BufferObject();
        ByteBuffer colors = BufferUtils.createByteBuffer(64).order(ByteOrder.nativeOrder());
        FloatBuffer values = colors.asFloatBuffer();
        for (float[] color : COLORS) values.put(color);
        payload.setData(colors); payload.setBinding(0); payload.setUpdateNeeded();
        commands.setData(commandsData()); commands.setBinding(1); commands.setUpdateNeeded();
        Shader draw = graphics(es,
                "layout(std140) uniform m_Payload { vec4 colors[4]; };\n"
                + "uniform int m_Base; in vec3 inPosition; flat out vec4 color;\n"
                + "void main(){int record=gl_InstanceID+m_Base; color=colors[record];"
                + "gl_Position=vec4(-1.0+(float(record)+inPosition.x)*.5,inPosition.y-1.0,0,1);}",
                "flat in vec4 color; out vec4 outColor; void main(){outColor=color;}");
        draw.getBufferBlock("m_Payload").setBufferObject(BufferType.UniformBufferObject,payload);
        Shader decode = graphics(es,
                "in vec3 inPosition; void main(){gl_Position=vec4(inPosition.xy-vec2(1.0),0,1);}",
                "layout(std140) uniform m_Commands { uvec4 words[6]; };\n"
                + "out vec4 outColor; void main(){int n=int(gl_FragCoord.x);outColor=vec4(float(words[n/4][n%4]),0,0,1);}");
        decode.getBufferBlock("m_Commands").setBufferObject(BufferType.UniformBufferObject,commands);
        Shader compute = new Shader();
        compute.addSource(Shader.ShaderType.Compute,"indirect-commands",
                "layout(local_size_x=1) in; layout(std430,binding=1) buffer Commands { uint words[]; };\n"
                + "uniform int m_Phase; void main(){for(int command=0;command<4;command++){int p=1+command*5;"
                + "words[p]=6u;words[p+1]=0u;words[p+2]=0u;words[p+3]=0u;words[p+4]=0u;}"
                + "if(m_Phase==1)words[2]=4u;else if(m_Phase==2){words[2]=2u;words[7]=2u;}}", "",es?"GLSL310":"GLSL430");
        Mesh mesh = new Quad(1,2), decoderMesh = new Quad(2,2);
        FrameBuffer target = target(WIDTH,HEIGHT), oracle = target(24,1);
        renderer.setFrameBuffer(target); base(draw,0); renderer.setShader(draw);
        renderer.clearBuffers(true,false,false); renderer.renderMesh(mesh,0,4,null);
        pixels(renderer,target,false);
        renderer.clearBuffers(true,false,false);
        renderer.renderMeshIndirect(mesh,0,commands,4,null);
        pixels(renderer,target,false);
        decode(renderer,oracle,decoderMesh,decode,expectedWords(1));

        // Rejected calls must retain the existing draw and leave new GPU storage absent.
        BufferObject unallocated = new BufferObject(); unallocated.setData(commandsData());
        try {
            renderer.getCaps().remove(Caps.IndexedIndirectDraw);
            reject(UnsupportedOperationException.class,() -> renderer.renderMeshIndirect(mesh,0,unallocated,4,null));
            require(unallocated.getId()==-1,"unsupported preflight allocates no command storage");
        } finally { renderer.getCaps().add(Caps.IndexedIndirectDraw); }
        reject(IllegalArgumentException.class,() -> renderer.renderMeshIndirect(mesh,0,commands,1,null));
        reject(IllegalArgumentException.class,() -> renderer.renderMeshIndirect(mesh,0,commands,Integer.MAX_VALUE-3,null));

        renderer.setFixedShaderStorageBufferObject(1,commands);
        for (int phase=1; phase<=3;phase++) {
            compute.getUniform("m_Phase").setValue(VarType.Int,phase);
            renderer.dispatchCompute(compute,1,1,1);
            renderer.memoryBarrier(EnumSet.of(MemoryBarrier.Command,MemoryBarrier.UniformBuffer,MemoryBarrier.ShaderStorage));
            decode(renderer,oracle,decoderMesh,decode,expectedWords(phase));
            renderer.setFrameBuffer(target); renderer.setBackgroundColor(ColorRGBA.Black);
            renderer.clearBuffers(true,false,false); base(draw,0); renderer.setShader(draw);
            renderer.renderMeshIndirect(mesh,0,commands,4,null);
            if (phase==2) {
                base(draw,2); renderer.setShader(draw); renderer.renderMeshIndirect(mesh,0,commands,24,null);
            }
            pixels(renderer,target,phase==3);
            if (phase==2) capture(renderer,target);
        }
        // Dirty reupload of an already-bound command buffer must not retain a stale generic binding.
        commands.setData(commandsData()); commands.setUpdateNeeded();
        renderer.setFrameBuffer(target); renderer.clearBuffers(true,false,false);
        base(draw,0); renderer.setShader(draw); renderer.renderMeshIndirect(mesh,0,commands,4,null);
        pixels(renderer,target,false);
        renderer.deleteBuffer(commands); require(commands.getId()==-1,"retire bound command buffer");
        renderer.clearBuffers(true,false,false); renderer.renderMeshIndirect(mesh,0,commands,4,null);
        pixels(renderer,target,false);
        // Ordinary mesh attributes/programs remain usable after all indirect submissions.
        renderer.clearBuffers(true,false,false); renderer.renderMesh(mesh,0,4,null);
        pixels(renderer,target,false);
        Files.createDirectories(output);
        Files.write(output.resolve("summary.txt"),Arrays.asList("checks="+checks,"maximumError="+maximumError,
                "commands=96 bytes; record offsets4/24; reserved/baseVertex zero; GPU phase1/2/3 verified",
                "renderer="+renderer.getCaps()));
        renderer.setFrameBuffer(null);
    }

    private ByteBuffer commandsData() {
        ByteBuffer bytes = BufferUtils.createByteBuffer(96).order(ByteOrder.nativeOrder());
        int[] expected=expectedWords(1); bytes.asIntBuffer().put(expected); return bytes;
    }

    private int[] expectedWords(int phase) {
        int[] words=new int[24];words[0]=7;
        for(int c=0;c<4;c++)words[1+c*5]=6;
        if(phase==1)words[2]=4;
        else if(phase==2){words[2]=2;words[7]=2;}
        return words;
    }

    private Shader graphics(boolean es,String vertex,String fragment) {
        Shader shader=new Shader();String prefix=es?"precision highp float; precision highp int;\n":"";
        shader.addSource(Shader.ShaderType.Vertex,"indirect-draw",prefix+vertex,"",es?"GLSL300":"GLSL150");
        shader.addSource(Shader.ShaderType.Fragment,"indirect-draw",prefix+fragment,"",es?"GLSL300":"GLSL150");
        return shader;
    }

    private void base(Shader shader,int base){shader.getUniform("m_Base").setValue(VarType.Int,base);}

    private FrameBuffer target(int width,int height) {
        FrameBuffer fb=new FrameBuffer(width,height,1);
        fb.addColorTarget(FrameBuffer.FrameBufferTarget.newTarget(Image.Format.RGBA32F));return fb;
    }

    private FloatBuffer read(Renderer renderer,FrameBuffer target) {
        ByteBuffer bytes=BufferUtils.createByteBuffer(target.getWidth()*target.getHeight()*16).order(ByteOrder.nativeOrder());
        renderer.readFrameBufferWithFormat(target,bytes,Image.Format.RGBA32F);return bytes.asFloatBuffer();
    }

    private void decode(Renderer renderer,FrameBuffer fb,Mesh mesh,Shader shader,int[] expected) {
        renderer.setFrameBuffer(fb); renderer.setShader(shader); renderer.clearBuffers(true,false,false);
        renderer.renderMesh(mesh,0,1,null);FloatBuffer pixels=read(renderer,fb);
        for(int i=0;i<expected.length;i++)require(pixels.get(i*4)==expected[i],"GPU command word "+i);
    }

    private void pixels(Renderer renderer,FrameBuffer fb,boolean empty) {
        FloatBuffer pixels=read(renderer,fb);
        for(int y=0;y<HEIGHT;y++)for(int x=0;x<WIDTH;x++)for(int c=0;c<4;c++){
            float expected=empty?(c==3?1:0):COLORS[x/4][c];
            float error=Math.abs(pixels.get((y*WIDTH+x)*4+c)-expected);
            maximumError=Math.max(maximumError,error); require(error<1e-5,"indirect pixel "+x+","+y+","+c);
        }
    }

    private void capture(Renderer renderer,FrameBuffer fb) throws Exception {
        FloatBuffer pixels=read(renderer,fb);BufferedImage image=new BufferedImage(WIDTH,HEIGHT,BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<HEIGHT;y++)for(int x=0;x<WIDTH;x++){
            int p=(y*WIDTH+x)*4;int red=Math.round(pixels.get(p)*255),green=Math.round(pixels.get(p+1)*255),blue=Math.round(pixels.get(p+2)*255);
            image.setRGB(x,HEIGHT-y-1,0xff000000|(red<<16)|(green<<8)|blue);
        }
        Files.createDirectories(output);ImageIO.write(image,"png",output.resolve("indirect.png").toFile());
    }

    private void reject(Class<? extends RuntimeException> type,Runnable action) {
        try {action.run();}catch(RuntimeException error){require(type.isInstance(error),"rejection type "+error);return;}
        throw new IllegalStateException("Expected "+type.getSimpleName());
    }

    private void require(boolean ok,String message){if(!ok)throw new IllegalStateException(message);checks++;}
}
