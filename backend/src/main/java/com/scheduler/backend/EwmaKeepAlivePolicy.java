package com.scheduler.backend;

public class EwmaKeepAlivePolicy implements KeepAlivePolicy {
    private final double alpha, multiplier;
    private final int minKeep, maxKeep, fallback;
    private long last = -1;
    private double ewmaGap = -1;

    public EwmaKeepAlivePolicy(double alpha, double multiplier, int minKeep, int maxKeep, int fallback) {
        this.alpha = alpha; this.multiplier = multiplier;
        this.minKeep = minKeep; this.maxKeep = maxKeep; this.fallback = fallback;
    }

    @Override public void onInvocation(long t) {
        if (last >= 0) {
            double gap = t - last;
            ewmaGap = ewmaGap < 0 ? gap : alpha * gap + (1 - alpha) * ewmaGap;
        }
        last = t;
    }

    @Override public int keepAliveMinutes() {
        if (ewmaGap < 0) return fallback;               // no history yet
        int k = (int) Math.ceil(multiplier * ewmaGap);  // wait ~multiplier x typical gap
        return Math.max(minKeep, Math.min(maxKeep, k));
    }
}