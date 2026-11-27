package cn.iocoder.sanitation.spool;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.GetResponse;
import com.rabbitmq.client.ShutdownSignalException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class BrokerTest extends LabTest {
    private static final byte[] PAYLOAD = "{\"weight\":123}".getBytes(StandardCharsets.UTF_8);
    private static final String ROUTE = "fixture";
    private static final String ID = "stable-synthetic-id";
    private static final int BUDGET = 4;

    static Connection open(int port) throws Exception {
        var factory = new ConnectionFactory();
        factory.setHost("127.0.0.1");
        factory.setPort(port);
        factory.setVirtualHost("fixture");
        factory.setUsername(System.getenv("LAB_MQ_USER"));
        factory.setPassword(System.getenv("LAB_MQ_PASSWORD"));
        factory.setConnectionTimeout(2000);
        factory.setAutomaticRecoveryEnabled(false);
        return factory.newConnection();
    }

    private static Connection open() throws Exception {
        return open(port());
    }

    private static int port() throws Exception {
        String mapping = docker("port", System.getenv("LAB_RABBIT"), "5672/tcp");
        if (!mapping.startsWith("127.0.0.1:")) {
            throw new IOException("expected a RabbitMQ loopback-only port");
        }
        return Integer.parseInt(mapping.substring("127.0.0.1:".length()));
    }

    private static void topology(Channel channel, String name, Map<String, Object> settings) throws IOException {
        channel.exchangeDeclare(name, "direct", true);
        channel.queueDeclare(name, true, false, false, settings);
        channel.queueBind(name, name, ROUTE);
    }

    private static void publish(Channel channel, String exchange) throws IOException {
        channel.basicPublish(exchange, ROUTE, true, new AMQP.BasicProperties.Builder()
                .deliveryMode(2).messageId(ID).contentType("application/json").build(), PAYLOAD);
    }

    private static GetResponse receive(Channel channel, String name) throws Exception {
        for (int i = 0; i < 40; i++) {
            var item = channel.basicGet(name, false);
            if (item != null) {
                assertEquals(ID, item.getProps().getMessageId());
                assertArrayEquals(PAYLOAD, item.getBody());
                return item;
            }
            Thread.sleep(75);
        }
        throw new AssertionError("expected real Broker delivery");
    }

    private static void ack(Channel channel, GetResponse item) throws IOException {
        channel.basicAck(item.getEnvelope().getDeliveryTag(), false);
    }

    private void evidence(String name, String fields) throws IOException {
        Files.writeString(out().resolve(name + ".txt"), fields + "\n");
    }

    @Test void rabbitMandatoryReturnAckNativeNackAndMissingExchange() throws Exception {
        String exchange = "confirm-fixture";
        try (Connection connection = open(); Channel pub = connection.createChannel()) {
            pub.exchangeDeclare(exchange, "direct", true);
            var returned = new AtomicInteger();
            pub.addReturnListener(ret -> returned.set(ret.getReplyCode()));
            pub.confirmSelect();
            publish(pub, exchange);
            assertTrue(pub.waitForConfirms(5000));
            assertEquals(312, returned.get());
            topology(pub, exchange, Map.of("x-max-length", 1L, "x-overflow", "reject-publish"));
            returned.set(0);
            publish(pub, exchange);
            assertTrue(pub.waitForConfirms(5000));
            assertEquals(0, returned.get());
            publish(pub, exchange);
            assertFalse(pub.waitForConfirms(5000));
            ack(pub, receive(pub, exchange));
            Channel missing = connection.createChannel();
            try {
                missing.confirmSelect();
                publish(missing, "never-declared-exchange");
                assertThrows(ShutdownSignalException.class, () -> missing.waitForConfirms(5000));
            } finally {
                if (missing.isOpen()) {
                    missing.abort();
                }
            }
        }
        evidence("rabbit-confirms", "confirmed=true\nmandatory_no_binding=ACK+312\nqueue_overflow=NACK\nmissing_exchange=channel_closed");
    }

    @Test void postConfirmTopologyRecheckAndSqliteWriteFailureKeepLocalPayload() throws Exception {
        Path path = tmp.resolve("post-check.db");
        Spool spool = created(path);
        var first = spool.claim(ID, "node-a", 1, 5, BUDGET);
        try (Connection connection = open(); Channel pub = connection.createChannel()) {
            String queue = "post-check-fixture";
            topology(pub, queue, Map.of());
            pub.confirmSelect();
            publish(pub, queue);
            assertTrue(pub.waitForConfirms(5000));
            ack(pub, receive(pub, queue));
            pub.queueDelete(queue);
            var passive = connection.createChannel();
            IOException absent = assertThrows(IOException.class, () -> passive.queueDeclarePassive(queue));
            assertInstanceOf(ShutdownSignalException.class, absent.getCause());
            assertFalse(passive.isOpen());
            assertEquals("PUBLISHING", spool.read(ID).state());
            var second = spool.claim(ID, "node-b", 7, 5, BUDGET);
            topology(pub, queue, Map.of());
            publish(pub, queue);
            assertTrue(pub.waitForConfirms(5000));
            try (var db = Sqlite.open(path, false)) {
                Sqlite.exec(db, """
                        CREATE TRIGGER block_confirm BEFORE UPDATE OF state ON spool
                        WHEN NEW.state='BROKER_CONFIRMED'
                        BEGIN SELECT RAISE(ABORT,'fixture write blocked'); END
                        """);
                assertThrows(java.sql.SQLException.class, () -> spool.finish(second, 8, true, 0, BUDGET));
                assertEquals("PUBLISHING", spool.read(ID).state());
                Sqlite.exec(db, "DROP TRIGGER block_confirm");
            }
            assertFalse(spool.finish(first, 8, true, 0, BUDGET));
            var third = spool.claim(ID, "node-c", 13, 5, BUDGET);
            publish(pub, queue);
            assertTrue(pub.waitForConfirms(5000));
            assertTrue(spool.finish(third, 14, true, 0, BUDGET));
            assertEquals("BROKER_CONFIRMED", spool.read(ID).state());
            assertArrayEquals(PAYLOAD, spool.read(ID).body());
            ack(pub, receive(pub, queue));
            ack(pub, receive(pub, queue));
        }
        evidence("rabbit-state-fault", "post_check_missing_queue=PUBLISHING\nSQLite_trigger_failure=PUBLISHING\nretry=BROKER_CONFIRMED");
    }

    @Test void confirmedThenProcessExitAllowsSameIdRedeliveryAndOneSyntheticFact() throws Exception {
        Path local = tmp.resolve("local.db");
        String queue = "pre-cleanup-exit-fixture";
        assertEquals(41, await(start("rabbit-publish-halt", childCommand("broker-crash", local.toString(), queue)), 20));
        Spool spool = new Spool(local);
        assertEquals("PUBLISHING", spool.read(ID).state());
        assertNotNull(spool.claim(ID, "restarted", 10, 5, BUDGET));
        var business = new Ingest(tmp.resolve("business-fixture.db"), "business-fixture");
        try (Connection connection = open(); Channel pub = connection.createChannel()) {
            pub.confirmSelect();
            publish(pub, queue);
            assertTrue(pub.waitForConfirms(5000));
            var first = receive(pub, queue);
            var second = receive(pub, queue);
            // Keep a failed delivery pending until the same payload and fact commit succeed.
            assertNull(business.receive(ID, first.getBody(), false));
            pub.basicNack(first.getEnvelope().getDeliveryTag(), false, true);
            var proof = business.receive(ID, second.getBody(), true);
            ack(pub, second);
            var redelivered = receive(pub, queue);
            assertTrue(redelivered.getEnvelope().isRedeliver());
            assertEquals(proof, business.receive(ID, redelivered.getBody(), true));
            ack(pub, redelivered);
        }
        try (var db = Sqlite.open(tmp.resolve("business-fixture.db"), true)) {
            assertEquals("1", Sqlite.value(db, "SELECT count(*) FROM raw"));
            assertEquals("1", Sqlite.value(db, "SELECT count(*) FROM outbox"));
        }
        assertNotNull(spool.read(ID));
        evidence("rabbit-recovery", "process_exit=41\nlocal=PUBLISHING\nunique_id_deliveries=2\nredelivery_after_rollback=true\none_fact=true");
    }

    static void publishAndHalt(Path path, String queue) throws Exception {
        var spool = created(path);
        spool.claim(ID, "crashing", 1, 5, BUDGET);
        try (Connection connection = open(); Channel publisher = connection.createChannel()) {
            topology(publisher, queue, Map.of());
            publisher.confirmSelect();
            publish(publisher, queue);
            if (!publisher.waitForConfirms(5000)) {
                throw new AssertionError("unexpected nack");
            }
            System.out.println("confirmed; crash before local state update and cleanup");
            Runtime.getRuntime().halt(41);
        }
    }

    @Test void rabbitDurableMessageRestartsWithOriginalIdentity() throws Exception {
        String queue = "restart-fixture";
        try (Connection connection = open(); Channel channel = connection.createChannel()) {
            topology(channel, queue, Map.of());
            channel.confirmSelect();
            publish(channel, queue);
            assertTrue(channel.waitForConfirms(5000));
        }
        String broker = System.getenv("LAB_RABBIT");
        int oldPort = port();
        docker("stop", broker);
        Path path = tmp.resolve("broker-outage.db");
        var offline = new Spool(path);
        try {
            assertThrows(IOException.class, () -> {
                try (var ignored = open(oldPort)) { }
            });
            for (int i = 0; i < 30; i++) {
                assertTrue(offline.append("outage-" + i, PAYLOAD, Spool.Category.KEY_MEASUREMENT, 0, 2_000, 0,
                        () -> Long.MAX_VALUE, NO_FAULT));
            }
        } finally {
            docker("start", broker);
        }
        try (Connection connection = waitRabbit(); Channel channel = connection.createChannel()) {
            ack(channel, receive(channel, queue));
        }
        try (var db = Sqlite.open(path, true)) {
            assertEquals("30", Sqlite.value(db, "SELECT count(*) FROM spool WHERE state='PENDING'"));
        }
        evidence("rabbit-restart", "stopped_connection_failed=true\nlocal_while_offline=30\ndurable_delivery_after_restart=true\nmessageId=" + ID
                + "\nhost_port_before=" + oldPort + "\nhost_port_after=" + port());
    }

    @Test void rabbitLostConfirmReplaysOriginalPayload() throws Exception {
        var address = InetAddress.getByName("127.0.0.1");
        try (var witnessConnection = open(); var witness = witnessConnection.createChannel();
             var proxy = new ServerSocket(0, 8, address)) {
            String queue = "confirm-loss-fixture";
            topology(witness, queue, Map.of());
            var hold = new AtomicBoolean();
            var sockets = new CompletableFuture<Socket[]>();
            int brokerPort = port();
            daemon(() -> {
                try {
                    var front = proxy.accept();
                    var back = new Socket(address, brokerPort);
                    sockets.complete(new Socket[]{front, back});
                    daemon(() -> pump(front, back, new AtomicBoolean()));
                    pump(back, front, hold);
                } catch (IOException failure) {
                    sockets.completeExceptionally(failure);
                }
            });
            Connection uncertain = open(proxy.getLocalPort());
            Channel publisher = uncertain.createChannel();
            Socket[] pair = sockets.get(5, TimeUnit.SECONDS);
            Path offline = tmp.resolve("offline.db");
            var spool = created(offline);
            var attempt = spool.claim(ID, "initial", 1, 9, BUDGET);
            try {
                publisher.confirmSelect();
                hold.set(true);
                publish(publisher, queue);
                var first = receive(witness, queue);
                pair[0].close();
                pair[1].close();
                Exception confirm = assertThrows(Exception.class, () -> publisher.waitForConfirms(2500));
                assertTrue(confirm instanceof ShutdownSignalException || confirm instanceof java.util.concurrent.TimeoutException);
                assertTrue(spool.finish(attempt, 2, false, 3, BUDGET));
                assertEquals("RETRY_WAIT", spool.read(ID).state());
                var retry = spool.claim(ID, "replay", 3, 5, BUDGET);
                witness.confirmSelect();
                publish(witness, queue);
                assertTrue(witness.waitForConfirms(5000));
                assertTrue(spool.finish(retry, 4, true, 0, BUDGET));
                var copy = receive(witness, queue);
                assertEquals(first.getProps().getMessageId(), copy.getProps().getMessageId());
                ack(witness, first);
                ack(witness, copy);
            } finally {
                pair[0].close();
                pair[1].close();
                uncertain.abort();
            }
        }
        evidence("rabbit-confirm-loss", "witness_broker_received=true\nproducer_confirmation_lost=true\nreplayed_same_id=true");
    }

    private static void daemon(Runnable action) {
        Thread thread = new Thread(action, "lab-amqp-proxy");
        thread.setDaemon(true);
        thread.start();
    }

    private static void pump(Socket input, Socket output, AtomicBoolean hold) {
        try {
            byte[] buffer = new byte[8192];
            for (int count; (count = input.getInputStream().read(buffer)) >= 0;) {
                if (!hold.get()) {
                    output.getOutputStream().write(buffer, 0, count);
                }
            }
        } catch (IOException expectedOnInjectedClose) {
            // Disconnect is deliberate after the witness reads the original delivery.
        }
    }

    private static Connection waitRabbit() throws Exception {
        for (int i = 0; i < 60; i++) {
            try {
                return open();
            } catch (IOException | java.util.concurrent.TimeoutException unavailable) {
                Thread.sleep(750);
            }
        }
        throw new AssertionError("Broker not ready after restart");
    }

    @Test void redisGroupsPendingRedeliveryOfflineAndRestartUseObservedAof() throws Exception {
        String name = "compat-fixture";
        String first = redis("XADD", name, "*", "id", ID);
        assertTrue(first.matches("\\d+-\\d+"));
        assertEquals("1", redis("XLEN", name));
        assertEquals("OK", redis("XGROUP", "CREATE", name, "fixture", "0"));
        Process died = start("redis-dead-consumer", childCommand("redis-crash", tmp.resolve("unused").toString(), name));
        assertEquals(42, await(died, 10));
        assertTrue(Files.readString(out().resolve("redis-dead-consumer.log")).contains(first));
        assertTrue(redis("XPENDING", name, "fixture").contains("dead"));
        assertTrue(redis("XCLAIM", name, "fixture", "recovered", "0", first).contains(first));
        assertEquals("1", redis("XACK", name, "fixture", first));
        assertEquals("0", redis("XACK", name, "fixture", first));

        String broker = System.getenv("LAB_REDIS");
        docker("stop", broker);
        try {
            assertThrows(IOException.class, () -> redis("PING"));
        } finally {
            docker("start", broker);
        }
        assertEquals("PONG", redis("PING"));
        assertTrue(redis("XRANGE", name, "-", "+").contains(first));
        assertEquals("1", redis("XLEN", name));
        evidence("redis-stream", "record=" + first + "\nno_subscriber_length=1\npending_owner=dead\nclaim=true\nXACK=1,0\nrestarted_record=true\n"
                + redis("INFO", "persistence"));
    }

    static void redisReadAndHalt(String stream) throws Exception {
        System.out.println(redis("XREADGROUP", "GROUP", "fixture", "dead", "COUNT", "1", "STREAMS", stream, ">"));
        Runtime.getRuntime().halt(42);
    }

    @Test void confirmedCleanupFailurePreservationOccursInDownstreamFaultDomain() throws Exception {
        String queue = "after-cleanup-fixture";
        String failedQueue = "after-cleanup-preserved";
        Path path = tmp.resolve("early-cleanup.db");
        var spool = created(path);
        try (Connection connection = open(); Channel publisher = connection.createChannel()) {
            topology(publisher, queue, Map.of());
            topology(publisher, failedQueue, Map.of());
            publisher.confirmSelect();
            publish(publisher, queue);
            assertTrue(publisher.waitForConfirms(5000));
            // Explicit fault fixture bypasses the candidate proof guard to exercise this unsafe ordering.
            try (var db = Sqlite.open(path, false)) {
                assertEquals(1, Spool.update(db, "DELETE FROM spool WHERE id=?", ID));
            }
            var ingest = new Ingest(tmp.resolve("failed-proof.db"), "fixture");
            var first = receive(publisher, queue);
            assertNull(ingest.receive(ID, first.getBody(), false));
            assertNull(spool.read(ID));
            publish(publisher, failedQueue);
            assertTrue(publisher.waitForConfirms(5000));
            ack(publisher, first);
            var stored = receive(publisher, failedQueue);
            assertTrue(ingest.verify(ID, Sqlite.hash(PAYLOAD), ingest.receive(ID, stored.getBody(), true)));
            ack(publisher, stored);
        }
        evidence("rabbit-after-cleanup", "forced_early_delete=true\nconsumer_rollback=true\n"
                + "failure_copy_confirmed_before_ack=true\nreplay_from_failure_queue=true\n");
    }

    @Test void boundedBrokerReplayMeasuresConfirmAndRecovery() throws Exception {
        int count = 60;
        byte[] payload = new byte[512];
        var spool = new Spool(tmp.resolve("rate.db"));
        for (int i = 0; i < count; i++) {
            assertTrue(spool.append("batch-" + i, payload, Spool.Category.KEY_MEASUREMENT, 0, 60_000, 0,
                    () -> Long.MAX_VALUE, NO_FAULT));
        }
        String queue = "small-pressure-fixture";
        try (Connection connection = open(); Channel publisher = connection.createChannel()) {
            topology(publisher, queue, Map.of());
            publisher.confirmSelect();
            long started = System.nanoTime();
            long confirms = 0;
            for (int i = 0; i < count; i++) {
                String id = "batch-" + i;
                var attempt = spool.claim(id, "benchmark", 1, 5, BUDGET);
                long before = System.nanoTime();
                publisher.basicPublish(queue, ROUTE, true, new AMQP.BasicProperties.Builder()
                        .deliveryMode(2).messageId(id).build(), payload);
                assertTrue(publisher.waitForConfirms(5000));
                confirms += System.nanoTime() - before;
                assertTrue(spool.finish(attempt, 2, true, 0, BUDGET));
            }
            double elapsed = (System.nanoTime() - started) / 1e9;
            assertEquals(count, publisher.messageCount(queue));
            evidence("rabbit-throughput", "messages=60\nbytes_each=512\nconfirm_average_ms=" + confirms / 60e6
                    + "\nmeasured_net_replay_per_second=" + count / elapsed + "\n");
        }
    }

    @Test void redisSingleCandidateOwnerReplaysOnceAndPersistsProofSeparately() throws Exception {
        Path path = tmp.resolve("single-owner.db");
        Spool spool = created(path);
        try (var owner = FileOwnership.acquire(path)) {
            owner.check(path);
            assertThrows(IOException.class, () -> FileOwnership.acquire(path));
            var attempt = spool.claim(ID, "owner", 1, 80, BUDGET);
            assertNotNull(attempt);
            String result = redis("XADD", "single-owner-fixture", "*", "id", ID);
            assertTrue(result.matches("\\d+-\\d+"));
            assertTrue(spool.finish(attempt, 2, true, 0, BUDGET));
            assertNull(spool.claim(ID, "other", 89, 80, BUDGET));
            assertEquals("1", redis("XLEN", "single-owner-fixture"));
            assertEquals("BROKER_CONFIRMED", spool.read(ID).state());
        }
        evidence("redis-single-owner", "claim=1\nXADD=1\nINGEST_proof=absent\nlocal_state=BROKER_CONFIRMED");
    }

    private static Spool created(Path path) throws Exception {
        Spool spool = new Spool(path);
        assertTrue(spool.append(ID, PAYLOAD, Spool.Category.KEY_MEASUREMENT, 0, 1024, 0,
                () -> Long.MAX_VALUE, NO_FAULT));
        return spool;
    }

    private static java.util.List<String> redisCommand(String... args) {
        var command = new java.util.ArrayList<>(java.util.List.of("docker", "exec", System.getenv("LAB_REDIS"), "redis-cli", "--raw"));
        command.addAll(java.util.List.of(args));
        return command;
    }

    private static String redis(String... args) throws Exception {
        return command(redisCommand(args));
    }

    private static String docker(String... args) throws Exception {
        var command = new java.util.ArrayList<>(java.util.List.of("docker"));
        command.addAll(java.util.List.of(args));
        return command(command);
    }

    private static String command(java.util.List<String> args) throws Exception {
        var process = new ProcessBuilder(args).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        if (await(process, 40) != 0) {
            throw new IOException(output);
        }
        return output;
    }
}
