package cn.iocoder.sanitation.spool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.sqlite.SQLiteConnection;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

final class LegacyMigration {
    static final String TABLE = "iot_device_message_spool";
    private static final int TARGET_VERSION = 1;
    private static final String TARGET_BATCH = "CREATE TABLE migration_batch (batch TEXT PRIMARY KEY, source TEXT NOT NULL UNIQUE)";

    private static final String TARGET_ROWS = """
                CREATE TABLE migration_row (
                  batch TEXT NOT NULL, original_id INTEGER NOT NULL, original_time INTEGER NOT NULL,
                  original_size INTEGER NOT NULL, payload BLOB NOT NULL, sha256 TEXT NOT NULL, message_id TEXT,
                  state TEXT NOT NULL CHECK(state IN ('PENDING','PUBLISHED','QUARANTINED')), reason TEXT,
                  PRIMARY KEY (batch,original_id))
                """;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SCHEMA = "SELECT type || ':' || name || ':' || sql FROM sqlite_master ORDER BY type,name";
    private static final String LEGACY_CANONICAL = """
            index:idx_iot_device_message_spool_time:CREATE INDEX idx_iot_device_message_spool_time ON iot_device_message_spool(spool_time)
            table:iot_device_message_spool:CREATE TABLE iot_device_message_spool (id INTEGER PRIMARY KEY AUTOINCREMENT,spool_time INTEGER NOT NULL,size INTEGER NOT NULL,message TEXT NOT NULL)
            table:sqlite_sequence:CREATE TABLE sqlite_sequence(name,seq)
            """;

    record Row(long id, long time, long size, byte[] payload) {
        String hash() {
            return Sqlite.hash(payload);
        }
    }

    record Snapshot(String version, String journal, String engine, String schema, List<Row> rows) {
        String fingerprint() {
            return Sqlite.hash(schema.getBytes(StandardCharsets.UTF_8));
        }

        String manifest() {
            StringBuilder manifest = new StringBuilder(fingerprint());
            for (Row row : rows) {
                manifest.append('\n').append(row.id()).append(':').append(row.time())
                        .append(':').append(row.size()).append(':').append(row.hash());
            }
            return Sqlite.hash(manifest.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    static Snapshot inspect(Connection db) throws SQLException {
        StringBuilder schema = new StringBuilder();
        try (var query = db.createStatement(); var entries = query.executeQuery(SCHEMA)) {
            while (entries.next()) {
                schema.append(entries.getString(1)).append('\n');
            }
        }
        List<Row> data = new ArrayList<>();
        if (schema.indexOf("table:" + TABLE + ":") >= 0) {
            try (var query = db.createStatement();
                 var rows = query.executeQuery("SELECT id,spool_time,size,CAST(message AS BLOB) FROM " + TABLE + " ORDER BY id")) {
                while (rows.next()) {
                    data.add(new Row(rows.getLong(1), rows.getLong(2), rows.getLong(3), rows.getBytes(4)));
                }
            }
        }
        return new Snapshot(Sqlite.value(db, "PRAGMA user_version"), Sqlite.value(db, "PRAGMA journal_mode"),
                Sqlite.value(db, "SELECT sqlite_version()"), schema.toString(), List.copyOf(data));
    }

    private static void validateSource(Connection db, Snapshot snapshot) throws SQLException {
        if (!"ok".equals(Sqlite.value(db, "PRAGMA quick_check")) || !"0".equals(snapshot.version())
                || !(snapshot.schema().isEmpty() || normalize(LEGACY_CANONICAL).equals(normalize(snapshot.schema())))) {
            throw new SQLException("unknown, mixed, or damaged source schema");
        }
    }

    private static String normalize(String schema) {
        return schema.replaceAll("\\s+", "").toLowerCase(java.util.Locale.ROOT);
    }

    static void backup(Connection db, Path newBackup) throws SQLException, IOException {
        Files.createFile(newBackup);
        int result = db.unwrap(SQLiteConnection.class).getDatabase().backup("main", newBackup.toString(), null);
        if (result != 0) {
            throw new SQLException("SQLite snapshot backup returned " + result);
        }
    }

    static String migrate(String sourceId, Path source, Path target, Path backup, FileOwnership sourceOwner, FileOwnership targetOwner,
                          Runnable beforeCommit) throws SQLException, IOException {
        if (sourceId.isBlank() || source.toAbsolutePath().equals(target.toAbsolutePath())) {
            throw new IllegalArgumentException("distinct source and target and stable source id are required");
        }
        sourceOwner.check(source);
        targetOwner.check(target);
        Snapshot snapshot;
        try (var input = Sqlite.open(source, true)) {
            snapshot = inspect(input);
            validateSource(input, snapshot);
            backup(input, backup);
        }
        try (var copy = Sqlite.open(backup, true)) {
            if (!snapshot.manifest().equals(inspect(copy).manifest())) {
                throw new SQLException("source and backup differ");
            }
        }
        String batch = Sqlite.hash((sourceId + "\n" + snapshot.manifest()).getBytes(StandardCharsets.UTF_8));
        try (var output = Sqlite.open(target, false)) {
            output.setAutoCommit(false);
            try {
                initializeTarget(output);
                try (var query = output.prepareStatement("SELECT batch FROM migration_batch WHERE source=?")) {
                    query.setString(1, sourceId);
                    try (var found = query.executeQuery()) {
                        if (found.next() && !batch.equals(found.getString(1))) {
                            throw new SQLException("source manifest changed after a batch was stored; forward repair required");
                        }
                    }
                }
                try (var insert = output.prepareStatement("INSERT OR IGNORE INTO migration_batch VALUES (?,?)")) {
                    insert.setString(1, batch);
                    insert.setString(2, sourceId);
                    if (insert.executeUpdate() != 0) {
                        copyRows(output, snapshot, batch);
                    }
                }
                verifyRows(output, snapshot, batch);
                sourceOwner.check(source);
                targetOwner.check(target);
                beforeCommit.run();
                output.commit();
            } catch (SQLException | IOException | RuntimeException e) {
                output.rollback();
                throw e;
            }
        }
        return batch;
    }

    private static void initializeTarget(Connection db) throws SQLException {
        String version = Sqlite.value(db, "PRAGMA user_version");
        if (Integer.toString(TARGET_VERSION).equals(version)) {
            for (String ddl : List.of(TARGET_BATCH, TARGET_ROWS)) {
                String table = ddl.substring("CREATE TABLE ".length(), ddl.indexOf('(')).strip();
                String actual = Sqlite.value(db, "SELECT sql FROM sqlite_master WHERE type='table' AND name='" + table + "'");
                if (!normalize(ddl).equals(normalize(actual))) {
                    throw new SQLException("target table was replaced");
                }
            }
            if (!"2".equals(Sqlite.value(db, "SELECT count(*) FROM sqlite_master WHERE sql IS NOT NULL"))) {
                throw new SQLException("mixed target schema");
            }
            return;
        }
        if (!"0".equals(version) || !"0".equals(Sqlite.value(db, "SELECT count(*) FROM sqlite_master"))) {
            throw new SQLException("target schema not recognized");
        }
        Sqlite.exec(db, TARGET_BATCH);
        Sqlite.exec(db, TARGET_ROWS);
        Sqlite.exec(db, "PRAGMA user_version=" + TARGET_VERSION);
    }

    private static void copyRows(Connection db, Snapshot snapshot, String batch) throws SQLException {
        var knownIds = new HashSet<String>();
        try (var insert = db.prepareStatement("INSERT INTO migration_row VALUES (?,?,?,?,?,?,?,?,?)")) {
            for (Row row : snapshot.rows()) {
                String messageId = null;
                String reason = null;
                try {
                    JsonNode tree = JSON.readTree(row.payload());
                    JsonNode id = tree == null ? null : tree.get("id");
                    if (id == null || !id.isTextual() || id.textValue().isBlank()) {
                        reason = "MISSING_ID";
                    } else {
                        messageId = id.textValue();
                    }
                } catch (IOException e) {
                    reason = "INVALID_JSON";
                }
                if (messageId != null && !knownIds.add(messageId)) {
                    reason = "DUPLICATE_ID";
                }
                if (row.size() != row.payload().length) {
                    reason = "SIZE_MISMATCH";
                }
                insert.setString(1, batch);
                insert.setLong(2, row.id());
                insert.setLong(3, row.time());
                insert.setLong(4, row.size());
                insert.setBytes(5, row.payload());
                insert.setString(6, row.hash());
                insert.setString(7, messageId);
                insert.setString(8, reason == null ? "PENDING" : "QUARANTINED");
                insert.setString(9, reason);
                insert.executeUpdate();
            }
        }
    }

    private static void verifyRows(Connection db, Snapshot snapshot, String batch) throws SQLException {
        try (var query = db.prepareStatement("SELECT original_id,original_time,original_size,payload,sha256 "
                + "FROM migration_row WHERE batch=? ORDER BY original_id")) {
            query.setString(1, batch);
            try (var rows = query.executeQuery()) {
                for (Row expected : snapshot.rows()) {
                    if (!rows.next() || expected.id() != rows.getLong(1) || expected.time() != rows.getLong(2)
                            || expected.size() != rows.getLong(3) || !expected.hash().equals(rows.getString(5))
                            || !java.util.Arrays.equals(expected.payload(), rows.getBytes(4))) {
                        throw new SQLException("target row does not match source manifest");
                    }
                }
                if (rows.next()) {
                    throw new SQLException("unexpected target row");
                }
            }
        }
    }
}
