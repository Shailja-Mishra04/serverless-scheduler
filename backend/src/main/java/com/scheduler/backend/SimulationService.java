package com.scheduler.backend;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

@Service
public class SimulationService {

    private static final double LONG_TAIL_FRACTION = 0.20; // bottom 20% by event count
    private static final String[] BAND_NAMES = {"1-9", "10-49", "50-499", "500-4999", "5000+"};

    private final JdbcTemplate jdbc;
    private Map<String, long[]> cache;   // function_id -> sorted global minutes

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

    /** Reads the whole table once; later runs reuse it. */
    private synchronized Map<String, long[]> data() {
        if (cache != null) return cache;

        Map<String, long[]> map = new LinkedHashMap<>();
        String sql = "SELECT function_id, (day-1)*1440 + (minute_of_day-1) AS t " +
                "FROM invocations ORDER BY function_id";

        PreparedStatementCreator psc = con -> {
            PreparedStatement ps = con.prepareStatement(
                    sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
            ps.setFetchSize(Integer.MIN_VALUE);
            return ps;
        };

        String[] current = {null};
        long[][] buf = {new long[1024]};
        int[] n = {0};

        jdbc.query(psc, (RowCallbackHandler) rs -> {
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

    public SimulationResult run(String policyName, Supplier<KeepAlivePolicy> policyFactory) {
        Map<String, long[]> data = data();

        long[] sizes = data.values().stream().mapToLong(a -> a.length).sorted().toArray();
        long threshold = sizes[(int) (sizes.length * LONG_TAIL_FRACTION)];

        Acc all = new Acc(), tail = new Acc(), rest = new Acc();
        Acc[] bands = new Acc[BAND_NAMES.length];
        for (int i = 0; i < bands.length; i++) bands[i] = new Acc();

        for (long[] minutes : data.values()) {
            KeepAlivePolicy policy = policyFactory.get();
            long warmUntil = -1, cold = 0, idle = 0;

            for (long t : minutes) {
                boolean isWarm = t <= warmUntil;
                if (!isWarm) cold++;

                policy.onInvocation(t);
                int k = policy.keepAliveMinutes();
                long newWarmUntil = t + k;
                idle += isWarm ? Math.max(0, newWarmUntil - warmUntil) : k;
                warmUntil = newWarmUntil;
            }

            long events = minutes.length;
            all.add(events, cold, idle);
            (events <= threshold ? tail : rest).add(events, cold, idle);
            bands[bandOf(minutes.length)].add(events, cold, idle);
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