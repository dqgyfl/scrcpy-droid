package com.anonymous.scrcpyx.stats;

public class WindowFpsCounter {

    private final long[] timestamps;
    private int index = 0;
    private int count = 0;

    public WindowFpsCounter(int windowSize) {
        timestamps = new long[windowSize];
    }

    public synchronized double onFrame(long ts) {
        timestamps[index] = ts;
        index = (index + 1) % timestamps.length;

        if (count < timestamps.length) {
            count++;
            return 0;
        }

        long oldest = timestamps[index];
        long newest = ts;

        return (timestamps.length - 1) * 1e9 / (newest - oldest);
    }
}
