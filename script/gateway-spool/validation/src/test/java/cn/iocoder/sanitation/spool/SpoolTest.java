package cn.iocoder.sanitation.spool;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class SpoolTest extends LabTest {
    private static final byte[] BODY = new byte[]{1, 2, 3};
    private static final Spool.Category KEY = Spool.Category.KEY_MEASUREMENT;
    private static final long WEEK = TimeUnit.DAYS.toMillis(7);

    private static boolean save(Spool spool, String id, byte[] body, Spool.Category category) throws SQLException {
        return spool.append(id, body, category, 0, 5_000_000, 0, () -> Long.MAX_VALUE, NO_FAULT);
    }

    @Test void keyIsNotCommittedOnCrashBeforeTransactionAndSurvivesCrashAfter() throws Exception {
        Path before = tmp.resolve("before.db");
        Path after = tmp.resolve("after.db");
        assertEquals(39, await(start("write-before-halt", childCommand("spool-crash", before.toString(), "before")), 10));
        assertNull(new Spool(before).read("crash-event"));
        assertEquals(40, await(start("write-after-halt", childCommand("spool-crash", after.toString(), "after")), 10));
        var row = new Spool(after).read("crash-event");
        assertNotNull(row);
        assertArrayEquals(new byte[]{1, 2}, row.body());
        assertEquals("PENDING", row.state());
        Files.writeString(out().resolve("halt-matrix.txt"), "before=39 absent\nafter=40 durable PENDING\n");
    }

    @Test void brokerConfirmSqliteAndRollbackCannotProveT1() throws Exception {
        Spool spool = new Spool(tmp.resolve("proof-spool.db"));
        Ingest t1 = new Ingest(tmp.resolve("proof-ingest.db"), "fixture");
        assertTrue(save(spool, "event", BODY, KEY));
        assertNull(t1.lookup("event"));
        assertFalse(spool.handoff("event", t1, null));
        var attempt = spool.claim("event", "sender", 1, 5, 3);
        assertTrue(spool.finish(attempt, 2, true, 0, 3));
        assertEquals("BROKER_CONFIRMED", spool.read("event").state());
        assertNull(t1.receive("event", BODY, false));
        assertFalse(spool.handoff("event", t1, t1.lookup("event")));
        assertFalse(spool.reclaim("event", t1, null));
        assertArrayEquals(BODY, spool.read("event").body());
        var proof = t1.receive("event", BODY, true);
        // The successful response is not delivered. Reconcile by stable id from a fresh store.
        var reopened = new Ingest(tmp.resolve("proof-ingest.db"), "fixture");
        assertEquals(proof, reopened.lookup("event"));
        assertFalse(spool.handoff("event", reopened, new Ingest.Proof("foreign", "event", proof.hash(), proof.commitId())));
        assertFalse(spool.handoff("event", reopened, new Ingest.Proof("fixture", "other", proof.hash(), proof.commitId())));
        assertFalse(spool.handoff("event", reopened, new Ingest.Proof("fixture", "event", proof.hash(), "wrong")));
        assertFalse(spool.reclaim("event", reopened, proof));
        assertTrue(spool.handoff("event", reopened, reopened.lookup("event")));
        var result = spool.read("event");
        assertEquals("HANDOFF_COMPLETE", result.state());
        assertFalse(spool.handoff("event", reopened, proof));
        assertEquals(result.version(), spool.read("event").version());
        assertTrue(spool.reclaim("event", reopened, proof));
        assertFalse(spool.reclaim("event", reopened, proof));
        assertNull(spool.read("event").body());
        assertEquals(result.hash(), spool.read("event").hash());
        assertTrue(save(spool, "event", BODY, KEY));
        assertNull(spool.read("event").body());
        assertEquals(result.version() + 1, spool.read("event").version());
        try (var db = Sqlite.open(tmp.resolve("proof-spool.db"), true)) {
            assertEquals(proof.commitId(), Sqlite.value(db, "SELECT evidence FROM spool WHERE id='event'"));
        }
        Files.writeString(out().resolve("proof.txt"), "broker_confirm_requires_T1=true\nrollback_refused=true\n"
                + "loss_reconciled_by_id=true\nwrong_source_identity_commit_refused=true\nrepeated_proof_no_op=true\npayload_reclaimed_after_evidence=true\n");
    }

    @Test void conflictsUnknownAndOldCriticalRecordsStayDurable() throws Exception {
        Path db = tmp.resolve("retained.db");
        Spool spool = new Spool(db);
        assertTrue(save(spool, "event", BODY, KEY));
        assertTrue(save(spool, "event", BODY, KEY));
        assertFalse(save(spool, "event", new byte[]{4, 5}, KEY));
        assertTrue(save(spool, "unknown", BODY, Spool.Category.UNKNOWN));
        assertTrue(save(spool, "telemetry", BODY, Spool.Category.ORDINARY_TELEMETRY));
        assertEquals(1, spool.expireTelemetry(WEEK + 1));
        assertArrayEquals(BODY, spool.read("event").body());
        assertEquals("QUARANTINED", spool.read("unknown").state());
        assertNull(spool.claim("unknown", "owner", WEEK, 100, 3));
        try (var read = Sqlite.open(db, true)) {
            assertEquals("1", Sqlite.value(read, "SELECT count(*) FROM conflict"));
            assertEquals("0405", Sqlite.value(read, "SELECT hex(body) FROM conflict"));
        }
        var ingest = new Ingest(tmp.resolve("ingest.db"), "trusted");
        var first = ingest.receive("event", BODY, true);
        assertThrows(SQLException.class, () -> ingest.receive("event", new byte[]{4, 5}, true));
        assertEquals(first, ingest.lookup("event"));
        Files.writeString(out().resolve("retention.txt"), "ordinary_removed=1\nkey_retained=1\nunknown_retained=1\nconflict_retained=1\n");
    }

    @Test void staleLeaseAndCallbacksCannotChangeCurrentAttemptAndBudgetRetainsData() throws Exception {
        Path db = tmp.resolve("lease.db");
        Spool spool = new Spool(db);
        assertTrue(save(spool, "event", BODY, KEY));
        var old = spool.claim("event", "old", 0, 4, 2);
        assertNull(spool.claim("event", "second", 2, 4, 2));
        assertFalse(spool.finish(old, 4, true, 0, 2));
        var next = spool.claim("event", "new", 4, 4, 2);
        assertNotEquals(old.token(), next.token());
        assertTrue(next.version() > old.version());
        assertFalse(spool.finish(old, 5, true, 0, 2));
        assertFalse(spool.finish(new Spool.Attempt("event", "wrong", next.token(), next.version()), 5, true, 0, 2));
        assertNull(spool.claim("event", "third", 8, 4, 2));
        assertFalse(spool.finish(next, 9, true, 0, 2));
        var retained = new Spool(db).read("event");
        assertEquals("QUARANTINED", retained.state());
        assertEquals(2, retained.attempts());
        assertArrayEquals(BODY, retained.body());
        assertTrue(save(spool, "retry", BODY, KEY));
        var retry = spool.claim("retry", "sender", 0, 20, 2);
        assertTrue(spool.finish(retry, 1, false, 10, 2));
        assertFalse(spool.finish(retry, 1, true, 10, 2));
        assertEquals("RETRY_WAIT", spool.read("retry").state());
        assertNull(spool.claim("retry", "sender", 9, 4, 2));
        assertNotNull(spool.claim("retry", "sender", 10, 4, 2));
        Files.writeString(out().resolve("leases.txt"), "expired_budget=" + retained.state() + "\n"
                + "attempts=" + retained.attempts() + "\nstale_rejected=true\nduplicate_rejected=true\n");
    }

    @Test void quotasPhysicalReserveReadonlyAndControlledFullProtectOriginal() throws Exception {
        Path path = tmp.resolve("quota.db");
        Spool spool = new Spool(path);
        assertTrue(save(spool, "kept", BODY, KEY));
        var upstream = new java.util.HashMap<>(java.util.Map.of("new", BODY, "reservation", BODY));
        assertFalse(spool.append("new", upstream.get("new"), KEY, 0, 3, 0, () -> 1000, NO_FAULT));
        assertFalse(spool.append("reservation", upstream.get("reservation"), KEY, 0, 1000, 10, () -> 9, NO_FAULT));
        try (var denied = Sqlite.open(path, true)) {
            assertEquals(8, assertThrows(SQLException.class, () -> Sqlite.exec(denied, "DELETE FROM spool")).getErrorCode() & 255);
        }
        try (var full = Sqlite.open(path, false)) {
            Sqlite.exec(full, "PRAGMA max_page_count=" + Sqlite.value(full, "PRAGMA page_count"));
            var failure = assertThrows(SQLException.class, () -> Sqlite.exec(full,
                    "UPDATE spool SET body=zeroblob(500000) WHERE id='kept'"));
            assertEquals(13, failure.getErrorCode() & 255);
        }
        assertArrayEquals(BODY, spool.read("kept").body());
        assertNull(spool.read("new"));
        assertNull(spool.read("reservation"));
        assertEquals(2, upstream.size());
        Files.writeString(out().resolve("rejected.txt"), "quota_rejected=true\nreserve_9_under_10_rejected=true\n"
                + "upstream=2\nREADONLY=8\nFULL=13\noriginal_retained=true\n");
    }

    @Test void sqliteCapacityMeasuresSinglesBatchWalContentionAndBacklogRecovery() throws Exception {
        Path path = tmp.resolve("pressure.db");
        Spool spool = new Spool(path);
        byte[] payload = new byte[512];
        int rows = 240;
        long single = System.nanoTime();
        for (int i = 0; i < 40; i++) {
            assertTrue(save(spool, "single-" + i, payload, KEY));
        }
        long singlesNanos = System.nanoTime() - single;
        int[] contended = {0};
        long batchNanos;
        long peakBytes;
        long recoveryNanos;
        try (var db = Sqlite.open(path, false)) {
            Sqlite.exec(db, "PRAGMA wal_autocheckpoint=0");
            long batch = System.nanoTime();
            Sqlite.exec(db, "BEGIN IMMEDIATE");
            for (int i = 0; i < 120; i++) {
                Spool.update(db, "INSERT INTO spool(id,body,hash,category,created,state) VALUES (?,?,?,?,?,?)",
                        "batch-" + i, payload, Sqlite.hash(payload), KEY.name(), -WEEK * 2, "PENDING");
            }
            Sqlite.exec(db, "COMMIT");
            batchNanos = System.nanoTime() - batch;
            var pool = Executors.newFixedThreadPool(4);
            try {
                var accepted = new ArrayList<java.util.concurrent.Future<Boolean>>();
                for (int i = 0; i < 80; i++) {
                    String id = "concurrent-" + i;
                    accepted.add(pool.submit(() -> {
                        for (int attempt = 0; attempt < 5; attempt++) {
                            try {
                                return save(spool, id, payload, KEY);
                            } catch (SQLException failure) {
                                if ((failure.getErrorCode() & 255) != 5) {
                                    throw failure;
                                }
                                synchronized (contended) { contended[0]++; }
                            }
                        }
                        return false;
                    }));
                }
                for (var status : accepted) {
                    assertTrue(status.get(15, TimeUnit.SECONDS));
                }
            } finally {
                pool.shutdownNow();
            }
            long wal = Files.size(Path.of(path + "-wal"));
            assertTrue(wal > 0);
            peakBytes = bytes(path);
            assertEquals(Integer.toString(rows), Sqlite.value(db, "SELECT count(*) FROM spool"));
            assertEquals(0, spool.expireTelemetry(0));
            long recoveryStart = System.nanoTime();
            var t1 = new Ingest(tmp.resolve("capacity-ingest.db"), "capacity");
            for (String prefix : List.of("single-", "batch-", "concurrent-")) {
                int count = switch (prefix) { case "single-" -> 40; case "batch-" -> 120; default -> 80; };
                for (int i = 0; i < count; i++) {
                    String event = prefix + i;
                    assertTrue(spool.handoff(event, t1, t1.receive(event, payload, true)));
                }
            }
            recoveryNanos = System.nanoTime() - recoveryStart;
            assertEquals(Integer.toString(rows), Sqlite.value(db, "SELECT count(*) FROM spool WHERE state='HANDOFF_COMPLETE'"));
            Sqlite.exec(db, "PRAGMA wal_checkpoint(TRUNCATE)");
            assertEquals(0, Files.size(Path.of(path + "-wal")));
            Files.writeString(out().resolve("capacity.txt"), """
                    4 writer threads, one SQLite spool, %d records of %d bytes, 120 aged two weeks synthetically
                    single_average_ms=%.3f
                    batch_120_ms=%.3f
                    SQLITE_BUSY_retries=%d
                    wal_bytes_before_recovery=%d
                    spool_and_sidecars_bytes_before_recovery=%d
                    checkpointed_main_bytes=%d
                    local_T1_fixture_recovery_seconds=%.3f
                    local_T1_fixture_recovery_records_per_second=%.1f
                    """.formatted(rows, payload.length, singlesNanos / 40e6, batchNanos / 1e6, contended[0],
                    wal, peakBytes, Files.size(path), recoveryNanos / 1e9, rows / (recoveryNanos / 1e9)));
        }
    }

    private static long bytes(Path path) throws java.io.IOException {
        long total = 0;
        for (String suffix : List.of("", "-wal", "-shm")) {
            Path component = Path.of(path + suffix);
            if (Files.exists(component)) {
                total += Files.size(component);
            }
        }
        return total;
    }
}
