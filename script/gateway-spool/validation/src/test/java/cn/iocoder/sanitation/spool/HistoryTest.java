package cn.iocoder.sanitation.spool;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;

class HistoryTest extends LabTest {
    private Path fixture(String name) throws IOException {
        return Files.copy(run().resolve("s1.db"), tmp.resolve(name));
    }

    private static LegacyMigration.Snapshot snapshot(Path path) throws SQLException {
        try (var db = Sqlite.open(path, true)) {
            return LegacyMigration.inspect(db);
        }
    }

    private void evidence(String name, Path path) throws Exception {
        var snapshot = snapshot(path);
        var rows = snapshot.rows().stream()
                .map(row -> "id=%d bytes=%d sha256=%s".formatted(row.id(), row.payload().length, row.hash())).toList();
        Files.writeString(out().resolve(name + ".txt"), "SQLite=" + snapshot.engine() + "\nversion=" + snapshot.version()
                + "\njournal=" + snapshot.journal() + "\nWAL=" + Files.exists(Path.of(path + "-wal"))
                + "\nfingerprint=" + snapshot.fingerprint() + "\nmanifest=" + snapshot.manifest() + "\n"
                + snapshot.schema() + "rows=" + rows.size() + "\n" + String.join("\n", rows) + "\n");
    }

    static void insert(Connection db, String json) throws SQLException {
        Spool.update(db, "INSERT INTO " + LegacyMigration.TABLE + "(spool_time,size,message) VALUES (?,?,?)",
                System.currentTimeMillis(), json.getBytes(StandardCharsets.UTF_8).length, json);
    }

    @Test void ownershipRejectsSameJvmSecondJvmAndReleasesOnCrash() throws Exception {
        Path db = fixture("owned.db");
        Path marker = tmp.resolve("first-marker");
        long start = System.nanoTime();
        Process first = start("holder", childCommand("hold", db.toString(), marker.toString()));
        try {
            waitForMarker(marker, first);
            assertThrows(IOException.class, () -> FileOwnership.acquire(db));
            assertEquals(23, await(start("contender", childCommand("hold", db.toString(), tmp.resolve("no").toString())), 8));
            first.destroyForcibly();
            assertEquals(137, await(first, 8));
            assertTrue(Files.isRegularFile(tmp.resolve("owned.db.owner.lock")));
            long takeover = System.nanoTime();
            assertEquals(0, await(start("takeover", childCommand("hold", db.toString(),
                    tmp.resolve("new-owner").toString(), "clean")), 8));
            Files.writeString(out().resolve("takeover.txt"), "crash recovery millis=" + (System.nanoTime() - takeover) / 1e6
                    + "\nwhole scenario millis=" + (System.nanoTime() - start) / 1e6 + "\n");
        } finally {
            first.destroyForcibly();
        }
        try (var owned = FileOwnership.acquire(db)) {
            owned.check(db);
            assertThrows(IOException.class, () -> FileOwnership.acquire(db));
            assertEquals(23, await(start("overlap", childCommand("hold", db.toString(), tmp.resolve("no").toString())), 8));
        }
        try (var owned = FileOwnership.acquire(db)) {
            owned.check(db);
        }
    }

    @Test void rejectsLinkedPathsAndReplacement() throws Exception {
        Path db = fixture("identity.db");
        Path link = Files.createSymbolicLink(tmp.resolve("linked.db"), db.getFileName());
        assertThrows(IOException.class, () -> FileOwnership.acquire(link));
        try (var owned = FileOwnership.acquire(db)) {
            Files.move(db, tmp.resolve("old.db"));
            fixture("identity.db");
            assertThrows(IOException.class, () -> owned.check(db));
        }
    }

    @Test void nativeS1AndS2AreByteEquivalentAtRowLevel() throws Exception {
        var s1 = snapshot(run().resolve("s1.db"));
        var s2 = snapshot(run().resolve("s2.db"));
        assertEquals("3.49.1", s1.engine());
        assertEquals("wal", s1.journal());
        assertEquals("0", s1.version());
        assertEquals(s1.fingerprint(), s2.fingerprint());
        assertEquals(s1.manifest(), s2.manifest());
        assertEquals(1, s1.rows().size());
        evidence("s1", run().resolve("s1.db"));
        evidence("s2", run().resolve("s2.db"));
    }

    @Test void migratesEmptyDatabase() throws Exception {
        Path db = tmp.resolve("empty.db");
        try (var ignored = Sqlite.open(db, false)) { }
        evidence("empty", db);
        Path target = tmp.resolve("output.db");
        migrate(db, target, "empty-backup.db", NO_FAULT);
        try (var read = Sqlite.open(target, true)) {
            assertEquals("0", Sqlite.value(read, "SELECT count(*) FROM migration_row"));
        }
    }

    @Test void backupRetainsUncheckpointedWalAndMigrationRecoversFromHalt() throws Exception {
        Path source = fixture("crash.db");
        assertEquals(37, await(start("wal-halt", childCommand("wal", source.toString())), 10));
        assertTrue(Files.size(Path.of(source + "-wal")) > 0);
        evidence("wal-before-migration", source);
        Path mainOnly = Files.copy(source, tmp.resolve("main-only.db"));
        assertEquals(1, snapshot(mainOnly).rows().size());
        assertEquals(2, snapshot(source).rows().size());
        Path target = tmp.resolve("migrated.db");
        Path backup = tmp.resolve("halt-backup.db");
        assertEquals(38, await(start("migrate-halt", childCommand("migrate", source.toString(),
                target.toString(), backup.toString())), 10));
        assertEquals(snapshot(source).manifest(), snapshot(backup).manifest());
        evidence("wal-consistent-backup", backup);
        try (var recovered = Sqlite.open(target, false)) {
            assertEquals("0", Sqlite.value(recovered, "PRAGMA user_version"));
            assertEquals("0", Sqlite.value(recovered, "SELECT count(*) FROM sqlite_master"));
        }
        assertNotNull(migrate(source, target, "retry-backup.db", NO_FAULT));
        try (var recovered = Sqlite.open(target, true)) {
            assertEquals("2", Sqlite.value(recovered, "SELECT count(*) FROM migration_row"));
        }
    }

    @Test void backlogBytesAndQuarantineArePreservedAndReimportIsIdempotent() throws Exception {
        Path source = fixture("backlog.db");
        try (var db = Sqlite.open(source, false)) {
            insert(db, "{\"id\":\"same\"}");
            insert(db, "{\"id\":\"same\",\"different\":true}");
            insert(db, "{\"missing\":1}");
            insert(db, "{bad-json");
        }
        evidence("backlog-source", source);
        var manifest = snapshot(source).manifest();
        var hash = Sqlite.hash(Files.readAllBytes(source));
        Path target = tmp.resolve("target.db");
        String batch = migrate(source, target, "backup-1.db", NO_FAULT);
        try (var output = Sqlite.open(target, false)) {
            assertEquals("1", Sqlite.value(output, "PRAGMA user_version"));
            Sqlite.exec(output, "UPDATE migration_row SET state='PUBLISHED' WHERE original_id=1");
        }
        assertEquals(batch, migrate(source, target, "backup-2.db", NO_FAULT));
        assertEquals(hash, Sqlite.hash(Files.readAllBytes(source)));
        assertEquals(manifest, snapshot(source).manifest());
        try (var output = Sqlite.open(target, true)) {
            assertEquals("5", Sqlite.value(output, "SELECT count(*) FROM migration_row"));
            assertEquals("1", Sqlite.value(output, "SELECT count(*) FROM migration_row WHERE state='PUBLISHED'"));
            assertEquals("1", Sqlite.value(output, "SELECT count(*) FROM migration_row WHERE state='PENDING'"));
            var list = Sqlite.value(output, "SELECT group_concat(reason) FROM (SELECT reason FROM migration_row "
                    + "WHERE state='QUARANTINED' ORDER BY original_id)");
            assertEquals("DUPLICATE_ID,MISSING_ID,INVALID_JSON", list);
            Files.writeString(out().resolve("backlog-quarantine.txt"), list + "\n"
                    + Sqlite.value(output, "SELECT group_concat(original_id || ':' || state || ':' || sha256,'\n') FROM migration_row"));
        }
    }

    @Test void changedSourceRequiresForwardRepairAndDoesNotReplacePublishedRows() throws Exception {
        Path source = fixture("unfrozen.db");
        Path target = tmp.resolve("target.db");
        migrate(source, target, "first-backup.db", NO_FAULT);
        try (var output = Sqlite.open(target, false); var changed = Sqlite.open(source, false)) {
            Sqlite.exec(output, "UPDATE migration_row SET state='PUBLISHED'");
            insert(changed, "{\"id\":\"unexpected-append\"}");
        }
        assertThrows(SQLException.class, () -> migrate(source, target, "changed-backup.db", NO_FAULT));
        try (var output = Sqlite.open(target, true)) {
            assertEquals("1", Sqlite.value(output, "SELECT count(*) FROM migration_row"));
            assertEquals("PUBLISHED", Sqlite.value(output, "SELECT state FROM migration_row"));
        }
        assertEquals(2, snapshot(source).rows().size());
    }

    @Test void rejectsUnknownMixedCorruptAndUnknownTarget() throws Exception {
        Path unknown = fixture("unknown.db");
        Path mixed = fixture("mixed.db");
        Path corrupted = Files.writeString(tmp.resolve("corrupt.db"), "not a database");
        try (var db = Sqlite.open(unknown, false); var other = Sqlite.open(mixed, false)) {
            Sqlite.exec(db, "PRAGMA user_version=99");
            Sqlite.exec(other, "CREATE TABLE unexplained (id INTEGER)");
        }
        evidence("unknown", unknown);
        evidence("mixed", mixed);
        for (Path source : new Path[]{unknown, mixed, corrupted}) {
            var original = Sqlite.hash(Files.readAllBytes(source));
            assertThrows(SQLException.class, () -> migrate(source, tmp.resolve("blocked.db"), "not-created.db", NO_FAULT));
            assertEquals(original, Sqlite.hash(Files.readAllBytes(source)));
        }
        Path target = tmp.resolve("wrong-target.db");
        try (var db = Sqlite.open(target, false)) {
            Sqlite.exec(db, "PRAGMA user_version=9");
        }
        var original = Sqlite.hash(Files.readAllBytes(target));
        assertThrows(SQLException.class, () -> migrate(fixture("source.db"), target, "no-upgrade.db", NO_FAULT));
        assertEquals(original, Sqlite.hash(Files.readAllBytes(target)));
    }

    @Test void readonlyLockCompetitionAndControlledFull() throws Exception {
        Path path = fixture("limits.db");
        try (var read = Sqlite.open(path, true)) {
            assertSqliteCode(8, assertThrows(SQLException.class, () -> insert(read, "{}")));
        }
        try (var first = Sqlite.open(path, false); var second = Sqlite.open(path, false)) {
            Sqlite.exec(first, "BEGIN IMMEDIATE");
            assertSqliteCode(5, assertThrows(SQLException.class, () -> insert(second, "{}")));
            Sqlite.exec(first, "ROLLBACK");
            Sqlite.exec(first, "PRAGMA max_page_count=" + Sqlite.value(first, "PRAGMA page_count"));
            assertSqliteCode(13, assertThrows(SQLException.class, () -> insert(first, "x".repeat(500_000))));
        }
        assertEquals(1, snapshot(path).rows().size());
        evidence("limits-row-protected", path);
        Files.writeString(out().resolve("sqlite-errors.txt"), "READONLY=8\nBUSY=5\nFULL=13; max_page_count\n");
    }

    private static void assertSqliteCode(int code, SQLException exception) {
        assertEquals(code, exception.getErrorCode() & 255, exception.toString());
    }

    private String migrate(Path source, Path target, String backup, Runnable fault) throws Exception {
        try (var sourceOwner = FileOwnership.acquire(source); var targetOwner = FileOwnership.acquire(target)) {
            return LegacyMigration.migrate("fixture-source", source, target, tmp.resolve(backup), sourceOwner, targetOwner, fault);
        }
    }
}
