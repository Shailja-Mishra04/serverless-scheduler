package com.scheduler.backend;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.RowCallbackHandler;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stands in for MySQL in tests. It answers SimulationService's two streaming queries
 * (invocations and lstm_predictions) from small in-memory traces.
 */
class FakeJdbcTemplate extends JdbcTemplate {

    private final Map<String, long[]> calls = new LinkedHashMap<>();
    private final Map<String, LstmPredictions> preds = new LinkedHashMap<>();

    /** Adds a function called at these global minutes (day 6 starts at minute 7200). */
    FakeJdbcTemplate function(String id, long... minutes) {
        calls.put(id, minutes);
        return this;
    }

    FakeJdbcTemplate predictions(String id, LstmPredictions p) {
        preds.put(id, p);
        return this;
    }

    @Override
    public void query(PreparedStatementCreator psc, RowCallbackHandler rch) {
        String sql = sqlOf(psc);
        List<Object[]> rows = new ArrayList<>();
        if (sql.contains("lstm_predictions")) {
            preds.forEach((id, p) -> {
                for (int i = 0; i < p.t().length; i++) rows.add(new Object[]{id, p.t()[i], p.gap()[i]});
            });
        } else {
            calls.forEach((id, ms) -> {
                for (long m : ms) rows.add(new Object[]{id, m});
            });
        }
        try {
            for (Object[] row : rows) rch.processRow(resultSet(row));
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Runs the service's statement creator against a fake connection to see its SQL. */
    private static String sqlOf(PreparedStatementCreator psc) {
        String[] sql = {null};
        Connection con = (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("prepareStatement")) {
                        sql[0] = (String) args[0];
                        return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                                new Class<?>[]{PreparedStatement.class}, (p, m, a) -> null);
                    }
                    return null;
                });
        try {
            psc.createPreparedStatement(con);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return sql[0];
    }

    /** A ResultSet positioned on one row; columns are 1-based like JDBC. */
    private static ResultSet resultSet(Object[] row) {
        return (ResultSet) Proxy.newProxyInstance(
                ResultSet.class.getClassLoader(), new Class<?>[]{ResultSet.class},
                (proxy, method, args) -> {
                    Object v = row[(Integer) args[0] - 1];
                    return switch (method.getName()) {
                        case "getString" -> v.toString();
                        case "getLong" -> ((Number) v).longValue();
                        case "getDouble" -> ((Number) v).doubleValue();
                        default -> throw new UnsupportedOperationException(method.getName());
                    };
                });
    }
}
