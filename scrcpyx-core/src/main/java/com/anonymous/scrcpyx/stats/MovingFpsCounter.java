package com.anonymous.scrcpyx.stats;

public class MovingFpsCounter {

    private long lastTimestampNs = -1;
    private double smoothedFps = 0.0;

    // smoothing factor (0.05 ~ stable, 0.2 ~ reactive)
    private final double alpha;

    public MovingFpsCounter() {
        this(0.2);
    }

    public MovingFpsCounter(double alpha) {
        this.alpha = alpha;
    }

    public void onFrame(long packetTimestampUs) {
        if (lastTimestampNs < 0) {
            lastTimestampNs = packetTimestampUs;
            return;
        }

        long deltaNs = packetTimestampUs - lastTimestampNs;
        lastTimestampNs = packetTimestampUs;

        if (deltaNs <= 0) {
            return; // ignore invalid
        }

        double instantFps = 1e6 / deltaNs;

        if (smoothedFps == 0.0) {
            smoothedFps = instantFps;
        } else {
            smoothedFps = alpha * instantFps + (1.0 - alpha) * smoothedFps;
        }
    }

    public double getFps() {
        return smoothedFps;
    }
}
