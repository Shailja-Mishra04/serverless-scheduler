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

    private final JdbcTemplate jdbc;
    private Map<String, long[]> cache;   // function_id -> sorted global minutes

    public SimulationService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static class Acc { long fns, events, cold, idle; }

    /** Reads the whole table once; later runs reuse it. */
    private synchronized Map<String, long[]> data() {
        if (cache != null) return cache;

        Map<String, long[]> map = new LinkedHashMap<>();
        String sql = "SELECT function_id, (day-1)*1440 + (minute_of_day-1) AS t " +
                "FROM invocations ORDER BY function_id";

        PreparedStatementCreator psc = con -> {
            PreparedStatement ps = con.prepareStatement(
                    sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
            ps.setFetchSize(Integer.MIN_VALUE);   // stream rows instead of loading all at once
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

        for (long[] minutes : data.values()) {
            KeepAlivePolicy policy = policyFactory.get();
            long warmUntil = -1, events = 0, cold = 0, idle = 0;

            for (long t : minutes) {
                events++;
                boolean isWarm = t <= warmUntil;
                if (!isWarm) cold++;

                policy.onInvocation(t);
                int k = policy.keepAliveMinutes();
                long newWarmUntil = t + k;
                idle += isWarm ? Math.max(0, newWarmUntil - warmUntil) : k;
                warmUntil = newWarmUntil;
            }

            Acc bucket = minutes.length <= threshold ? tail : rest;
            for (Acc a : new Acc[]{all, bucket}) {
                a.fns++; a.events += events; a.cold += cold; a.idle += idle;
            }
        }
        return new SimulationResult(policyName, toStats(all), toStats(tail), toStats(rest));
    }

    /** How many functions (and events) fall into each call-count band. */
    public List<BandStats> bands() {
        String[] names = {"1-9 events", "10-49 events", "50-499 events", "500-4999 events", "5000+ events"};
        long[] fns = new long[5], evs = new long[5];
        for (long[] m : data().values()) {
            int n = m.length;
            int b = n < 10 ? 0 : n < 50 ? 1 : n < 500 ? 2 : n < 5000 ? 3 : 4;
            fns[b]++; evs[b] += n;
        }
        List<BandStats> out = new ArrayList<>();
        for (int i = 0; i < 5; i++) out.add(new BandStats(names[i], fns[i], evs[i]));
        return out;
    }

    private SliceStats toStats(Acc a) {
        double rate = a.events == 0 ? 0 : (double) a.cold / a.events;
        return new SliceStats(a.fns, a.events, a.cold, rate, a.idle);
    }
}