package com.scheduler.backend;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.function.Supplier;

@Service
public class SimulationService {

    private final JdbcTemplate jdbc;

    public SimulationService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public SimulationResult run(String policyName, Supplier<KeepAlivePolicy> policyFactory) {
        List<String> functionIds = jdbc.queryForList(
                "SELECT DISTINCT function_id FROM invocations", String.class);

        long events = 0, cold = 0, warm = 0, idle = 0;

        for (String fid : functionIds) {
            List<Long> minutes = jdbc.query(
                    "SELECT (day-1)*1440 + (minute_of_day-1) AS t FROM invocations " +
                            "WHERE function_id = ? ORDER BY day, minute_of_day",
                    (rs, i) -> rs.getLong("t"), fid);

            KeepAlivePolicy policy = policyFactory.get();   // fresh state per function
            long warmUntil = -1;

            for (long t : minutes) {
                events++;
                boolean isWarm = t <= warmUntil;
                if (isWarm) warm++; else cold++;

                policy.onInvocation(t);
                int k = policy.keepAliveMinutes();
                long newWarmUntil = t + k;

                if (isWarm) idle += Math.max(0, newWarmUntil - warmUntil);
                else idle += k;

                warmUntil = newWarmUntil;
            }
        }
        double rate = events == 0 ? 0 : (double) cold / events;
        return new SimulationResult(policyName, functionIds.size(), events, cold, warm, rate, idle);
    }
}
