package com.scheduler.backend;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LstmGatedPolicyTest {

    /** Predictions for calls at 0, gap, 2 x gap, ... all saying "next call in predGap minutes". */
    private static LstmPredictions preds(int calls, int gap, double predGap) {
        long[] t = new long[calls];
        double[] g = new double[calls];
        for (int i = 0; i < calls; i++) { t[i] = (long) i * gap; g[i] = predGap; }
        return new LstmPredictions(t, g);
    }

    // (predictions, fallback, maxVariance, minErrors, k, window, minEnd, maxEnd)
    private static LstmGatedPolicy gate(LstmPredictions p) {
        return new LstmGatedPolicy(p, new FixedKeepAlivePolicy(7), 0.25, 3, 2.0, 20, 1, 240);
    }

    @Test
    void predictionLookup() {
        LstmPredictions p = preds(3, 10, 10.0);
        assertEquals(10.0, p.at(20));
        assertEquals(-1, p.at(15));               // no prediction at that minute
        assertEquals(-1, LstmPredictions.EMPTY.at(0));
    }

    @Test
    void trustsAccuratePredictionsAfterEnoughErrors() {
        LstmGatedPolicy g = gate(preds(5, 10, 10.0));
        for (long t = 0; t <= 20; t += 10) g.onInvocation(t);
        assertEquals(7, g.keepAliveMinutes());    // calls 0, 10, 20: fewer than 3 errors -> fallback

        g.onInvocation(30);                       // 3 errors, all 0 -> trusted
        long start = g.preWarmMinutes(), end = start + g.keepAliveMinutes();
        assertTrue(start <= 10 && 10 <= end, "warm window must cover the predicted gap of 10");

        g.onInvocation(40);
        assertEquals(2, g.trusted());             // calls 30 and 40
        assertEquals(3, g.fellBack());            // calls 0, 10 and 20
    }

    @Test
    void fallsBackWhenPredictionsAreNoisy() {
        // calls every 10 minutes, predictions swing between 1 and 100
        long[] t = new long[8];
        double[] p = new double[8];
        for (int i = 0; i < 8; i++) { t[i] = i * 10L; p[i] = i % 2 == 0 ? 1 : 100; }
        LstmGatedPolicy g = gate(new LstmPredictions(t, p));
        for (long m : t) g.onInvocation(m);

        assertEquals(0, g.trusted());
        assertEquals(0, g.preWarmMinutes());
        assertEquals(7, g.keepAliveMinutes());
    }

    @Test
    void fallsBackWhenThereAreNoPredictions() {
        LstmGatedPolicy g = gate(LstmPredictions.EMPTY);
        for (long t = 0; t <= 50; t += 10) g.onInvocation(t);
        assertEquals(0, g.trusted());
        assertEquals(7, g.keepAliveMinutes());
    }

    @Test
    void capsTheWindowAtMaxEnd() {
        LstmGatedPolicy g = gate(preds(4, 1000, 1000.0));
        for (long t = 0; t <= 3000; t += 1000) g.onInvocation(t);
        assertEquals(239, g.preWarmMinutes());    // window ends at maxEnd = 240
        assertEquals(1, g.keepAliveMinutes());
    }
}
