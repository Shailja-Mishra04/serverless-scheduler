package com.scheduler.backend;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** One check per simple baseline policy. */
class BaselinePoliciesTest {

    @Test
    void fixedNeverChanges() {
        FixedKeepAlivePolicy p = new FixedKeepAlivePolicy(10);
        p.onInvocation(0);
        p.onInvocation(500);
        assertEquals(10, p.keepAliveMinutes());
        assertEquals(0, p.preWarmMinutes());
    }

    @Test
    void ewmaFollowsTheSmoothedGap() {
        // (alpha, multiplier, minKeep, maxKeep, fallback)
        EwmaKeepAlivePolicy p = new EwmaKeepAlivePolicy(0.5, 2.0, 1, 60, 10);
        p.onInvocation(0);
        assertEquals(10, p.keepAliveMinutes());   // no gap yet -> fallback
        p.onInvocation(4);                        // gap 4 -> ewma 4 -> ceil(2 x 4) = 8
        assertEquals(8, p.keepAliveMinutes());
        p.onInvocation(6);                        // gap 2 -> ewma 0.5x2 + 0.5x4 = 3 -> 6
        assertEquals(6, p.keepAliveMinutes());
    }

    @Test
    void percentilePicksTheRightGap() {
        // (percentile, window, minGaps, minKeep, maxKeep, fallback, margin)
        PercentileKeepAlivePolicy p = new PercentileKeepAlivePolicy(0.9, 4, 3, 1, 60, 10, 1.0);
        p.onInvocation(0);
        p.onInvocation(1);
        p.onInvocation(3);
        assertEquals(10, p.keepAliveMinutes());   // only 2 gaps -> fallback
        p.onInvocation(7);                        // gaps 1, 2, 4 -> index ceil(0.9 x 3) - 1 = 2
        assertEquals(4, p.keepAliveMinutes());
    }

    @Test
    void floorRaisesShortKeepAlives() {
        assertEquals(5, new FloorKeepAlivePolicy(5, new FixedKeepAlivePolicy(2)).keepAliveMinutes());
        assertEquals(20, new FloorKeepAlivePolicy(5, new FixedKeepAlivePolicy(20)).keepAliveMinutes());
    }
}
