package com.anonymous.scrcpyx.stats;

public class SimpleDelayEstimator {

    // ===== Tunable parameters =====
    private static final double CLOCK_ALPHA = 0.001;
    private static final double NETWORK_ALPHA = 0.05;
    private static final long MAX_DELAY_MS = 5000;

    private boolean initialized = false;

    private double clockOffsetMs;
    private double networkDelayMs;
    private double variance;

    // ===== Called from decoder thread only =====
    public void onFrameArrived(long ptsUs) {

        long nowUs = System.nanoTime() / 1000;
        long observedDelayMs = (nowUs - ptsUs) / 1000;

        if (observedDelayMs < 0 || observedDelayMs > MAX_DELAY_MS) {
            return;
        }

        if (!initialized) {
            clockOffsetMs = observedDelayMs;
            networkDelayMs = 0;
            variance = 0;
            initialized = true;
            return;
        }

        double diff = observedDelayMs - clockOffsetMs;

        // 3-sigma outlier reject
        double stdDev = Math.sqrt(variance);
        if (stdDev > 0 && Math.abs(diff) > 3 * stdDev) {
            return;
        }

        // Slow clock drift tracking
        clockOffsetMs += CLOCK_ALPHA * diff;

        // Network jitter tracking
        double netSample = observedDelayMs - clockOffsetMs;
        networkDelayMs += NETWORK_ALPHA * (netSample - networkDelayMs);

        // Variance update (EMA)
        variance += NETWORK_ALPHA * ((diff * diff) - variance);
    }

    // ===== May be called from any thread =====
    public long getTotalDelayMs() {
        return (long) (clockOffsetMs + networkDelayMs);
    }

    public long getClockOffsetMs() {
        return (long) clockOffsetMs;
    }

    public long getNetworkDelayMs() {
        return (long) networkDelayMs;
    }

    public long getJitterMs() {
        return (long) Math.sqrt(variance);
    }
}

