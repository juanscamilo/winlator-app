package com.winlator.widget;

import android.annotation.SuppressLint;
import android.content.Context;
import android.opengl.GLSurfaceView;
import android.view.Display;
import android.view.SurfaceHolder;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.winlator.core.DisplayPerformanceHelper;
import com.winlator.renderer.GLRenderer;
import com.winlator.xserver.XServer;

@SuppressLint("ViewConstructor")
public class XServerView extends GLSurfaceView {
    private final GLRenderer renderer;

    public XServerView(Context context, XServer xServer) {
        super(context);
        setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setEGLContextClientVersion(3);
        setEGLConfigChooser(8, 8, 8, 8, 0, 0);
        setPreserveEGLContextOnPause(true);
        renderer = new GLRenderer(this, xServer);
        setRenderer(renderer);
        setRenderMode(RENDERMODE_WHEN_DIRTY);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        super.surfaceCreated(holder);
        // Ask SurfaceFlinger to keep the panel at its maximum rate (120Hz on the Galaxy S23)
        // while the X server surface is visible, instead of letting it drop to 60Hz.
        Display display = getDisplay();
        if (display != null && getContext() instanceof android.app.Activity && DisplayPerformanceHelper.isHighRefreshRateEnabled((android.app.Activity)getContext())) {
            DisplayPerformanceHelper.setSurfaceFrameRate(holder.getSurface(), DisplayPerformanceHelper.getHighestRefreshRateMode(display).getRefreshRate());
        }
    }

    public GLRenderer getRenderer() {
        return renderer;
    }
}
