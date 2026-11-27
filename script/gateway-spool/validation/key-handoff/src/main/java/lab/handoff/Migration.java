package lab.handoff;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;

final class Migration {
    static final String LEGACY = "iot_device_message_spool";
    private static final int CANDIDATE = 2002;
    private static final String TABLE = "CREATE TABLE " + LEGACY
            + " (id INTEGER PRIMARY KEY AUTOINCREMENT, spool_time INTEGER NOT NULL, size INTEGER NOT NULL, message TEXT NOT NULL)";
    private static final String INDEX = "CREATE INDEX idx_iot_device_message_spool_time ON " + LEGACY + " (spool_time)";
    private static final String SEQUENCE = "CREATE TABLE sqlite_sequence(name,seq)";
    private static final String SCHEMA = "SELECT sql FROM sqlite_master WHERE sql IS NOT NULL ORDER BY sql";

    record LegacyRow(long id, byte[] payload) {
        String digest() { return Db.hash(payload); }
    }

    static List<LegacyRow> historical(Connection db) throws SQLException {
        return Db.rows(db, "SELECT id, CAST(message AS BLOB) FROM " + LEGACY + " ORDER BY id",
                row -> new LegacyRow(row.getLong(1), row.getBytes(2)));
    }

    static void prepare(Path file, Path backup, String volume, Owner owner, Runnable cut) throws Exception {
        owner.require(file);
        // The owner marker is cooperative. The fixture assumes the old reader was fenced before upgrade.
        if (Files.notExists(file)) Files.createFile(file);
        try (var input = Db.open(file, true)) {
            int version = version(input);
            check(input, version);
            if (version == CANDIDATE) return;
            Db.backup(input,backup);
        }
        try (var input = Db.open(backup, true)) {
            check(input,0);
        }
        Db.tx(file, db -> {
            check(db,0);
            List<LegacyRow> historical = schema(db).isEmpty() ? List.of() : historical(db);
            try (var input = Db.open(backup,true)) {
                List<LegacyRow> copied = schema(input).isEmpty() ? List.of() : historical(input);
                if (!manifest(historical).equals(manifest(copied))) {
                    throw new SQLException("backup does not match original rows");
                }
            }
            if (schema(db).isEmpty()) {
                Db.exec(db,TABLE);
                Db.exec(db,INDEX);
            }
            String batch = Db.hash(volume + ":" + manifest(historical));
            for (String statement : Db.resource("/candidate.sql").split(";")) {
                if (!statement.isBlank()) Db.exec(db,statement);
            }
            for (LegacyRow row : historical) {
                Db.exec(db, """
                        INSERT INTO key_spool_quarantine(source_volume,source_row,migration_batch,payload,hash,reason)
                        VALUES (?,?,?,?,?,'V0_UNCLASSIFIED')
                        """, volume, Long.toString(row.id()), batch, row.payload(), row.digest());
            }
            Db.exec(db,"PRAGMA user_version="+CANDIDATE);
            cut.run();
            return null;
        });
        try (var db = Db.open(file,false)) {
            if (!"wal".equalsIgnoreCase(Db.text(db,"PRAGMA journal_mode=WAL"))) {
                throw new SQLException("WAL unavailable");
            }
        }
    }

    private static int version(Connection db) throws SQLException {
        return (int)Db.number(db,"PRAGMA user_version");
    }

    private static List<String> schema(Connection db) throws SQLException {
        return Db.rows(db,SCHEMA,row -> normalized(row.getString(1))).stream().sorted().toList();
    }

    private static void check(Connection input, int version) throws SQLException {
        if (!"ok".equals(Db.text(input,"PRAGMA quick_check"))) throw new SQLException("damaged SQLite");
        if (version != 0 && version != CANDIDATE) throw new SQLException("unknown schema version");
        var actual = schema(input);
        if (version == 0 && actual.isEmpty()) return;
        var expected = new java.util.ArrayList<>(List.of(normalized(TABLE),normalized(INDEX),normalized(SEQUENCE)));
        if (version == CANDIDATE) {
            for (String ddl : Db.resource("/candidate.sql").split(";")) {
                if (ddl.strip().startsWith("CREATE")) expected.add(normalized(ddl));
            }
        }
        expected.sort(null);
        if (!actual.equals(expected)) throw new SQLException("mixed or unsupported schema");
    }

    static String manifest(List<LegacyRow> records) {
        return Db.hash(records.stream().map(row -> row.id()+":"+row.digest()).reduce("", (a,b)->a+"\n"+b));
    }

    private static String normalized(String sql) {
        return sql.replaceAll("\\s+","").toLowerCase(Locale.ROOT);
    }

    static void assertOfflineRestoreEligible(Path file, Owner owner) throws IOException, SQLException {
        owner.require(file);
        try (var db=Db.open(file,true)) {
            check(db,CANDIDATE);
            if (Db.number(db,"SELECT count(*) FROM key_event_spool")!=0
                    || Db.number(db,"SELECT count(*) FROM lab_ordinary")!=0
                    || Db.number(db,"SELECT count(*) FROM key_spool_quarantine WHERE migration_batch IS NULL")!=0) {
                throw new SQLException("newly accepted facts require forward repair, never overwrite with backup");
            }
        }
    }

    static void legacy(Connection db) throws SQLException {
        Db.exec(db,TABLE);
        Db.exec(db,INDEX);
    }
}
