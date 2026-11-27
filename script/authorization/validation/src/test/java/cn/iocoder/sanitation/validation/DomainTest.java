package cn.iocoder.sanitation.validation;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static cn.iocoder.sanitation.validation.Authorization.*;
import static cn.iocoder.sanitation.validation.Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class DomainTest {
    final Fixtures f = new Fixtures();
    final Observations queries = new Observations(f.policy, f.clock, observations());
    final PrivateFiles files = new PrivateFiles(f.policy, f.clock);
    final Selection download = selection(ONE, Data.FILE, Action.DOWNLOAD);
    final Selection export = selection(ONE, Data.OBSERVATION, Action.EXPORT);

    DomainTest() {
        files.business.put(1L, new PrivateFiles.Business(1, ONE, START));
        files.business.put(2L, new PrivateFiles.Business(2, OTHER, START));
        files.business.put(3L, new PrivateFiles.Business(3, TWO, START));
        files.files.put(11L, new PrivateFiles.File(11, 1, ONE, START, FULL.to(), false));
        files.files.put(12L, new PrivateFiles.File(12, 2, OTHER, START, FULL.to(), false));
        files.files.put(13L, new PrivateFiles.File(13, 3, TWO, START, FULL.to(), false));
        files.files.put(14L, new PrivateFiles.File(14, 1, ONE, START, FULL.to(), true));
    }

    @Test @Tag("positive")
    void authorizedMapTotalsDrillDownAndPageAgree() {
        assertEquals(Map.of("a", new Observations.Totals(1, 2, 5),
                "b", new Observations.Totals(1, 3, 6)), queries.map(REGULATOR, view(ONE)));
        assertEquals(List.of(1L), queries.drillDown(REGULATOR, view(ONE), "a")
                .stream().map(Observations.Observation::id).toList());
        assertEquals(2, queries.page(USER, view(ONE), 1, 1).get(0).id());
    }

    @Test @Tag("negative")
    void directIdsEmptyScopeAndWideHistoryNeverBroadenResults() {
        for (long id : List.of(3L, 4L, 5L, 999L)) assertTrue(queries.byId(USER, view(ONE), id).isEmpty());
        assertTrue(queries.map(REGULATOR, view(TWO)).isEmpty());
        assertTrue(queries.page(ADMIN, view(ONE), 0, 20).isEmpty());
        var broaderHistory = new Selection(ONE, Data.OBSERVATION, Action.VIEW,
                new Window(START.minusSeconds(999), START.plusSeconds(999)));
        assertTrue(queries.byId(USER, broaderHistory, 1).isPresent());
        assertTrue(queries.byId(USER, broaderHistory, 5).isEmpty());
        f.revoke(USER, true);
        assertTrue(queries.byId(USER, broaderHistory, 1).isEmpty());
    }

    @Test @Tag("positive")
    void historyUsesHalfOpenIntervalsAfterCardAndBoardReplacement() {
        var history = new History();
        for (String key : List.of("card", "board")) {
            assertTrue(history.add(new History.Relationship(key, "old", DATA_TIME, 1)));
            assertTrue(history.end(key, 1, START, 2));
            assertTrue(history.add(new History.Relationship(key, "replacement", FULL, 3)));
            assertEquals("old", history.resolve(key, DATA_TIME.from(), true).orElseThrow().owner());
            assertEquals(2, history.resolve(key, START.minusMillis(1), true).orElseThrow().version());
            assertEquals("replacement", history.resolve(key, START, true).orElseThrow().owner());
            assertTrue(history.resolve(key, FULL.to(), true).isEmpty());
            assertTrue(history.resolve(key, START, false).isEmpty());
        }
        assertEquals(6, history.revisions().size());
    }

    @Test @Tag("negative")
    void concurrentOverlapFailsAndHistoricalMembershipCannotAuthorizeNow() throws Exception {
        var history = new History();
        var begin = new CyclicBarrier(2);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> { begin.await(5, TimeUnit.SECONDS);
                return history.add(new History.Relationship("slot", "a", DATA_TIME, 1)); });
            var second = executor.submit(() -> { begin.await(5, TimeUnit.SECONDS);
                return history.add(new History.Relationship("slot", "b", FULL, 2)); });
            assertNotEquals(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
        assertEquals(1, history.revisions().size());
        assertTrue(history.resolve("slot", START, true).isPresent());
        assertTrue(queries.byId(ADMIN, view(ONE), 1).isEmpty());
    }

    @Test @Tag("positive")
    void proxyAndExportUseIndependentDownloadAuthority() {
        var chunks = new AtomicInteger();
        assertTrue(files.download(USER, download, 11, 2, n -> chunks.incrementAndGet()));
        assertEquals(2, chunks.get());
        assertTrue(files.create(1, USER, export, 14));
        assertTrue(files.execute(1, 2, n -> {}));
        assertTrue(files.downloadExport(1, USER, download));
        assertEquals(0, files.temporaryCount());
        assertEquals(List.of(PrivateFiles.Stage.DOWNLOAD, PrivateFiles.Stage.CREATE,
                PrivateFiles.Stage.EXECUTE, PrivateFiles.Stage.DOWNLOAD),
                files.audit.stream().map(PrivateFiles.Audit::stage).toList());
        assertNull(files.audit.get(3).reason());
    }

    @Test @Tag("negative")
    void unknownAndForeignFilesLookTheSameAndReassignmentIsRejected() {
        for (long id : List.of(12L, 13L, 14L, 999L)) {
            assertFalse(files.download(USER, download, id, 1, n -> fail("no direct bytes")));
            assertEquals(Reason.OBJECT_UNAVAILABLE, files.audit.get(files.audit.size() - 1).reason());
        }
        files.business.put(1L, new PrivateFiles.Business(1, TWO, START));
        assertFalse(files.download(USER, download, 11, 1, n -> fail("stale binding")));
    }

    @Test @Tag("negative")
    void fileExpiryDoesNotDependOnGrantExpiry() {
        files.files.put(11L, new PrivateFiles.File(11, 1, ONE, START, START.plusSeconds(1), false));
        f.clock.set(START.plusSeconds(1));
        assertTrue(f.policy.decide(USER, download).allowed());
        assertFalse(files.download(USER, download, 11, 1, n -> fail("expired asset")));
    }

    @Test @Tag("negative")
    void revocationBeforeAndDuringExportOrDownloadStopsRelease() {
        assertTrue(files.create(1, USER, export, 14));
        f.revoke(USER, true);
        assertFalse(files.execute(1, 2, n -> fail("revoked before execution")));
        f.revoke(USER, false);
        assertFalse(files.execute(1, 2, n -> {
            assertEquals(0, n);
            f.revoke(USER, true);
        }));
        assertEquals(0, files.temporaryCount());
        f.revoke(USER, false);
        assertTrue(files.execute(1, 1, n -> {}));
        f.revoke(USER, true);
        assertFalse(files.downloadExport(1, USER, download));
        f.revoke(USER, false);
        assertFalse(files.download(USER, download, 11, 2, n -> {
            assertEquals(0, n);
            f.revoke(USER, true);
        }));
        assertEquals(Reason.OBJECT_UNAVAILABLE, files.audit.get(files.audit.size() - 1).reason());
    }

    @Test @Tag("negative")
    void failedExportCleansStagedDataAndWritesFailureAudit() {
        assertTrue(files.create(1, USER, export, 14));
        assertThrows(IllegalStateException.class, () -> files.execute(1, 2, n -> {
            throw new IllegalStateException("fixture write failed");
        }));
        assertEquals(0, files.temporaryCount());
        assertEquals(Reason.UNAVAILABLE, files.audit.get(files.audit.size() - 1).reason());
        assertFalse(files.downloadExport(1, USER, download));
    }
}
