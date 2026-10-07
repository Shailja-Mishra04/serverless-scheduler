package com.scheduler.backend;

import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Component
public class DataLoader implements CommandLineRunner {

    private final JdbcTemplate jdbcTemplate;
    private static final int BATCH_SIZE = 5000;

    public DataLoader(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(String... args) throws Exception {
        // Skip if already loaded, so we don't duplicate data on every restart
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM invocations", Integer.class);
        if (count != null && count > 0) {
            System.out.println("Invocations table already has " + count + " rows. Skipping load.");
            return;
        }

        String sql = "INSERT INTO invocations (function_id, day, minute_of_day, invocation_count) VALUES (?, ?, ?, ?)";

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new ClassPathResource("data/invocations_subset.csv").getInputStream(), StandardCharsets.UTF_8))) {

            String line = reader.readLine(); // skip header
            List<Object[]> batch = new ArrayList<>();
            int total = 0;

            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(",");
                if (parts.length != 4) continue;

                Object[] row = new Object[]{
                        parts[0],
                        Integer.parseInt(parts[1]),
                        Integer.parseInt(parts[2]),
                        Integer.parseInt(parts[3])
                };
                batch.add(row);

                if (batch.size() == BATCH_SIZE) {
                    jdbcTemplate.batchUpdate(sql, batch);
                    total += batch.size();
                    System.out.println("Inserted " + total + " rows...");
                    batch.clear();
                }
            }
            if (!batch.isEmpty()) {
                jdbcTemplate.batchUpdate(sql, batch);
                total += batch.size();
            }
            System.out.println("Done. Total rows inserted: " + total);
        }
    }
}