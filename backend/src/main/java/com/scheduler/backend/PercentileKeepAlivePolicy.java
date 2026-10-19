package com.scheduler.backend;

import java.util.Arrays;

public class PercentileKeepAlivePolicy implements KeepAlivePolicy {
    private final double percentile;          // 0..1, e.g. 0.9
    private final int window, minGaps, minKeep, maxKeep, fallback;
    private final double margin;
    private final long[] gaps;
    private int count = 0, next = 0;
    private long last = -1;

    public PercentileKeepAlivePolicy(double percentile, int window, int minGaps,
                                     int minKeep, int maxKeep, int fallback, double margin) {
        this.percentile = percentile; this.window = window; this.minGaps = minGaps;
        this.minKeep = minKeep; this.maxKeep = maxKeep; this.fallback = fallback;
        this.margin = margin;
        this.gaps = new long[window];
    }

    @Override public void onInvocation(long t) {
        if (last >= 0) {
            gaps[next] = t - last;
            next = (next + 1) % window;
            if (count < window) count++;
        }
        last = t;
    }

    @Override public int keepAliveMinutes() {
        if (count < minGaps) return fallback;            // not enough history yet
        long[] a = Arrays.copyOf(gaps, count);
        Arrays.sort(a);
        int idx = (int) Math.ceil(percentile * count) - 1;
        idx = Math.max(0, Math.min(count - 1, idx));
        int k = (int) Math.ceil(margin * a[idx]);
        return Math.max(minKeep, Math.min(maxKeep, k));
    }
}