package com.anonymous.scrcpyx.stats;

import java.util.ArrayDeque;
import java.util.Deque;

public class HighPrecisionDelayEstimator {

    private static final int MIN_WINDOW_SIZE = 120; // ~2 seconds at 60fps
    private static final double SKEW_ALPHA = 1e-8;  // very slow skew adjust
    private static final long MAX_DELAY_MS = 5000;

    private boolean initialized = false;

    // Clock model
    private double skew = 1.0;
    private double offsetMs = 0;

    // Baseline network delay (minimum filter)
    private final Deque<Long> minWindow = new ArrayDeque<>();
    private long minDelayMs = Long.MAX_VALUE;

    // RTP-style jitter
    private double jitterMs = 0;
    private long lastTransit = 0;

    public static class Stats {
        public final long totalDelayMs;
        public final long baselineNetworkMs;
        public final long clockOffsetMs;
        public final double jitterMs;
        public final double skew;

        public Stats(long total, long base, long clock, double jitter, double skew) {
            this.totalDelayMs = total;
            this.baselineNetworkMs = base;
            this.clockOffsetMs = clock;
            this.jitterMs = jitter;
            this.skew = skew;
        }

        @Override
        public String toString() {
            return "Total=" + totalDelayMs +
                    "ms BaseNet=" + baselineNetworkMs +
                    "ms Clock=" + clockOffsetMs +
                    "ms Jitter=" + String.format("%.2f", jitterMs) +
                    "ms Skew=" + skew;
        }
    }

    public synchronized Stats update(long ptsUs) {

        long nowUs = System.nanoTime() / 1000;
        long arrivalMs = nowUs / 1000;
        long ptsMs = ptsUs / 1000;

        long observedDelay = arrivalMs - ptsMs;

        if (observedDelay < 0 || observedDelay > MAX_DELAY_MS) {
            return current();
        }

        if (!initialized) {
            offsetMs = observedDelay;
            initialized = true;
            lastTransit = observedDelay;
            return current();
        }

        // ===== Clock skew estimation (linear correction) =====
        double predictedArrival = ptsMs * skew + offsetMs;
        double error = arrivalMs - predictedArrival;

        offsetMs += 0.001 * error;     // adjust offset slowly
        skew += SKEW_ALPHA * error;    // adjust skew very slowly

        long adjustedDelay = (long) (arrivalMs - (ptsMs * skew + offsetMs));

        // ===== Minimum baseline filter =====
        minWindow.addLast(adjustedDelay);
        if (adjustedDelay < minDelayMs) {
            minDelayMs = adjustedDelay;
        }

        if (minWindow.size() > MIN_WINDOW_SIZE) {
            long removed = minWindow.removeFirst();
            if (removed == minDelayMs) {
//                minDelayMs = minWindow.stream().min(Long::compare).orElse(adjustedDelay);
            }
        }

        long networkDelay = adjustedDelay - minDelayMs;
        if (networkDelay < 0) networkDelay = 0;

        // ===== RTP-style jitter =====
        long transit = adjustedDelay;
        long d = Math.abs(transit - lastTransit);
        lastTransit = transit;
        jitterMs += (1.0 / 16.0) * (d - jitterMs);

        return new Stats(
                adjustedDelay,
                minDelayMs,
                (long) offsetMs,
                jitterMs,
                skew
        );
    }

    public synchronized Stats current() {
        return new Stats(
                minDelayMs,
                minDelayMs,
                (long) offsetMs,
                jitterMs,
                skew
        );
    }
}
