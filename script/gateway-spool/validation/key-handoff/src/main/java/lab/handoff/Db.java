package lab.handoff;

import org.sqlite.SQLiteConnection;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

final class Db {
    @FunctionalInterface interface Work<T> { T run(Connection db) throws SQLException; }
    @FunctionalInterface interface MapRow<T> { T read(ResultSet row) throws SQLException; }

    static Connection open(Path file, boolean readonly) throws SQLException {
        var db = DriverManager.getConnection("jdbc:sqlite:" + file.toUri() + (readonly ? "?mode=ro" : ""));
        try {
            exec(db, "PRAGMA foreign_keys=ON");
            exec(db, "PRAGMA busy_timeout=4000");
            if (!readonly) exec(db, "PRAGMA synchronous=FULL");
            return db;
        } catch (SQLException e) {
            db.close();
            throw e;
        }
    }

    static <T> T tx(Path file, Work<T> work) throws SQLException {
        try (var db = open(file, false)) {
            exec(db, "BEGIN IMMEDIATE");
            try {
                var result = work.run(db);
                exec(db, "COMMIT");
                return result;
            } catch (SQLException | RuntimeException e) {
                try {
                    exec(db, "ROLLBACK");
                } catch (SQLException rollback) {
                    e.addSuppressed(rollback);
                }
                throw e;
            }
        }
    }

    static int exec(Connection db, String sql, Object... args) throws SQLException {
        try (var stmt = db.prepareStatement(sql)) {
            bind(stmt, args);
            stmt.execute();
            return Math.max(0, stmt.getUpdateCount());
        }
    }

    static <T> List<T> rows(Connection db, String sql, MapRow<T> map, Object... args) throws SQLException {
        try (var stmt = db.prepareStatement(sql)) {
            bind(stmt, args);
            try (var rs = stmt.executeQuery()) {
                var result = new ArrayList<T>();
                while (rs.next()) result.add(map.read(rs));
                return result;
            }
        }
    }

    static long number(Connection db, String query, Object... args) throws SQLException {
        return rows(db, query, row -> row.getLong(1), args).get(0);
    }

    static String text(Connection db, String query, Object... args) throws SQLException {
        return rows(db, query, row -> row.getString(1), args).get(0);
    }

    private static void bind(java.sql.PreparedStatement stmt, Object[] args) throws SQLException {
        for (int i = 0; i < args.length; i++) stmt.setObject(i + 1, args[i]);
    }

    static String hash(byte[] payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    static String hash(String text) {
        return hash(text.getBytes(StandardCharsets.UTF_8));
    }

    static void backup(Connection db, Path target) throws SQLException, IOException {
        Files.createFile(target);
        if (db.unwrap(SQLiteConnection.class).getDatabase().backup("main", target.toString(), null) != 0) {
            throw new SQLException("SQLite backup failed");
        }
    }

    static String resource(String name) {
        try (var in = Db.class.getResourceAsStream(name)) {
            if (in == null) throw new IllegalArgumentException(name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
