package cn.iocoder.sanitation.messaging;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.GetResponse;
import com.rabbitmq.client.ShutdownSignalException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.junit.jupiter.api.Assertions.*;

class PublishTest extends LabFixture {
    @Test void managementConnectionFailureIsTopologyUnknownBeforeAnySend() throws Exception {
        var item = event("management-down");
        accept(item);
        int unavailable = closedPort();
        try (var raw = Broker.connect(); var channel = raw.createChannel();
             var broker = new Broker(() -> unavailable, managementPassword())) {
            prepare(channel);
            var failure = assertThrows(Broker.TopologyUnavailable.class, () -> broker.topology(1));
            assertEquals("MANAGEMENT_IO", failure.getMessage());
            assertInstanceOf(java.io.IOException.class, failure.getCause());
            assertFalse(relay.publish(relay.claim(), broker, () -> fail("no confirmation")));
            assertFailure(item, "TOPOLOGY_UNKNOWN");
            assertNull(channel.basicGet(Broker.CLASSIC, false));
            snapshot();
        }
    }

    @Test void actualBrokerManagement401IsAClassifiedNonIoFailure() throws Exception {
        var item = event("management-http");
        accept(item);
        try (var raw = Broker.connect(); var channel = raw.createChannel();
             var broker = new Broker(() -> Database.port("MGMT_PORT", 15672), attempt())) {
            prepare(channel);
            var failure = assertThrows(Broker.TopologyUnavailable.class, () -> broker.topology(1));
            assertEquals("MANAGEMENT_HTTP_401", failure.getMessage());
            assertNull(failure.getCause());
            assertFalse(relay.publish(relay.claim(), broker, () -> fail("no confirmation")));
            assertFailure(item, "TOPOLOGY_UNKNOWN");
            assertNull(channel.basicGet(Broker.CLASSIC, false));
            snapshot();
        }
    }

    @Test void invalidTopologyShapeIsUnknownButWrongBrokerIdentityPropagates() throws Exception {
        var item = event("shape");
        accept(item);
        var reply = new java.util.concurrent.atomic.AtomicReference<>("{}");
        var server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/overview", exchange -> {
            byte[] bytes = reply.get().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var body = exchange.getResponseBody()) { body.write(bytes); }
        });
        server.start();
        try (var raw = Broker.connect(); var channel = raw.createChannel();
             var broker = new Broker(() -> server.getAddress().getPort(), attempt())) {
            prepare(channel);
            assertEquals("MANAGEMENT_RESPONSE_SHAPE",
                    assertThrows(Broker.TopologyUnavailable.class, () -> broker.topology(1)).getMessage());
            assertFalse(relay.publish(relay.claim(), broker, () -> fail("no confirmation")));
            assertFailure(item, "TOPOLOGY_UNKNOWN");
            ingest.sql.update("UPDATE outbox SET next_retry_at=UTC_TIMESTAMP(3)+INTERVAL 1 HOUR");
            var different = event("identity");
            accept(different);
            reply.set("{\"cluster_name\":\"wrong-fixture\"}");
            assertEquals("unexpected management broker", assertThrows(IllegalStateException.class,
                    () -> relay.publish(relay.claim(), broker, () -> fail("no confirmation"))).getMessage());
            assertEquals("PUBLISHING", status(different));
            assertNull(lastError(different));
            assertNull(channel.basicGet(Broker.CLASSIC, false));
            snapshot();
        } finally {
            server.stop(0);
        }
    }

    @Test void unavailablePublishConnectionIsTransportUnknownAfterValidTopology() throws Exception {
        var item = event("transport-unknown");
        accept(item);
        try (var raw = Broker.connect(); var channel = raw.createChannel();
             var broker = new Broker(() -> Database.port("MGMT_PORT", 15672), managementPassword(), closedPort())) {
            prepare(channel);
            assertTrue(await(() -> broker.topology(1) ? Boolean.TRUE : null));
            assertFalse(relay.publish(relay.claim(), broker, () -> fail("no confirmation")));
            assertFailure(item, "TRANSPORT_UNKNOWN");
            assertNull(channel.basicGet(Broker.CLASSIC, false));
            snapshot();
        }
    }

    @Test void confirmedMessageWithPostCheckFailureDoesNotBecomePublished() throws Exception {
        var item = event("post-check");
        accept(item);
        var port = new java.util.concurrent.atomic.AtomicInteger(Database.port("MGMT_PORT", 15672));
        int unavailable = closedPort();
        try (var raw = Broker.connect(); var channel = raw.createChannel();
             var broker = new Broker(port::get, managementPassword())) {
            prepare(channel);
            assertFalse(relay.publish(relay.claim(), broker, () -> port.set(unavailable)));
            assertFailure(item, "TOPOLOGY_UNKNOWN");
            var delivered = delivery(channel, Broker.CLASSIC);
            assertEquals(item.messageId(), delivered.getProps().getMessageId());
            channel.basicAck(delivered.getEnvelope().getDeliveryTag(), false);
            snapshot();
        }
    }

    @Test void unavailableOutcomeWritePreservesLeaseAndIdentityUntilConfirmedReclaim() throws Exception {
        var item = event("post-check-db");
        accept(item);
        var port = new java.util.concurrent.atomic.AtomicInteger(Database.port("MGMT_PORT", 15672));
        int unavailable = closedPort();
        String database = Database.required("MARKER") + "-db";
        try (var raw = Broker.connect(); var channel = raw.createChannel();
             var broker = new Broker(port::get, managementPassword())) {
            prepare(channel);
            var first = relay.claim();
            var stopping = new AtomicBoolean();
            try {
                var unknown = assertThrows(Relay.UpdateUnknown.class, () -> relay.publish(first, broker, () -> {
                    try { docker("stop", database); stopping.set(true); }
                    catch (Exception error) { throw new IllegalStateException(error); }
                    port.set(unavailable);
                }));
                assertEquals("DB_UPDATE_UNKNOWN", unknown.getMessage());
                assertEquals("TOPOLOGY_UNKNOWN", unknown.requestedFailure);
                assertFalse(unknown.brokerAndTopologyAccepted);
                assertEquals(first.id(), unknown.messageId);
                assertEquals(first.attempt(), unknown.attemptId);
                assertInstanceOf(org.springframework.transaction.CannotCreateTransactionException.class, unknown.getCause());
            } finally {
                if (stopping.get()) docker("start", database);
                port.set(Database.port("MGMT_PORT", 15672));
            }
            assertEquals("PUBLISHING", await(() -> status(item)));
            assertNull(lastError(item));
            assertArrayEquals(first.bytes(), ingest.sql.queryForObject("SELECT payload FROM outbox WHERE message_id=?",
                    byte[].class, first.id()));
            expire(first);
            var replacement = relay.claim();
            assertEquals(first.id(), replacement.id());
            assertArrayEquals(first.bytes(), replacement.bytes());
            assertNotEquals(first.attempt(), replacement.attempt());
            assertFalse(relay.finish(first, true, null));
            assertEquals("PUBLISHING", status(item));
            assertTrue(relay.publish(replacement, broker, () -> {}));
            var published = outcome(item);
            assertFalse(relay.finish(replacement, true, null));
            assertFalse(relay.finish(first, false, "STALE"));
            assertEquals(published, outcome(item));
            for (int i = 0; i < 2; i++) {
                var delivered = delivery(channel, Broker.CLASSIC);
                assertEquals(first.id(), delivered.getProps().getMessageId());
                assertArrayEquals(first.bytes(), delivered.getBody());
                channel.basicAck(delivered.getEnvelope().getDeliveryTag(), false);
            }
            snapshot();
        }
    }

    @Test void expiredThirdAttemptGoesDeadWithoutAFourthClaimOrAnyPublication() throws Exception {
        var item = event("lease-budget");
        accept(item);
        try (var raw = Broker.connect(); var channel = raw.createChannel()) {
            prepare(channel);
            Relay.Claim third = null;
            for (int i = 1; i <= 3; i++) {
                third = relay.claim();
                assertEquals(i, third.attempts());
                expire(third);
            }
            assertNull(relay.claim());
            assertEquals("DEAD", status(item));
            assertEquals("LEASE_EXHAUSTED", lastError(item));
            var dead = outcome(item);
            assertEquals(3, ((Number) dead.get("attempts")).intValue());
            assertFalse(relay.finish(third, true, null));
            assertFalse(relay.finish(third, false, "STALE"));
            assertEquals(dead, outcome(item));
            assertEquals(1, ingest.count("raw_event"));
            assertNull(channel.basicGet(Broker.CLASSIC, false));
            snapshot();
        }
    }

    private void assertFailure(Protocol item, String expectedReason) {
        assertEquals("RETRY_WAIT", status(item));
        assertEquals(expectedReason, lastError(item));
    }

    private String lastError(Protocol item) {
        return ingest.sql.queryForObject("SELECT last_error FROM outbox WHERE message_id=?", String.class, item.messageId());
    }

    private Map<String, Object> outcome(Protocol item) {
        return ingest.sql.queryForMap("SELECT status,version,attempt_id,attempts,last_error FROM outbox WHERE message_id=?",
                item.messageId());
    }

    private void expire(Relay.Claim claim) {
        ingest.sql.update("UPDATE outbox SET lease_until=UTC_TIMESTAMP(3)-INTERVAL 1 SECOND WHERE message_id=?", claim.id());
    }

    private static int closedPort() throws Exception {
        try (var socket = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))) {
            return socket.getLocalPort();
        }
    }

    private static String managementPassword() { return System.getenv("RABBITMQ_DEFAULT_PASS"); }

    @Test void concurrentRelayClaimsAndExpiredLeaseFencingUnderBothIsolations() throws Exception {
        var plans = new ArrayList<Map<String, Object>>();
        for (int isolation : new int[] {TransactionDefinition.ISOLATION_READ_COMMITTED, TransactionDefinition.ISOLATION_DEFAULT}) {
            ingest.sql.update("DELETE FROM outbox");
            ingest.sql.update("DELETE FROM raw_event");
            accept(event("one"));
            accept(event("two"));
            String actual = ingest.commit(isolation, ignored -> ingest.sql.queryForObject(
                    "SELECT @@transaction_isolation", String.class));
            plans.add(Map.of("isolation", actual, "ready", ingest.sql.queryForList("""
                    EXPLAIN SELECT message_id FROM outbox WHERE status IN ('PENDING','RETRY_WAIT')
                    AND next_retry_at<=UTC_TIMESTAMP(3) ORDER BY next_retry_at,message_id LIMIT 1 FOR UPDATE SKIP LOCKED
                    """), "lease", ingest.sql.queryForList("""
                    EXPLAIN SELECT message_id FROM outbox WHERE status='PUBLISHING'
                    AND lease_until<=UTC_TIMESTAMP(3) ORDER BY lease_until,message_id LIMIT 1 FOR UPDATE SKIP LOCKED
                    """)));
            var a = new Relay(ingest, "first", isolation);
            var b = new Relay(ingest, "second", isolation);
            var pool = Executors.newFixedThreadPool(2);
            try {
                var barrier = new CyclicBarrier(2);
                var left = pool.submit(() -> { barrier.await(5, TimeUnit.SECONDS); return a.claim(); });
                var right = pool.submit(() -> { barrier.await(5, TimeUnit.SECONDS); return b.claim(); });
                var x = left.get(8, TimeUnit.SECONDS);
                var y = right.get(8, TimeUnit.SECONDS);
                // SKIP LOCKED can legitimately skip all currently locked rows. Retry once after both transactions finish.
                if (x == null) x = a.claim();
                if (y == null) y = b.claim();
                assertNotNull(x);
                assertNotNull(y);
                assertNotEquals(x.id(), y.id());
                assertEquals(2, ingest.sql.queryForObject("SELECT COUNT(*) FROM outbox WHERE status='PUBLISHING'", Integer.class));
                ingest.sql.update("UPDATE outbox SET lease_until=UTC_TIMESTAMP(3)-INTERVAL 1 SECOND WHERE message_id=?", x.id());
                var renewed = b.claim();
                assertEquals(x.id(), renewed.id());
                assertNotEquals(x.attempt(), renewed.attempt());
                assertEquals(x.version() + 1, renewed.version());
                assertFalse(a.finish(x, true, "STALE_CALLBACK"));
                assertTrue(b.finish(renewed, true, null));
                assertFalse(b.finish(renewed, true, null));
            } finally { pool.shutdownNow(); }
            evidence(actual, ingest.sql.queryForList("SELECT message_id,status,version,attempt_id FROM outbox"));
        }
        evidence("plans", plans);
    }

    @Test void committedClaimSurvivesRelayProcessExit() throws Exception {
        var item = event("relay-crash");
        accept(item);
        var child = child("relay-child.log", "relay");
        try {
            assertTrue(child.waitFor(30, TimeUnit.SECONDS));
            assertEquals(38, child.exitValue());
        } finally {
            if (child.isAlive()) child.destroyForcibly();
        }
        assertEquals("PUBLISHING", status(item));
        assertNull(relay.claim());
        var old = ingest.sql.queryForMap("SELECT attempt_id,version,owner FROM outbox WHERE message_id=?", item.messageId());
        ingest.sql.update("UPDATE outbox SET lease_until=UTC_TIMESTAMP(3)-INTERVAL 1 SECOND WHERE message_id=?", item.messageId());
        var recovered = relay.claim();
        assertNotEquals(old.get("attempt_id"), recovered.attempt());
        assertEquals(((Number) old.get("version")).longValue() + 1, recovered.version());
        assertEquals(item.messageId(), recovered.id());
        evidence("attempts", Map.of("expectedExit", 38, "old", old, "replacement", recovered.attempt()));
        snapshot();
    }

    @Test void realConfirmsReturnedNativeNackAndMissingExchange() throws Exception {
        try (var connection = Broker.connect(); var channel = connection.createChannel(); var broker = new Broker()) {
            prepare(channel);
            var item = event("pub");
            var rally = broker.publish(Broker.EXCHANGE, Broker.ROUTE, item.messageId(), attempt(), item.bytes());
            assertTrue(rally.accepted());
            var returned = broker.publish(Broker.EXCHANGE, "void", item.messageId(), attempt(), item.bytes());
            assertTrue(returned.ack());
            assertTrue(returned.returned());
            assertFalse(returned.accepted());
            String bound = "probe-bounded";
            channel.queueDeclare(bound, true, false, false,
                    Map.of("x-queue-type", "classic", "x-max-length", 1, "x-overflow", "reject-publish"));
            channel.queuePurge(bound);
            channel.queueBind(bound, Broker.EXCHANGE, "limit");
            assertTrue(broker.publish(Broker.EXCHANGE, "limit", "full", attempt(), item.bytes()).accepted());
            var nack = broker.publish(Broker.EXCHANGE, "limit", "overflow", attempt(), item.bytes());
            assertFalse(nack.ack());
            assertFalse(nack.returned());
            var missing = broker.publish("probe-nonexistent", Broker.ROUTE, item.messageId(), attempt(), item.bytes());
            assertFalse(missing.accepted());
            evidence("confirmed", Map.of("routed", rally, "returned", returned, "nativeNack", nack, "exchangeMissing", missing));
        }
    }

    @Test void expectedBindingAndVersionGatePUBLISHED() throws Exception {
        try (var connection = Broker.connect(); var channel = connection.createChannel(); var broker = new Broker()) {
            prepare(channel);
            assertTrue(await(() -> broker.topology(1) ? Boolean.TRUE : null));
            assertFalse(broker.topology(2));
            var item = event("binding");
            accept(item);
            channel.queueUnbind(Broker.QUORUM, Broker.EXCHANGE, Broker.ROUTE);
            assertTrue(broker.publish(Broker.EXCHANGE, Broker.ROUTE, item.messageId(), attempt(), item.bytes()).accepted());
            assertFalse(broker.topology(1));
            var claim = relay.claim();
            assertFalse(relay.publish(claim, broker, () -> fail("must not send")));
            assertEquals("RETRY_WAIT", status(item));
            ingest.sql.update("UPDATE outbox SET next_retry_at=UTC_TIMESTAMP(3)+INTERVAL 1 HOUR WHERE message_id=?", item.messageId());
            channel.queueBind(Broker.QUORUM, Broker.EXCHANGE, Broker.ROUTE);
            var wrong = event("topology-version");
            accept(wrong);
            ingest.sql.update("UPDATE outbox SET topology_version=9 WHERE message_id=?", wrong.messageId());
            assertFalse(relay.publish(relay.claim(), broker, () -> fail("must not send")));
            assertEquals("RETRY_WAIT", status(wrong));
            ingest.sql.update("UPDATE outbox SET next_retry_at=UTC_TIMESTAMP(3)+INTERVAL 1 HOUR WHERE message_id=?", wrong.messageId());
            var good = event("topology-good");
            accept(good);
            assertTrue(relay.publish(relay.claim(), broker, () -> {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                var observer = new Database("ingest");
                observer.commit(tx -> {
                    assertEquals(good.messageId(), observer.sql.queryForObject(
                            "SELECT message_id FROM outbox WHERE message_id=? FOR UPDATE NOWAIT", String.class, good.messageId()));
                    return null;
                });
            }));
            assertEquals("PUBLISHED", status(good));
            evidence("publish-boundary", Map.of("transactionActiveDuringPublish", false, "independentLockNowait", true));
            snapshot();
        }
    }

    @Test void confirmedMessageWithDatabaseDownCanBeRepublishedUnderSameId() throws Exception {
        var item = event("db-off");
        accept(item);
        String database = Database.required("MARKER") + "-db";
        try (var connection = Broker.connect(); var channel = connection.createChannel(); var broker = new Broker()) {
            prepare(channel);
            var first = relay.claim();
            var stopped = new AtomicBoolean();
            try {
                var update = assertThrows(Relay.UpdateUnknown.class,
                        () -> relay.publish(first, broker, () -> {
                            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                            try { docker("stop", database); stopped.set(true); }
                            catch (Exception error) { throw new IllegalStateException(error); }
                        }));
                assertInstanceOf(org.springframework.transaction.CannotCreateTransactionException.class, update.getCause());
                assertEquals("DB_UPDATE_UNKNOWN", update.getMessage());
                assertEquals(first.id(), update.messageId);
                assertEquals(first.attempt(), update.attemptId);
                assertTrue(update.brokerAndTopologyAccepted);
                assertNull(update.requestedFailure);
            } finally {
                if (stopped.get()) docker("start", database);
            }
            assertEquals("PUBLISHING", await(() -> status(item)));
            ingest.sql.update("UPDATE outbox SET lease_until=UTC_TIMESTAMP(3)-INTERVAL 1 SECOND WHERE message_id=?", item.messageId());
            var next = relay.claim();
            assertEquals(first.id(), next.id());
            assertNotEquals(first.attempt(), next.attempt());
            assertTrue(relay.publish(next, broker, () -> {}));
            for (int i = 0; i < 2; i++) {
                var delivered = delivery(channel, Broker.CLASSIC);
                assertEquals(item.messageId(), delivered.getProps().getMessageId());
                channel.basicAck(delivered.getEnvelope().getDeliveryTag(), false);
            }
            snapshot();
        }
    }

    @Test void durableClassicAndQuorumSurviveSameVolumeBrokerRestart() throws Exception {
        var item = event("restart");
        try (var connection = Broker.connect(); var channel = connection.createChannel(); var broker = new Broker()) {
            prepare(channel);
            assertTrue(await(() -> broker.topology(1) ? Boolean.TRUE : null));
            assertTrue(broker.publish(Broker.EXCHANGE, Broker.ROUTE, item.messageId(), attempt(), item.bytes()).accepted());
        }
        String node = Database.required("MQ_NAME");
        docker("stop", node);
        try {
            assertThrows(java.net.ConnectException.class, Broker::connect);
        } finally {
            docker("start", node);
        }
        awaitRabbitListener();
        try (var connection = await(Broker::connect); var channel = connection.createChannel(); var broker = new Broker()) {
            assertTrue(await(() -> broker.topology(1) ? Boolean.TRUE : null));
            for (String queue : new String[] {Broker.CLASSIC, Broker.QUORUM}) {
                var received = delivery(channel, queue);
                assertEquals(item.messageId(), received.getProps().getMessageId());
                assertEquals(2, received.getProps().getDeliveryMode());
                channel.basicAck(received.getEnvelope().getDeliveryTag(), false);
            }
        }
        evidence("restart", Map.of("exchange", Broker.EXCHANGE, "classic", Broker.CLASSIC,
                "quorum", Broker.QUORUM, "sameVolume", true, "deliveryMode", 2));
    }

    @Test void confirmTransportLossAllowsRealBrokerDuplicateWithStableId() throws Exception {
        var loopback = InetAddress.getByName("127.0.0.1");
        try (var raw = Broker.connect(); var witness = raw.createChannel();
             var proxy = new ServerSocket(0, 16, loopback)) {
            prepare(witness);
            var hold = new AtomicBoolean();
            var sockets = new CompletableFuture<Socket[]>();
            daemon(() -> {
                try {
                    var front = proxy.accept();
                    var back = new Socket(loopback, Database.port("MQ_PORT", 5672));
                    sockets.complete(new Socket[]{front, back});
                    daemon(() -> pump(front, back, new AtomicBoolean()));
                    pump(back, front, hold);
                } catch (Exception error) {
                    sockets.completeExceptionally(error);
                }
            });
            var producer = Broker.rawFactory(proxy.getLocalPort()).newConnection();
            var channel = producer.createChannel();
            var pair = sockets.get(5, TimeUnit.SECONDS);
            try {
                channel.confirmSelect();
                hold.set(true);
                var props = new AMQP.BasicProperties.Builder().messageId("m-lost-confirm").deliveryMode(2).build();
                channel.basicPublish(Broker.EXCHANGE, Broker.ROUTE, true, props, event("lost-confirm").bytes());
                GetResponse first = delivery(witness, Broker.CLASSIC);
                pair[0].close();
                pair[1].close();
                Exception observed = assertThrows(Exception.class, () -> channel.waitForConfirms(2000));
                assertTrue(observed instanceof ShutdownSignalException || observed instanceof TimeoutException);
                witness.confirmSelect();
                witness.basicPublish(Broker.EXCHANGE, Broker.ROUTE, true, props, event("lost-confirm").bytes());
                assertTrue(witness.waitForConfirms(5000));
                GetResponse second = delivery(witness, Broker.CLASSIC);
                assertEquals("m-lost-confirm", first.getProps().getMessageId());
                assertEquals(first.getProps().getMessageId(), second.getProps().getMessageId());
                witness.basicAck(first.getEnvelope().getDeliveryTag(), false);
                witness.basicAck(second.getEnvelope().getDeliveryTag(), false);
                evidence("transport", Map.of("brokerAccepted", true, "publisherConfirmLost", true,
                        "deliveries", 2, "messageId", first.getProps().getMessageId()));
            } finally {
                pair[0].close();
                pair[1].close();
                producer.abort();
            }
        }
    }

    private static void pump(Socket from, Socket to, AtomicBoolean hold) {
        try {
            var input = from.getInputStream();
            var output = to.getOutputStream();
            byte[] buffer = new byte[8192];
            for (int count; (count = input.read(buffer)) >= 0;) {
                if (!hold.get()) output.write(buffer, 0, count);
            }
        } catch (java.io.IOException expectedOnClose) {
            // The fault under test closes both proxy sockets.
        }
    }

    private static void daemon(Runnable run) {
        var thread = new Thread(run);
        thread.setDaemon(true);
        thread.start();
    }
}
