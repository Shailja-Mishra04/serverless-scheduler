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
public class PredictionLoader implements CommandLineRunner {

    private final JdbcTemplate jdbcTemplate;
    private static final int BATCH_SIZE = 5000;

    public PredictionLoader(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(String... args) throws Exception {
        // Skip if already loaded, so we don't duplicate data on every restart
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM lstm_predictions", Integer.class);
        if (count != null && count > 0) {
            System.out.println("lstm_predictions already has " + count + " rows. Skipping load.");
            return;
        }

        String sql = "INSERT INTO lstm_predictions (function_id, call_minute, pred_gap) VALUES (?, ?, ?)";

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new ClassPathResource("data/lstm_predictions.csv").getInputStream(), StandardCharsets.UTF_8))) {

            String line = reader.readLine(); // skip header: function_id,t,pred_gap
            List< Object[] > batch = new ArrayList<>();
            int total = 0;

            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(",");
                if (parts.length != 3) continue;

                batch.add(new Object[]{
                        parts[0],
                        Long.parseLong(parts[1]),
                        Double.parseDouble(parts[2])
                });

                if (batch.size() == BATCH_SIZE) {
                    jdbcTemplate.batchUpdate(sql, batch);
                    total += batch.size();
                    if (total % 100000 == 0) System.out.println("Inserted " + total + " predictions...");
                    batch.clear();
                }
            }
            if (!batch.isEmpty()) {
                jdbcTemplate.batchUpdate(sql, batch);
                total += batch.size();
            }
            System.out.println("Done. Total predictions inserted: " + total);
        }
    }
}