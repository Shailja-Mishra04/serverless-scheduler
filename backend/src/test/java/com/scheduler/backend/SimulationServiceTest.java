package com.scheduler.backend;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tiny hand-made traces with cold starts and idle minutes worked out by hand.
 * Only days 6-7 are scored, so day 6 starts at minute D6 = 5 x 1440 = 7200.
 */
class SimulationServiceTest {

    private static final long D6 = 7200;

    /** A policy with a fixed pre-warm delay and keep-alive, to test the warm-window maths. */
    private record Window(int pre, int keep) implements KeepAlivePolicy {
        public void onInvocation(long minute) { }
        public int preWarmMinutes() { return pre; }
        public int keepAliveMinutes() { return keep; }
    }

    private static SimulationResult runFixed(FakeJdbcTemplate db, int minutes) {
        return new SimulationService(db).run("fixed", () -> new FixedKeepAlivePolicy(minutes));
    }

    @Test
    void fixedTimeoutCountsColdStartsAndIdle() {
        // calls at +0, +5, +30 with a 10-minute timeout
        //  +0  cold, warm until +10
        //  +5  warm, idle += 5 (minutes +0..+5), warm until +15
        //  +30 cold, idle += 10 (the whole +5..+15 window), warm until +40
        //  end: idle += 10
        FakeJdbcTemplate db = new FakeJdbcTemplate().function("f1", D6, D6 + 5, D6 + 30);
        SliceStats s = runFixed(db, 10).overall();

        assertEquals(3, s.events());
        assertEquals(2, s.coldStarts());
        assertEquals(25, s.idleWarmMinutes());
        assertEquals(2.0 / 3, s.coldStartRate(), 1e-9);
    }

    @Test
    void daysOneToFiveAreReplayedButNotScored() {
        // -10 and -5 warm the container up but are not scored
        //  -10 cold (not counted), warm until D6
        //  -5  warm, warm until D6+5 (idle before D6 not counted)
        //  +3  warm, idle += 3 (D6..D6+3), warm until +13
        //  end: idle += 10
        FakeJdbcTemplate db = new FakeJdbcTemplate().function("f1", D6 - 10, D6 - 5, D6 + 3);
        SliceStats s = runFixed(db, 10).overall();

        assertEquals(1, s.events());
        assertEquals(0, s.coldStarts());
        assertEquals(13, s.idleWarmMinutes());
    }

    @Test
    void preWarmWindowStartsLaterAndIsChargedOnlyWhileLoaded() {
        // pre-warm 6, keep 4: each call opens a window [t+6, t+10]
        //  +0  cold, window [+6, +10]
        //  +8  warm, idle += 2 (+6..+8), window [+14, +18]
        //  +20 cold (window ended), idle += 4, window [+26, +30]
        //  +23 cold (window not started yet), no idle, window [+29, +33]
        //  end: idle += 4
        FakeJdbcTemplate db = new FakeJdbcTemplate().function("f1", D6, D6 + 8, D6 + 20, D6 + 23);
        SliceStats s = new SimulationService(db).run("w", () -> new Window(6, 4)).overall();

        assertEquals(4, s.events());
        assertEquals(3, s.coldStarts());
        assertEquals(10, s.idleWarmMinutes());
    }

    @Test
    void shortUnloadsStayWarmInstead() {
        // pre-warm 3 is under the 5-minute minimum, so it becomes keep-alive 3 + 4 = 7
        //  +0  cold, warm [+0, +7]
        //  +2  warm (would be cold with a real 3-minute unload), idle += 2, warm [+2, +9]
        //  +10 cold, idle += 7, warm [+10, +17]
        //  end: idle += 7
        FakeJdbcTemplate db = new FakeJdbcTemplate().function("f1", D6, D6 + 2, D6 + 10);
        SliceStats s = new SimulationService(db).run("w", () -> new Window(3, 4)).overall();

        assertEquals(2, s.coldStarts());
        assertEquals(16, s.idleWarmMinutes());
    }

    @Test
    void groupsFunctionsIntoBandsAndAverages() {
        // f1: the 3-call trace from the first test (2 cold, 25 idle), band 1-9
        // f2: 12 calls one minute apart (1 cold, 11 x 1 + 10 = 21 idle), band 10-49
        long[] f2 = new long[12];
        for (int i = 0; i < 12; i++) f2[i] = D6 + i;
        FakeJdbcTemplate db = new FakeJdbcTemplate()
                .function("f1", D6, D6 + 5, D6 + 30)
                .function("f2", f2);
        SimulationResult r = runFixed(db, 10);

        assertEquals(15, r.overall().events());
        assertEquals(3, r.overall().coldStarts());
        assertEquals(46, r.overall().idleWarmMinutes());
        assertEquals(0.2, r.overall().coldStartRate(), 1e-9);
        assertEquals((2.0 / 3 + 1.0 / 12) / 2, r.overall().meanFunctionColdStartRate(), 1e-9);

        assertEquals(1, r.bands().get("1-9").functions());
        assertEquals(1, r.bands().get("10-49").functions());
        assertEquals(1, r.longTail().functions());   // bottom 20% by calls is f1
        assertEquals(3, r.longTail().events());
    }

    @Test
    void gateUsesPredictionsLoadedFromTheDatabase() {
        // 10 calls every 10 minutes, the LSTM always predicts a gap of 10
        long[] t = new long[10];
        double[] g = new double[10];
        for (int i = 0; i < 10; i++) { t[i] = D6 + 10L * i; g[i] = 10; }
        FakeJdbcTemplate db = new FakeJdbcTemplate()
                .function("f1", t)
                .predictions("f1", new LstmPredictions(t, g));
        SimulationService service = new SimulationService(db);

        assertEquals(10.0, service.predictionsFor("f1").at(D6 + 20));
        assertEquals(LstmPredictions.EMPTY, service.predictionsFor("missing"));

        // the fallback alone (1-minute timeout) misses every call
        SliceStats fallbackOnly = service.run("fixed-1", () -> new FixedKeepAlivePolicy(1)).overall();
        assertEquals(10, fallbackOnly.coldStarts());

        // the gate needs 3 errors first (decided at calls 1-3), then pre-warms the
        // rest, so only the first 4 calls are cold
        SliceStats gated = service.runPerFunction("gate", fid -> new LstmGatedPolicy(
                        service.predictionsFor(fid), new FixedKeepAlivePolicy(1), 0.25, 3, 2.0, 20, 1, 240))
                .overall();
        assertEquals(4, gated.coldStarts());
        assertTrue(gated.idleWarmMinutes() < 20, "pre-warmed windows should be short");
    }
}
