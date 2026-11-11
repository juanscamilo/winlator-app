package com.winlator.core;

import java.util.concurrent.atomic.AtomicLong;

/** Lightweight counters for the diagnostic report (guest FPS, compositor cost). */
public abstract class PerfStats {
    private static final AtomicLong guestFrames = new AtomicLong();
    private static final AtomicLong compositorFrames = new AtomicLong();
    private static final AtomicLong compositorNanos = new AtomicLong();
    private static volatile long compositorMaxNanos = 0;
    private static volatile long windowStart = System.nanoTime();

    public static void onGuestFrame() {
        guestFrames.incrementAndGet();
    }

    public static void onCompositorFrame(long durationNanos) {
        compositorFrames.incrementAndGet();
        compositorNanos.addAndGet(durationNanos);
        if (durationNanos > compositorMaxNanos) compositorMaxNanos = durationNanos;
    }

    public static synchronized void reset() {
        guestFrames.set(0);
        compositorFrames.set(0);
        compositorNanos.set(0);
        compositorMaxNanos = 0;
        windowStart = System.nanoTime();
    }

    /** Human-readable summary of the window since the last reset. */
    public static synchronized String summary() {
        double seconds = Math.max(0.001, (System.nanoTime() - windowStart) / 1e9);
        long frames = compositorFrames.get();
        double avgMs = frames > 0 ? compositorNanos.get() / 1e6 / frames : 0;
        return String.format(java.util.Locale.US,
            "window=%.1fs guestFPS=%.1f compositorFPS=%.1f compositorAvg=%.2fms compositorMax=%.2fms",
            seconds, guestFrames.get() / seconds, frames / seconds, avgMs, compositorMaxNanos / 1e6);
    }
}
