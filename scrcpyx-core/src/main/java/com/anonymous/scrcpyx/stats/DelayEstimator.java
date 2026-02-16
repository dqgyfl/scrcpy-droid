package com.anonymous.scrcpyx.stats;

public class DelayEstimator {

    // ===== Tunable parameters =====
    private static final double CLOCK_ALPHA = 0.001;   // slow drift correction
    private static final double NETWORK_ALPHA = 0.05;  // fast jitter smoothing
    private static final long MAX_DELAY_MS = 5000;

    private boolean initialized = false;

    private double clockOffsetMs;
    private double networkDelayMs;
    private double variance;

    public static class Status {
        public final long totalDelayMs;
        public final long clockOffsetMs;
        public final long networkDelayMs;
        public final long jitterMs;

        public Status(long total, long clock, long net, long jitter) {
            this.totalDelayMs = total;
            this.clockOffsetMs = clock;
            this.networkDelayMs = net;
            this.jitterMs = jitter;
        }

        @Override
        public String toString() {
            return "Total=" + totalDelayMs +
                    "ms Clock=" + clockOffsetMs +
                    "ms Net=" + networkDelayMs +
                    "ms Jitter=" + jitterMs + "ms";
        }
    }

    /**
     * Call this for every incoming frame.
     * ptsUs must be in microseconds and from same clock base as sender.
     */
    public synchronized void onFrameArrived(long ptsUs) {

        long nowUs = System.nanoTime() / 1000;
        long observedDelayMs = (nowUs - ptsUs) / 1000;

        if (observedDelayMs < 0 || observedDelayMs > MAX_DELAY_MS) {
            return; // ignore bad sample
        }

        if (!initialized) {
            clockOffsetMs = observedDelayMs;
            networkDelayMs = 0;
            variance = 0;
            initialized = true;
            return;
        }

        double diff = observedDelayMs - clockOffsetMs;

        // Outlier rejection (3 sigma rule)
        double stdDev = Math.sqrt(variance);
        if (stdDev > 0 && Math.abs(diff) > 3 * stdDev) {
            return;
        }

        // Slow clock drift update
        clockOffsetMs += CLOCK_ALPHA * diff;

        // Fast network jitter update
        double netSample = observedDelayMs - clockOffsetMs;
        networkDelayMs += NETWORK_ALPHA * (netSample - networkDelayMs);

        // Variance update (EMA version)
        variance += NETWORK_ALPHA * ((diff * diff) - variance);
    }

    /**
     * Retrieve current delay estimation.
     */
    public synchronized Status getCurrentStatus() {

        if (!initialized) {
            return new Status(0, 0, 0, 0);
        }

        long total = (long) (clockOffsetMs + networkDelayMs);
        long clock = (long) clockOffsetMs;
        long net = (long) networkDelayMs;
        long jitter = (long) Math.sqrt(variance);

        return new Status(total, clock, net, jitter);
    }
}
