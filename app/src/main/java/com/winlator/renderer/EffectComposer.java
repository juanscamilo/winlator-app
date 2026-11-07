package com.winlator.renderer;

import android.opengl.GLES20;

import android.util.Log;

import com.winlator.renderer.effects.Effect;
import com.winlator.renderer.material.ScreenMaterial;
import com.winlator.renderer.upscaler.SGSRMaterial;
import com.winlator.renderer.upscaler.Upscaler;

import java.util.ArrayList;

public class EffectComposer {
    private final GLRenderer renderer;
    private RenderTarget readBuffer = null;
    private RenderTarget writeBuffer = null;
    private final ArrayList<Effect> effects = new ArrayList<>();
    private Upscaler upscaler = Upscaler.BILINEAR;
    private SGSRMaterial sgsrMaterial;
    private RenderTarget sceneBuffer = null;
    private int sceneBufferWidth;
    private int sceneBufferHeight;
    private int bufferWidth;
    private int bufferHeight;

    public EffectComposer(GLRenderer renderer) {
        this.renderer = renderer;
    }

    public synchronized void addEffect(Effect effect) {
        if (!effects.contains(effect)) effects.add(effect);
        renderer.xServerView.requestRender();
    }

    public synchronized void removeEffect(Effect effect) {
        effects.remove(effect);
        renderer.xServerView.requestRender();
    }

    public synchronized  <T extends Effect> T getEffect(Class<T> effectClass) {
        for (Effect effect : effects) {
            if (effect.getClass() == effectClass) return (T)effect;
        }
        return null;
    }

    public synchronized boolean hasEffects() {
        return !effects.isEmpty();
    }

    /** True when the composer has to take over the frame (post effects or an upscaler). */
    public synchronized boolean isActive() {
        return !effects.isEmpty() || upscaler != Upscaler.BILINEAR;
    }

    public synchronized Upscaler getUpscaler() {
        return upscaler;
    }

    public synchronized void setUpscaler(Upscaler upscaler) {
        this.upscaler = upscaler != null ? upscaler : Upscaler.BILINEAR;
        renderer.xServerView.requestRender();
    }

    private void swapBuffers() {
        RenderTarget tmp = writeBuffer;
        writeBuffer = readBuffer;
        readBuffer = tmp;
    }

    private void renderEffect(Effect effect) {
        ScreenMaterial material = effect.getMaterial();
        material.use();
        renderer.quadVertices.bind(material.programId);

        material.setUniformVec2(material.uniforms.resolution, renderer.surfaceWidth, renderer.surfaceHeight);


        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, readBuffer.getTextureId());
        material.setUniformInt(material.uniforms.screenTexture, 0);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, renderer.quadVertices.count());
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
    }

    private void initBuffers() {
        // (Re)allocate when the surface size changes (rotation, PiP, ...).
        if (readBuffer != null && (bufferWidth != renderer.surfaceWidth || bufferHeight != renderer.surfaceHeight)) {
            readBuffer.destroy();
            writeBuffer.destroy();
            readBuffer = null;
            writeBuffer = null;
        }

        if (readBuffer == null) {
            readBuffer = new RenderTarget();
            readBuffer.allocateFramebuffer(renderer.surfaceWidth, renderer.surfaceHeight);
        }

        if (writeBuffer == null) {
            writeBuffer = new RenderTarget();
            writeBuffer.allocateFramebuffer(renderer.surfaceWidth, renderer.surfaceHeight);
        }

        bufferWidth = renderer.surfaceWidth;
        bufferHeight = renderer.surfaceHeight;
    }

    private void initSceneBuffer(int width, int height) {
        if (sceneBuffer != null && (sceneBufferWidth != width || sceneBufferHeight != height)) {
            sceneBuffer.destroy();
            sceneBuffer = null;
        }

        if (sceneBuffer == null) {
            sceneBuffer = new RenderTarget();
            sceneBuffer.allocateFramebuffer(width, height);
            sceneBufferWidth = width;
            sceneBufferHeight = height;
        }
    }

    /**
     * Draws the X screen at its native resolution into an offscreen buffer, then upscales it
     * to the view rectangle with SGSR. Returns false (bilinear fallback) if SGSR can't run.
     */
    private boolean renderUpscaled(int targetFramebuffer) {
        int sceneWidth = renderer.getScreenWidth();
        int sceneHeight = renderer.getScreenHeight();
        int[] viewport = renderer.getOutputViewport();

        // Nothing to upscale when the X screen is as large as the output.
        if (sceneWidth >= viewport[2] && sceneHeight >= viewport[3]) return false;

        if (sgsrMaterial == null) sgsrMaterial = new SGSRMaterial();
        try {
            sgsrMaterial.use();
        }
        catch (RuntimeException e) {
            Log.e("EffectComposer", "SGSR unavailable, falling back to bilinear", e);
            sgsrMaterial.destroy();
            upscaler = Upscaler.BILINEAR;
            return false;
        }

        initSceneBuffer(sceneWidth, sceneHeight);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, sceneBuffer.getFramebuffer());
        GLES20.glViewport(0, 0, sceneWidth, sceneHeight);
        renderer.drawScene();

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, targetFramebuffer);
        GLES20.glViewport(0, 0, renderer.surfaceWidth, renderer.surfaceHeight);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
        renderer.viewportNeedsUpdate = true;

        sgsrMaterial.use();
        renderer.quadVertices.bind(sgsrMaterial.programId);
        sgsrMaterial.setUniformBool(sgsrMaterial.uniforms.flipY, false);
        GLES20.glUniform4f(GLES20.glGetUniformLocation(sgsrMaterial.programId, "ViewportInfo"), 1.0f / sceneWidth, 1.0f / sceneHeight, sceneWidth, sceneHeight);

        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, sceneBuffer.getTextureId());
        sgsrMaterial.setUniformInt(sgsrMaterial.uniforms.screenTexture, 0);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, renderer.quadVertices.count());
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        GLES20.glEnable(GLES20.GL_BLEND);
        renderer.quadVertices.disable();
        return true;
    }

    public synchronized void render() {
        boolean hasEffects = !effects.isEmpty();
        if (hasEffects) initBuffers();
        int sceneTarget = hasEffects ? readBuffer.getFramebuffer() : 0;

        boolean upscaled = upscaler == Upscaler.SGSR && renderUpscaled(sceneTarget);
        if (!upscaled) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, sceneTarget);
            renderer.viewportNeedsUpdate = true;
            renderer.drawFrame();
        }

        for (Effect effect : effects) {
            boolean renderToScreen = effect == effects.get(effects.size()-1);

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, !renderToScreen ? writeBuffer.getFramebuffer() : 0);
            GLES20.glViewport(0, 0, renderer.surfaceWidth, renderer.surfaceHeight);
            renderer.viewportNeedsUpdate = true;
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

            renderEffect(effect);
            swapBuffers();
        }
    }
}
