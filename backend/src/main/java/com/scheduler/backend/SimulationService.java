package com.scheduler.backend;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

@Service
public class SimulationService {

    private static final double LONG_TAIL_FRACTION = 0.20; // bottom 20% by event count
    private static final String[] BAND_NAMES = {"1-9", "10-49", "50-499", "500-4999", "5000+"};
    /** Only calls and idle from this minute on are scored (start of day 6). Set 0 to score all 7 days. */
    private static final long EVAL_FROM = 5L * 1440;
    /** Unloading for fewer minutes than this isn't worth a reload, so the container stays warm. */
    private static final int MIN_UNLOAD = 5;
    private final JdbcTemplate jdbc;
    private Map<String, long[]> cache;                 // function_id -> sorted global minutes
    private Map<String, LstmPredictions> predCache;    // function_id -> LSTM predictions

    public SimulationService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static class Acc {
        long fns, events, cold, idle;
        double sumRate;
        void add(long ev, long cd, long id) {
            fns++; events += ev; cold += cd; idle += id;
            sumRate += ev == 0 ? 0 : (double) cd / ev;
        }
    }

    private static int bandOf(int n) {
        return n < 10 ? 0 : n < 50 ? 1 : n < 500 ? 2 : n < 5000 ? 3 : 4;
    }

    private static PreparedStatementCreator streaming(String sql) {
        return con -> {
            PreparedStatement ps = con.prepareStatement(
                    sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
            ps.setFetchSize(Integer.MIN_VALUE);
            return ps;
        };
    }

    /** Reads the whole table once; later runs reuse it. */
    private synchronized Map<String, long[]> data() {
        if (cache != null) return cache;

        Map<String, long[]> map = new LinkedHashMap<>();
        String sql = "SELECT function_id, (day-1)*1440 + (minute_of_day-1) AS t " +
                "FROM invocations ORDER BY function_id";

        String[] current = {null};
        long[][] buf = {new long[1024]};
        int[] n = {0};

        jdbc.query(streaming(sql), (RowCallbackHandler) rs -> {
            String f = rs.getString(1);
            long t = rs.getLong(2);
            if (!f.equals(current[0])) {
                flush(map, current[0], buf[0], n[0]);
                current[0] = f;
                n[0] = 0;
            }
            if (n[0] == buf[0].length) buf[0] = Arrays.copyOf(buf[0], n[0] * 2);
            buf[0][n[0]++] = t;
        });
        flush(map, current[0], buf[0], n[0]);

        cache = map;
        return cache;
    }

    private static void flush(Map<String, long[]> map, String fid, long[] buf, int n) {
        if (fid == null) return;
        long[] a = Arrays.copyOf(buf, n);
        Arrays.sort(a);
        map.put(fid, a);
    }

    /** Reads all LSTM predictions once; later runs reuse them. */
    private synchronized Map<String, LstmPredictions> predictions() {
        if (predCache != null) return predCache;

        Map<String, LstmPredictions> map = new HashMap<>();
        String sql = "SELECT function_id, call_minute, pred_gap FROM lstm_predictions " +
                "ORDER BY function_id, call_minute";

        String[] current = {null};
        long[][] ts = {new long[1024]};
        double[][] gaps = {new double[1024]};
        int[] n = {0};

        jdbc.query(streaming(sql), (RowCallbackHandler) rs -> {
            String f = rs.getString(1);
            if (!f.equals(current[0])) {
                if (current[0] != null) {
                    map.put(current[0], new LstmPredictions(
                            Arrays.copyOf(ts[0], n[0]), Arrays.copyOf(gaps[0], n[0])));
                }
                current[0] = f;
                n[0] = 0;
            }
            if (n[0] == ts[0].length) {
                ts[0] = Arrays.copyOf(ts[0], n[0] * 2);
                gaps[0] = Arrays.copyOf(gaps[0], n[0] * 2);
            }
            ts[0][n[0]] = rs.getLong(2);
            gaps[0][n[0]++] = rs.getDouble(3);
        });
        if (current[0] != null) {
            map.put(current[0], new LstmPredictions(
                    Arrays.copyOf(ts[0], n[0]), Arrays.copyOf(gaps[0], n[0])));
        }

        predCache = map;
        return predCache;
    }

    public LstmPredictions predictionsFor(String functionId) {
        return predictions().getOrDefault(functionId, LstmPredictions.EMPTY);
    }

    /** Minutes of [from, to) that fall inside the scored period. */
    private static long scored(long from, long to) {
        return Math.max(0, to - Math.max(from, EVAL_FROM));
    }

    public SimulationResult run(String policyName, Supplier<KeepAlivePolicy> policyFactory) {
        return runPerFunction(policyName, fid -> policyFactory.get());
    }

    /** Same as run(), but the policy is built knowing which function it serves. */
    public SimulationResult runPerFunction(String policyName,
                                           Function<String, KeepAlivePolicy> policyFactory) {
        Map<String, long[]> data = data();

        long[] sizes = data.values().stream().mapToLong(a -> a.length).sorted().toArray();
        long threshold = sizes[(int) (sizes.length * LONG_TAIL_FRACTION)];

        Acc all = new Acc(), tail = new Acc(), rest = new Acc();
        Acc[] bands = new Acc[BAND_NAMES.length];
        for (int i = 0; i < bands.length; i++) bands[i] = new Acc();
        long trusted = 0, fellBack = 0;

        for (Map.Entry<String, long[]> e : data.entrySet()) {
            long[] minutes = e.getValue();
            KeepAlivePolicy policy = policyFactory.apply(e.getKey());
            long winStart = -1, winEnd = -1, cold = 0, idle = 0, events = 0;

            for (long t : minutes) {
                boolean isWarm = t >= winStart && t <= winEnd;
                if (t >= EVAL_FROM) {
                    events++;
                    if (!isWarm) cold++;
                }

                // charge the previous warm window only for the minutes it was actually loaded
                if (winEnd >= 0 && t >= winStart) idle += scored(winStart, Math.min(t, winEnd));

                policy.onInvocation(t);
                int d = policy.preWarmMinutes();
                int k = policy.keepAliveMinutes();
                if (d < MIN_UNLOAD) { k += d; d = 0; }
                winStart = t + d;
                winEnd = t + d + k;
            }
            if (winEnd >= 0) idle += scored(winStart, winEnd);   // last window runs to its end

            if (policy instanceof LstmGatedPolicy g) {
                trusted += g.trusted();
                fellBack += g.fellBack();
            }
            if (events == 0) continue;                           // no calls in the scored days

            all.add(events, cold, idle);
            (minutes.length <= threshold ? tail : rest).add(events, cold, idle);
            bands[bandOf(minutes.length)].add(events, cold, idle);
        }

        if (trusted + fellBack > 0) {
            System.out.printf("%s: LSTM trusted %d times, fallback %d times (%.1f%% trusted)%n",
                    policyName, trusted, fellBack, 100.0 * trusted / (trusted + fellBack));
        }

        Map<String, SliceStats> bandMap = new LinkedHashMap<>();
        for (int i = 0; i < bands.length; i++) bandMap.put(BAND_NAMES[i], toStats(bands[i]));
        return new SimulationResult(policyName, toStats(all), toStats(tail), toStats(rest), bandMap);
    }

    /** How many functions (and events) fall into each call-count band. */
    public List<BandStats> bands() {
        long[] fns = new long[5], evs = new long[5];
        for (long[] m : data().values()) {
            int b = bandOf(m.length);
            fns[b]++; evs[b] += m.length;
        }
        List<BandStats> out = new ArrayList<>();
        for (int i = 0; i < 5; i++) out.add(new BandStats(BAND_NAMES[i] + " events", fns[i], evs[i]));
        return out;
    }

    private SliceStats toStats(Acc a) {
        double rate = a.events == 0 ? 0 : (double) a.cold / a.events;
        double meanFn = a.fns == 0 ? 0 : a.sumRate / a.fns;
        return new SliceStats(a.fns, a.events, a.cold, rate, meanFn, a.idle);
    }
}