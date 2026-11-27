package cn.iocoder.sanitation.validation;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

import static cn.iocoder.sanitation.validation.Authorization.*;
import static cn.iocoder.sanitation.validation.Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;

@Tag("mysql")
class MySqlScopeTest {
    final Fixtures f = new Fixtures();

    @Test @Tag("positive")
    void mysqlGroupDrillDownAndPageHaveOneScope() throws Exception {
        try (var connection = database()) {
            var scope = f.policy.decide(REGULATOR, view(ONE));
            assertEquals(List.of("a:1:2:5", "b:1:3:6"), results(connection, scope,
                    "cell, COUNT(*), SUM(amount), SUM(population)", " GROUP BY cell ORDER BY cell"));
            assertEquals(List.of("1"), results(connection, scope, "id", " AND cell = ? ORDER BY id", "a"));
            assertEquals(List.of("2"), results(connection, scope, "id", " ORDER BY occurred, id LIMIT 1 OFFSET 1"));
        }
    }

    @Test @Tag("negative")
    void mysqlDirectIdsAndEmptyScopeCannotAccessRows() throws Exception {
        try (var connection = database()) {
            var broad = new Selection(ONE, Data.OBSERVATION, Action.VIEW,
                    new Window(START.minusSeconds(999), START.plusSeconds(999)));
            var allowed = f.policy.decide(USER, broad);
            assertEquals(List.of("1", "2"), results(connection, allowed, "id", " ORDER BY id"));
            for (int id : new int[] {3, 4, 5, 999}) {
                assertEquals(List.of(), results(connection, allowed, "id", " AND id = ?", id));
            }
            var denied = f.policy.decide(REGULATOR, view(TWO));
            assertEquals(List.of(), results(connection, denied, "id", " ORDER BY id"));
            assertEquals(List.of("0:0:0"), results(connection, denied,
                    "COUNT(*), COALESCE(SUM(amount),0), COALESCE(SUM(population),0)", ""));
        }
    }

    private static List<String> results(Connection connection, Decision scope, String columns, String postfix, Object... filters)
            throws Exception {
        // SQL fragments are fixed fixture constants. The scope always contributes a predicate.
        String filter = "1 = 0";
        if (scope.allowed()) {
            assertEquals(Data.OBSERVATION, scope.selection().data());
            assertEquals(Action.VIEW, scope.selection().action());
            String periods = String.join(" OR ", java.util.Collections.nCopies(scope.windows().size(),
                    "(occurred >= ? AND occurred < ?)"));
            filter = "tenant = ? AND project = ? AND occurred >= ? AND occurred < ? AND (" + periods + ")";
        }
        try (var sql = connection.prepareStatement("SELECT " + columns + " FROM probe_observation WHERE " + filter + postfix)) {
            int start = 1;
            if (scope.allowed()) {
                var request = scope.selection();
                sql.setLong(1, request.project().tenant());
                sql.setLong(2, request.project().id());
                bind(sql, 3, request.dataTime());
                start = 5;
                for (Window window : scope.windows()) {
                    bind(sql, start, window);
                    start += 2;
                }
            }
            for (Object value : filters) sql.setObject(start++, value);
            try (var rows = sql.executeQuery()) {
                var output = new ArrayList<String>();
                while (rows.next()) {
                    var values = new ArrayList<String>();
                    for (int col = 1; col <= rows.getMetaData().getColumnCount(); col++) values.add(rows.getString(col));
                    output.add(String.join(":", values));
                }
                return output;
            }
        }
    }

    private static void bind(PreparedStatement sql, int start, Window window) throws Exception {
        sql.setTimestamp(start, Timestamp.from(window.from()));
        sql.setTimestamp(start + 1, Timestamp.from(window.to()));
    }

    private static Connection database() throws Exception {
        String port = System.getenv("ENG002D_MYSQL_PORT");
        assertNotNull(port, "run through run-mysql.py");
        assertTrue(port.matches("[0-9]{4,5}"));
        assertNotEquals("3306", port);
        var connection = DriverManager.getConnection("jdbc:mysql://127.0.0.1:" + port
                        + "/eng002d_probe?sslMode=DISABLED&allowPublicKeyRetrieval=true"
                        + "&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
                "eng002d_probe", System.getenv("ENG002D_MYSQL_PASSWORD"));
        try {
            try (var statement = connection.createStatement();
                 var identity = statement.executeQuery("SELECT VERSION(), @@report_host, @@server_uuid, DATABASE()")) {
                assertTrue(identity.next());
                assertTrue(identity.getString(1).startsWith("8.4."));
                assertEquals("eng002d-probe-isolated", identity.getString(2));
                assertEquals(System.getenv("ENG002D_MYSQL_UUID"), identity.getString(3));
                assertEquals("eng002d_probe", identity.getString(4));
            }
            try (var sql = connection.createStatement()) {
                sql.execute("CREATE TEMPORARY TABLE probe_observation (id INT, tenant INT, project INT,"
                        + " occurred DATETIME(3), cell VARCHAR(10), amount INT, population INT)");
            }
            try (var insert = connection.prepareStatement("INSERT INTO probe_observation VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                for (var row : observations()) {
                    insert.setLong(1, row.id());
                    insert.setLong(2, row.project().tenant());
                    insert.setLong(3, row.project().id());
                    insert.setTimestamp(4, Timestamp.from(row.eventTime()));
                    insert.setString(5, row.cell());
                    insert.setInt(6, row.numerator());
                    insert.setInt(7, row.denominator());
                    insert.executeUpdate();
                }
            }
            return connection;
        } catch (Exception | AssertionError error) {
            connection.close();
            throw error;
        }
    }
}
