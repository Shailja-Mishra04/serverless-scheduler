package com.scheduler.backend;

/**
 * Simplified hybrid histogram policy (Shahrad et al., USENIX ATC 2020).
 * Keeps a histogram of gaps between calls. Unloads the container until the
 * head (5th percentile) of the gaps, then keeps it warm until the tail (99th).
 * Falls back to a fixed keep-alive when the histogram is not representative.
 */
public class HybridHistogramPolicy implements KeepAlivePolicy {
    private final int range, minSamples, maxEnd, fallback;
    private final double headP, tailP, margin, cvThreshold;
    private final long[] bins;          // bins[g] = number of gaps of g minutes, 1..range
    private long samples = 0, outOfRange = 0, last = -1;
    private int pre = 0, keep;

    public HybridHistogramPolicy(int range, int minSamples, double headP, double tailP,
                                 double margin, double cvThreshold, int maxEnd, int fallback) {
        this.range = range; this.minSamples = minSamples;
        this.headP = headP; this.tailP = tailP; this.margin = margin;
        this.cvThreshold = cvThreshold; this.maxEnd = maxEnd; this.fallback = fallback;
        this.bins = new long[range + 1];
        this.keep = fallback;
    }

    @Override public void onInvocation(long t) {
        if (last >= 0) {
            long g = t - last;
            if (g <= range) { bins[(int) g]++; samples++; } else outOfRange++;
        }
        last = t;
        recompute();
    }

    private void recompute() {
        if (samples < minSamples || outOfRange > samples || cv() < cvThreshold) {
            pre = 0; keep = fallback;                 // not representative: plain fixed timeout
            return;
        }
        int head = percentileBin(headP), tail = percentileBin(tailP);
        int end = Math.max(1, Math.min(maxEnd, (int) Math.ceil(tail * (1 + margin))));
        pre = Math.min((int) Math.floor(head * (1 - margin)), end - 1);
        keep = end - pre;
    }

    private int percentileBin(double p) {
        long target = (long) Math.ceil(p * samples), seen = 0;
        for (int g = 1; g <= range; g++) {
            seen += bins[g];
            if (seen >= target) return g;
        }
        return range;
    }

    /** Coefficient of variation of the bin counts: high = peaked (predictable) histogram. */
    private double cv() {
        double mean = (double) samples / range, var = 0;
        for (int g = 1; g <= range; g++) var += (bins[g] - mean) * (bins[g] - mean);
        return Math.sqrt(var / range) / mean;
    }

    @Override public int preWarmMinutes() { return pre; }
    @Override public int keepAliveMinutes() { return keep; }
}