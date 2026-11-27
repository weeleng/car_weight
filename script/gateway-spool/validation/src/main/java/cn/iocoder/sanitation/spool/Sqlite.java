package cn.iocoder.sanitation.spool;

import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.HexFormat;

final class Sqlite {
    private Sqlite() { }

    static Connection open(Path path, boolean readOnly) throws SQLException {
        Connection db = DriverManager.getConnection("jdbc:sqlite:" + path.toUri() + (readOnly ? "?mode=ro" : ""));
        exec(db, "PRAGMA busy_timeout=250");
        return db;
    }

    static void exec(Connection db, String sql) throws SQLException {
        try (var statement = db.createStatement()) {
            statement.execute(sql);
        }
    }

    static String value(Connection db, String sql) throws SQLException {
        try (var statement = db.createStatement(); var rows = statement.executeQuery(sql)) {
            if (!rows.next()) {
                throw new SQLException("no result: " + sql);
            }
            return rows.getString(1);
        }
    }

    static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }
}
