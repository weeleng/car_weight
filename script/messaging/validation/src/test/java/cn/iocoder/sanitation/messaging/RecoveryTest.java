package cn.iocoder.sanitation.messaging;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RecoveryTest extends LabFixture {
    @Test void boundedRetryDeadRetentionAndApprovedReplayKeepOriginalIdentity() throws Exception {
        var event = event("replay");
        accept(event);
        assertEquals(Store.T2.COMMITTED, store.consume(Protocol.CONSUMER, event.bytes(), Store.Fault.NONE));
        for (int i = 1; i <= 3; i++) {
            var claim = relay.claim();
            assertTrue(relay.finish(claim, false, "fixture"));
            assertEquals(i == 3 ? "DEAD" : "RETRY_WAIT", status(event));
            assertNull(relay.claim());
            if (i < 3) ingest.sql.update("UPDATE outbox SET next_retry_at=UTC_TIMESTAMP(3)-INTERVAL 1 SECOND");
        }
        assertEquals(1, ingest.count("outbox"));
        assertEquals(1, ingest.count("raw_event"));
        assertThrows(Protocol.Rejected.class, () -> relay.replay(event.messageId(), Protocol.CONSUMER, "operator", "operator", "test"));
        String attempt = relay.replay(event.messageId(), Protocol.CONSUMER, "operator", "reviewer", "fixture authorized");
        try (var connection = Broker.connect(); var channel = connection.createChannel(); var broker = new Broker()) {
            prepare(channel);
            var replay = relay.replayClaim(event.messageId(), attempt);
            assertArrayEquals(event.bytes(), replay.bytes());
            assertTrue(relay.publish(replay, broker, () -> {}));
            assertEquals(Store.T2.DUPLICATE, consumer(store, event).handle(channel,
                    delivery(channel, Broker.CLASSIC), Store.Fault.NONE, () -> {}));
        }
        assertEquals(1, business.count("fact"));
        assertEquals(1, business.count("business_outbox"));
        ingest.sql.update("UPDATE replay_audit SET reconciliation='ONE_FACT_CONFIRMED' WHERE attempt_id=?", attempt);
        evidence("replay", ingest.sql.queryForList("""
                SELECT attempt_id,message_id,event_id,digest,consumer_id,requester,approver,reason,
                    CAST(requested_at AS CHAR) AS requested_at,result,reconciliation FROM replay_audit
                """));
        snapshot();
    }

    @Test void localFailureHandoffProvenEvenWhenDeadLetterExchangeDoesNotExist() throws Exception {
        var event = event("held");
        var receiver = consumer(store, event);
        try (var connection = Broker.connect(); var channel = connection.createChannel()) {
            String queue = "probe-local-handoff";
            channel.queueDeclare(queue, true, false, false,
                    Map.of("x-queue-type", "classic", "x-dead-letter-exchange", "probe-unavailable-dlx"));
            channel.queuePurge(queue);
            byte[] malformed = "malformed".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            var wrong = new Protocol(event.producer(), "m-version", event.eventId(), event.businessKey(),
                    9, event.tenant(), event.project(), event.payload());
            for (var fixture : Map.of("FORMAT", malformed, "VERSION", wrong.bytes()).entrySet()) {
                channel.basicPublish("", queue, null, fixture.getValue());
                var delivery = delivery(channel, queue);
                assertEquals(fixture.getKey(), assertThrows(Protocol.Rejected.class,
                        () -> receiver.handle(channel, delivery, Store.Fault.NONE, () -> fail("no success ACK"))).getMessage());
                receiver.quarantine(channel, delivery, fixture.getKey());
            }
            channel.basicPublish("", queue, null, event.bytes());
            var failed = delivery(channel, queue);
            assertThrows(Store.Injected.class, () -> receiver.handle(channel, failed, Store.Fault.APPLICATION, () -> fail("no success ACK")));
            receiver.quarantine(channel, failed, "BUSINESS_ERROR");
            assertNull(channel.basicGet(queue, false));
            assertEquals(3, business.count("failure"));
            store.holdFailure("m-repeated", malformed, "FORMAT");
            store.holdFailure("m-repeated", malformed, "FORMAT");
            assertEquals("DEAD", business.sql.queryForObject("SELECT state FROM failure WHERE envelope_key=?",
                    String.class, Protocol.digest(malformed)));
            byte[] saved = business.sql.queryForObject("SELECT body FROM failure WHERE reason='BUSINESS_ERROR'", byte[].class);
            assertArrayEquals(event.bytes(), saved);
            assertThrows(Protocol.Rejected.class, () -> receiver.recoverHeld(Protocol.digest(saved), "operator", "operator", "test"));
            assertEquals(Store.T2.COMMITTED, receiver.recoverHeld(Protocol.digest(saved), "operator", "reviewer", "fixture verified"));
            assertNull(Consumer.current());
            assertThrows(Protocol.Rejected.class, () -> receiver.recoverHeld(Protocol.digest(saved),
                    "operator", "reviewer", "already applied"));
            assertEquals("RECOVERED", business.sql.queryForObject(
                    "SELECT state FROM failure WHERE envelope_key=?", String.class, Protocol.digest(saved)));
            evidence("broker", Map.of("deadLetterExchange", "probe-unavailable-dlx",
                    "localFailureRecords", 3, "localRecovery", true));
            snapshot();
        }
    }

    @Test void trackLocalRollbackReplayDuplicateAndLatePoint() throws Exception {
        var eg = event("track");
        accept(eg);
        assertThrows(Store.Injected.class, () -> recovery.track(eg.messageId(), "v1", 200, true));
        assertEquals(0, track.count("track_inbox"));
        assertEquals(1, ingest.count("raw_event"));
        assertTrue(recovery.track(eg.messageId(), "v1", 200, false));
        assertFalse(recovery.track(eg.messageId(), "v1", 200, false));
        assertTrue(recovery.track("late", "v1", 100, false));
        assertEquals(200L, track.sql.queryForObject("SELECT occurred FROM latest_position WHERE vehicle_id='v1'", Long.class));
        assertEquals(2, track.count("position_fact"));
        assertEquals(1, track.sql.queryForObject("""
                SELECT COUNT(*) FROM position_fact JOIN track_inbox USING(message_id) WHERE message_id=?
                """, Integer.class, eg.messageId()));
        snapshot();
    }

    @Test void reconcileNonAtomicRestorePointsByStableKeys() throws Exception {
        var early = event("ingest-earlier");
        var queued = event("broker-earlier");
        var newer = event("broker-later");
        var absentFact = event("missing-fact");
        var absentFollow = event("missing-followup");
        for (var event : new Protocol[]{queued, newer, absentFact, absentFollow}) accept(event);
        for (var event : new Protocol[]{early, absentFact, absentFollow}) {
            store.consume(Protocol.CONSUMER, event.bytes(), Store.Fault.NONE);
        }
        ingest.sql.update("UPDATE outbox SET status='PUBLISHED' WHERE message_id=?", queued.messageId());
        business.sql.update("DELETE FROM fact WHERE business_key=?", absentFact.businessKey());
        business.sql.update("DELETE FROM business_outbox WHERE business_key=?", absentFollow.businessKey());
        business.sql.update("UPDATE inbox SET digest=? WHERE message_id=?", "0".repeat(64), absentFact.messageId());
        var differences = recovery.reconcile(Set.of(newer.messageId()), Set.of(queued.messageId()));
        var causes = differences.stream().map(Recovery.Difference::reason).collect(Collectors.toSet());
        assertTrue(causes.containsAll(Set.of("INGEST_EARLIER", "BROKER_EARLIER", "BROKER_LATER",
                "BUSINESS_EARLIER", "UNCONSUMED", "DEVICE_ACK_UNOBSERVED", "MISSING_FACT", "MISSING_FOLLOWUP", "DIGEST_MISMATCH")));
        assertTrue(differences.contains(new Recovery.Difference("MISSING_FACT", absentFact.eventId(),
                absentFact.messageId(), Protocol.CONSUMER, absentFact.businessKey())));
        evidence("differences", differences);
        snapshot();
    }
}
