package cn.iocoder.sanitation.messaging;

import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.HashSet;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class ConsumerTest extends LabFixture {
    @Test void normalDeliveryCommitThenAck() throws Exception {
        var event = event("ack");
        try (var connection = Broker.connect(); var channel = connection.createChannel(); var broker = new Broker()) {
            prepare(channel);
            assertTrue(broker.publish(Broker.EXCHANGE, Broker.ROUTE, event.messageId(), attempt(), event.bytes()).accepted());
            var item = delivery(channel, Broker.CLASSIC);
            assertEquals(Store.T2.COMMITTED, consumer(store, event).handle(channel, item, Store.Fault.NONE, () -> {
                assertEquals(1, business.count("fact"));
                assertEquals(1, business.count("inbox"));
                assertEquals(1, business.count("business_outbox"));
                assertNull(Consumer.current());
            }));
            evidence("broker", Map.of("queueMessages", channel.queueDeclarePassive(Broker.CLASSIC).getMessageCount(),
                    "ackAfterT2", true));
            assertNull(channel.basicGet(Broker.CLASSIC, false));
            snapshot();
        }
    }

    @Test void rollbackDoesNotAckAndBrokerRedelivers() throws Exception {
        var event = event("rollback");
        try (var connection = Broker.connect(); var broker = new Broker()) {
            try (var channel = connection.createChannel()) {
                prepare(channel);
                assertTrue(broker.publish(Broker.EXCHANGE, Broker.ROUTE, event.messageId(), attempt(), event.bytes()).accepted());
                assertThrows(Store.Injected.class, () -> consumer(store, event).handle(channel,
                        delivery(channel, Broker.CLASSIC), Store.Fault.APPLICATION, () -> fail("must not ACK")));
                assertEquals(0, business.count("inbox"));
                assertNull(Consumer.current());
            }
            try (var replay = connection.createChannel()) {
                var item = delivery(replay, Broker.CLASSIC);
                assertTrue(item.getEnvelope().isRedeliver());
                assertEquals(Store.T2.COMMITTED, consumer(store, event).handle(replay, item, Store.Fault.NONE, () -> {}));
                evidence("broker", Map.of("redelivered", true, "rollbackAcked", false));
            }
        }
        snapshot();
    }

    @Test void realProcessExitAfterCommitBeforeAckLeavesOnlyOneEffect() throws Exception {
        var event = event("crash");
        try (var connection = Broker.connect(); var channel = connection.createChannel(); var broker = new Broker()) {
            prepare(channel);
            assertTrue(broker.publish(Broker.EXCHANGE, Broker.ROUTE, event.messageId(), attempt(), event.bytes()).accepted());
            var child = child("consumer-child.log");
            try {
                assertTrue(child.waitFor(30, TimeUnit.SECONDS), "consumer did not reach injected failure");
                assertEquals(37, child.exitValue());
            } finally {
                if (child.isAlive()) child.destroyForcibly();
            }
            var replay = delivery(channel, Broker.CLASSIC);
            assertTrue(replay.getEnvelope().isRedeliver());
            assertEquals(Store.T2.DUPLICATE, consumer(store, event).handle(channel, replay, Store.Fault.NONE, () -> {}));
            assertEquals(1, business.count("fact"));
            assertEquals(1, business.count("business_outbox"));
            assertEquals(1, business.count("inbox"));
            evidence("broker", Map.of("expectedProcessExit", 37, "actualProcessExit", child.exitValue(), "redelivered", true));
            snapshot();
        }
    }

    @Test void ackOnFailedChannelLeavesDeliveryForAnotherChannel() throws Exception {
        var event = event("ack-failure");
        try (var connection = Broker.connect(); var broker = new Broker()) {
            var broken = connection.createChannel();
            prepare(broken);
            assertTrue(broker.publish(Broker.EXCHANGE, Broker.ROUTE, event.messageId(), attempt(), event.bytes()).accepted());
            var received = delivery(broken, Broker.CLASSIC);
            assertThrows(com.rabbitmq.client.ShutdownSignalException.class,
                    () -> consumer(store, event).handle(broken, received, Store.Fault.NONE, () -> {
                        assertEquals(1, business.count("fact"));
                        try { broken.abort(); } catch (java.io.IOException error) { throw new IllegalStateException(error); }
                    }));
            try (var next = connection.createChannel()) {
                var replay = delivery(next, Broker.CLASSIC);
                assertTrue(replay.getEnvelope().isRedeliver());
                assertEquals(Store.T2.DUPLICATE, consumer(store, event).handle(next, replay, Store.Fault.NONE, () -> {}));
            }
            assertEquals(1, business.count("fact"));
            snapshot();
        }
    }

    @Test void failureHandoffIsNotAcknowledgedWhileDatabaseIsDown() throws Exception {
        var item = event("hold-db-down");
        String database = Database.required("MARKER") + "-db";
        try (var connection = Broker.connect(); var broker = new Broker()) {
            try (var channel = connection.createChannel()) {
                prepare(channel);
                assertTrue(broker.publish(Broker.EXCHANGE, Broker.ROUTE, item.messageId(), attempt(), item.bytes()).accepted());
                var delivery = delivery(channel, Broker.CLASSIC);
                docker("stop", database);
                try {
                    assertThrows(org.springframework.transaction.CannotCreateTransactionException.class,
                            () -> consumer(store, item).quarantine(channel, delivery, "UNAVAILABLE"));
                } finally {
                    docker("start", database);
                }
            }
            await(() -> business.count("failure"));
            try (var next = connection.createChannel()) {
                var replay = delivery(next, Broker.CLASSIC);
                assertTrue(replay.getEnvelope().isRedeliver());
                consumer(store, item).quarantine(next, replay, "RECOVERY_REQUIRED");
                assertEquals(1, business.count("failure"));
            }
        }
        snapshot();
    }

    @Test void twoLogicalInstancesHandleDuplicateBrokerCopiesConcurrently() throws Exception {
        var event = event("parallel-consumer");
        try (var connection = Broker.connect(); var a = connection.createChannel(); var b = connection.createChannel();
             var broker = new Broker()) {
            prepare(a);
            for (int i = 0; i < 2; i++) {
                assertTrue(broker.publish(Broker.EXCHANGE, Broker.ROUTE, event.messageId(), attempt(), event.bytes()).accepted());
            }
            var one = delivery(a, Broker.CLASSIC);
            var two = delivery(b, Broker.CLASSIC);
            var gate = new java.util.concurrent.CyclicBarrier(2);
            var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
            try {
                var left = pool.submit(() -> {
                    gate.await(5, TimeUnit.SECONDS);
                    return consumer(new Store(ingest, new Database("business")), event)
                            .handle(a, one, Store.Fault.NONE, () -> {});
                });
                var right = pool.submit(() -> {
                    gate.await(5, TimeUnit.SECONDS);
                    return consumer(new Store(ingest, new Database("business")), event)
                            .handle(b, two, Store.Fault.NONE, () -> {});
                });
                assertEquals(java.util.Set.of(Store.T2.COMMITTED, Store.T2.DUPLICATE),
                        java.util.Set.of(left.get(10, TimeUnit.SECONDS), right.get(10, TimeUnit.SECONDS)));
            } finally { pool.shutdownNow(); }
        }
        assertEquals(1, business.count("inbox"));
        assertEquals(1, business.count("fact"));
        assertEquals(1, business.count("business_outbox"));
        snapshot();
    }

    @Test void isolatedTrustedRegistryRechecksActorAndRejectsCrossProjectBeforeT2() throws Exception {
        var user = event("user-command");
        var fact = event("device-fact");
        var forged = new Protocol("fixture", "m-forged", fact.eventId(), "f-forged", 1, "tenant-b", "project-b", "synthetic");
        var revoked = new HashSet<String>();
        var receiver = new Consumer(store, Map.of(user.eventId(), new Consumer.Scope("tenant-a", "project-a", "scale-service", "original-user"),
                fact.eventId(), new Consumer.Scope("tenant-a", "project-a", "scale-service", null)), revoked);
        try (var connection = Broker.connect(); var channel = connection.createChannel()) {
            prepare(channel);
            for (Protocol event : new Protocol[]{user, forged, fact}) {
                var props = new com.rabbitmq.client.AMQP.BasicProperties.Builder().messageId(event.messageId())
                        .headers(Map.of("tenant-id", "tenant-b", "project-id", "project-b", "actor-id", "admin")).build();
                channel.basicPublish(Broker.EXCHANGE, Broker.ROUTE, props, event.bytes());
                var got = delivery(channel, Broker.CLASSIC);
                revoked.add("original-user");
                if (event == fact) {
                    assertThrows(Store.Injected.class, () -> receiver.handle(channel, got, Store.Fault.APPLICATION, () -> fail("early ACK")));
                    assertNull(Consumer.current());
                    assertEquals(Store.T2.COMMITTED, receiver.handle(channel, got, Store.Fault.NONE, () -> {}));
                } else {
                    assertEquals("AUTHORIZATION", assertThrows(Protocol.Rejected.class,
                            () -> receiver.handle(channel, got, Store.Fault.NONE, () -> fail("untrusted ACK"))).getMessage());
                    receiver.quarantine(channel, got, "AUTHORIZATION");
                }
                assertNull(Consumer.current());
            }
            assertEquals(1, business.count("fact"));
            assertEquals(2, business.count("failure"));
            snapshot();
        }
    }
}
