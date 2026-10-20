package com.scheduler.backend;

/**
 * Confidence-gated policy. At each call it looks up the LSTM's predicted gap to the
 * next call. The prediction is trusted only when the function's recent prediction
 * errors (log scale) have a low variance; otherwise the fallback policy decides.
 */
public class LstmGatedPolicy implements KeepAlivePolicy {
    private static final int CAP = 1440;

    private final LstmPredictions preds;
    private final KeepAlivePolicy fallback;
    private final double maxVariance, k;
    private final int minErrors, minEnd, maxEnd;

    private final double[] errs;          // recent log-scale errors, ring buffer
    private int errCount = 0, errNext = 0;
    private long lastT = -1;
    private double lastPred = -1;
    private int pre = 0, keep = 0;
    private long trusted = 0, fellBack = 0;

    public LstmGatedPolicy(LstmPredictions preds, KeepAlivePolicy fallback, double maxVariance,
                           int minErrors, double k, int window, int minEnd, int maxEnd) {
        this.preds = preds; this.fallback = fallback; this.maxVariance = maxVariance;
        this.minErrors = minErrors; this.k = k; this.minEnd = minEnd; this.maxEnd = maxEnd;
        this.errs = new double[window];
    }

    @Override public void onInvocation(long t) {
        fallback.onInvocation(t);                  // fallback keeps learning either way

        if (lastPred > 0) {                        // how wrong was the previous prediction?
            double actual = Math.min(t - lastT, CAP);
            errs[errNext] = Math.log1p(actual) - Math.log1p(lastPred);
            errNext = (errNext + 1) % errs.length;
            if (errCount < errs.length) errCount++;
        }
        lastT = t;
        lastPred = preds.at(t);

        double var = variance();
        if (lastPred > 0 && errCount >= minErrors && var <= maxVariance) {
            double centre = Math.log1p(lastPred) + mean(), spread = k * Math.sqrt(var);
            int start = (int) Math.floor(Math.expm1(centre - spread));
            int end = (int) Math.ceil(Math.expm1(centre + spread));
            end = Math.min(maxEnd, Math.max(minEnd, end));
            pre = Math.max(0, Math.min(start, end - 1));
            keep = end - pre;
            trusted++;
        } else {
            pre = fallback.preWarmMinutes();
            keep = fallback.keepAliveMinutes();
            fellBack++;
        }
    }

    private double mean() {
        double s = 0;
        for (int i = 0; i < errCount; i++) s += errs[i];
        return errCount == 0 ? 0 : s / errCount;
    }

    private double variance() {
        if (errCount == 0) return 0;
        double m = mean(), s = 0;
        for (int i = 0; i < errCount; i++) s += (errs[i] - m) * (errs[i] - m);
        return s / errCount;
    }

    @Override public int preWarmMinutes() { return pre; }
    @Override public int keepAliveMinutes() { return keep; }

    public long trusted() { return trusted; }
    public long fellBack() { return fellBack; }
}