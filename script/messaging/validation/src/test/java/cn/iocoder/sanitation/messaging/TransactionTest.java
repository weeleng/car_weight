package cn.iocoder.sanitation.messaging;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class TransactionTest extends LabFixture {
    @Test void t1CommitAndEligibilityOnlyAfterBothRowsCommit() throws Exception {
        var item = event("first");
        var checked = new AtomicBoolean();
        assertTrue(Store.l2Eligible(store.receive(item, Store.Fault.NONE, () -> {
            var observer = new Database("ingest");
            assertEquals(0, observer.count("raw_event"));
            assertEquals(0, observer.count("outbox"));
            checked.set(true);
        })));
        assertTrue(checked.get());
        assertEquals(1, ingest.count("raw_event"));
        assertEquals(1, ingest.count("outbox"));
        snapshot();
    }

    @Test void t1RejectsRawAndOutboxFailuresAndRollsBackApplicationError() throws Exception {
        var invalid = new Protocol("fixture", "m-invalid", null, "f-invalid", 1, "tenant-a", "project-a", "synthetic");
        assertThrows(DataAccessException.class, () -> accept(invalid));
        assertThrows(DataAccessException.class, () -> store.receive(event("outbox"), Store.Fault.OUTBOX, () -> {}));
        assertThrows(Store.Injected.class, () -> store.receive(event("application"), Store.Fault.APPLICATION, () -> {}));
        assertThrows(Database.CommitNotProven.class, () -> store.receive(event("abort"), Store.Fault.ROLLBACK_ONLY, () -> {}));
        assertEquals(0, ingest.count("raw_event"));
        assertEquals(0, ingest.count("outbox"));
        snapshot();
    }

    @Test void t1DistinguishesDuplicatesConflictMissingIntentAndUnknownCommit() throws Exception {
        var item = event("once");
        assertEquals(Store.T1.UNKNOWN, store.receive(item, Store.Fault.UNKNOWN_AFTER_COMMIT, () -> {}));
        byte[] raw = item.payload().getBytes(StandardCharsets.UTF_8);
        assertFalse(Store.l2Eligible(Store.T1.UNKNOWN));
        assertTrue(Store.l2Eligible(store.recover(item.eventId(), raw)));
        assertEquals(Store.T1.DUPLICATE, store.receive(item, Store.Fault.NONE, () -> fail("duplicate must not insert")));
        assertFalse(Store.l2Eligible(store.recover(item.eventId(), "conflict".getBytes(StandardCharsets.UTF_8))));
        assertEquals(Store.T1.CONFLICT, store.recover(item.eventId(), "conflict".getBytes(StandardCharsets.UTF_8)));
        assertEquals(1, ingest.count("raw_conflict"));
        assertEquals(Protocol.digest(raw), ingest.sql.queryForObject(
                "SELECT digest FROM raw_event WHERE event_id=?", String.class, item.eventId()));
        assertThrows(DataAccessException.class, () -> ingest.sql.queryForList("SELECT * FROM probe_business.fact"));
        accept(event("different-id-same-payload"));
        assertEquals(2, ingest.count("raw_event"));
        ingest.sql.update("DELETE FROM outbox WHERE event_id=?", item.eventId());
        assertEquals(Store.T1.MISSING_INTENT, store.recover(item.eventId(), raw));
        snapshot();
    }

    @Test void t1ConcurrentDuplicateWritesOnePair() throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        try {
            var gate = new CyclicBarrier(2);
            var item = event("race");
            var first = pool.submit(() -> { gate.await(5, TimeUnit.SECONDS); return store.receive(item, Store.Fault.NONE, () -> {}); });
            var second = pool.submit(() -> { gate.await(5, TimeUnit.SECONDS); return store.receive(item, Store.Fault.NONE, () -> {}); });
            assertEquals(java.util.Set.of(Store.T1.COMMITTED, Store.T1.DUPLICATE),
                    java.util.Set.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)));
            assertEquals(1, ingest.count("raw_event"));
            assertEquals(1, ingest.count("outbox"));
        } finally {
            pool.shutdownNow();
        }
        snapshot();
    }

    @Test void t2RollbackCommitFailureUnknownCommitAndVersionGate() throws Exception {
        byte[] bytes = event("business").bytes();
        assertThrows(Store.Injected.class, () -> store.consume(Protocol.CONSUMER, bytes, Store.Fault.APPLICATION));
        assertThrows(DataAccessException.class, () -> store.consume(Protocol.CONSUMER, bytes, Store.Fault.OUTBOX));
        assertThrows(Database.CommitNotProven.class, () -> store.consume(Protocol.CONSUMER, bytes, Store.Fault.ROLLBACK_ONLY));
        assertThrows(org.springframework.dao.DataAccessResourceFailureException.class,
                () -> store.consume(Protocol.CONSUMER, bytes, Store.Fault.COMMIT_FAILURE));
        assertEquals(0, business.count("fact"));
        assertEquals(0, business.count("inbox"));
        assertEquals(0, business.count("business_outbox"));
        var incompatible = new Protocol("fixture", "m-version", "e-version", "f-version", 9,
                "tenant-a", "project-a", "synthetic");
        assertEquals("VERSION", assertThrows(Protocol.Rejected.class,
                () -> store.consume(Protocol.CONSUMER, incompatible.bytes(), Store.Fault.NONE)).getMessage());
        assertThrows(Store.CommitUnknown.class, () -> store.consume(Protocol.CONSUMER, bytes, Store.Fault.UNKNOWN_AFTER_COMMIT));
        assertEquals(Store.T2.DUPLICATE, store.consume(Protocol.CONSUMER, bytes, Store.Fault.NONE));
        assertEquals(1, business.count("fact"));
        assertEquals(1, business.count("business_outbox"));
        snapshot();
    }

    @Test void t2ConcurrentDedupAndIndependentBusinessKey() throws Exception {
        var item = event("race");
        var gate = new CyclicBarrier(2);
        var pool = Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<Store.T2> run = () -> {
                gate.await(5, TimeUnit.SECONDS);
                return new Store(ingest, new Database("business")).consume(Protocol.CONSUMER, item.bytes(), Store.Fault.NONE);
            };
            var first = pool.submit(run);
            var second = pool.submit(run);
            assertEquals(java.util.Set.of(Store.T2.COMMITTED, Store.T2.DUPLICATE),
                    java.util.Set.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)));
        } finally { pool.shutdownNow(); }
        var corrupt = new Protocol(item.producer(), item.messageId(), item.eventId(), item.businessKey(),
                1, "tenant-a", "project-a", "different");
        assertEquals("IDENTITY_CONFLICT", assertThrows(Protocol.Rejected.class,
                () -> store.consume(Protocol.CONSUMER, corrupt.bytes(), Store.Fault.NONE)).getMessage());
        assertEquals(Store.T2.EXISTING_FACT, store.consume(Protocol.CONSUMER, otherMessage(item).bytes(), Store.Fault.NONE));
        assertEquals(2, business.count("inbox"));
        assertEquals(1, business.count("fact"));
        assertEquals(1, business.count("business_outbox"));
        snapshot();
    }
}
