package com.scheduler.backend;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HybridHistogramPolicyTest {

    /** Same settings as the /summary rows: range 240, 10 samples, head 5%, tail 99%, margin 10%, CV 2. */
    private static HybridHistogramPolicy hist(int maxEnd) {
        return new HybridHistogramPolicy(240, 10, 0.05, 0.99, 0.10, 2.0, maxEnd, 60);
    }

    private static void callEvery(KeepAlivePolicy p, int gap, int calls) {
        for (int i = 0; i < calls; i++) p.onInvocation((long) i * gap);
    }

    @Test
    void usesFallbackUntilItHasTenGaps() {
        HybridHistogramPolicy p = hist(240);
        callEvery(p, 5, 10);                      // 9 gaps
        assertEquals(0, p.preWarmMinutes());
        assertEquals(60, p.keepAliveMinutes());
    }

    @Test
    void preWarmsForARegularFunction() {
        HybridHistogramPolicy p = hist(240);
        callEvery(p, 5, 11);                      // 10 gaps of 5 minutes
        // head = tail = 5; end = ceil(5 x 1.1) = 6; pre-warm = floor(5 x 0.9) = 4
        assertEquals(4, p.preWarmMinutes());
        assertEquals(2, p.keepAliveMinutes());    // warm from minute 4 to minute 6
    }

    @Test
    void capsTheWindowAtMaxEnd() {
        HybridHistogramPolicy p = hist(3);
        callEvery(p, 5, 11);
        // end capped at 3, so pre-warm becomes 3 - 1 = 2 and keep-alive 1
        assertEquals(2, p.preWarmMinutes());
        assertEquals(1, p.keepAliveMinutes());
    }

    @Test
    void fallsBackWhenMostGapsAreOutOfRange() {
        HybridHistogramPolicy p = hist(240);
        long t = 0;
        for (int i = 0; i <= 10; i++) { p.onInvocation(t); t += 5; }     // 10 gaps of 5
        for (int i = 0; i < 11; i++) { t += 300; p.onInvocation(t); }   // 11 gaps over 240
        assertEquals(0, p.preWarmMinutes());
        assertEquals(60, p.keepAliveMinutes());
    }
}
