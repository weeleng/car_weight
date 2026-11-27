package cn.iocoder.sanitation.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.GetResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;

abstract class LabFixture {
    final Database ingest = new Database("ingest");
    final Database business = new Database("business");
    final Database track = new Database("track");
    final Store store = new Store(ingest, business);
    final Recovery recovery = new Recovery(ingest, business, track);
    final Relay relay = Relay.readCommitted(ingest, "relay-a");
    private String test;

    @BeforeEach
    void reset(TestInfo info) {
        test = info.getTestMethod().orElseThrow().getName();
        clear(ingest, "raw_event", "outbox", "replay_audit", "raw_conflict");
        clear(business, "inbox", "fact", "business_outbox", "failure");
        clear(track, "track_inbox", "position_fact", "latest_position");
    }

    private static void clear(Database db, String... tables) {
        for (String table : tables) db.sql.update("DELETE FROM " + table);
    }

    static Protocol event(String id) {
        return new Protocol("fixture", "m-" + id, "e-" + id, "f-" + id, 1, "tenant-a", "project-a", "synthetic");
    }

    static Protocol otherMessage(Protocol event) {
        return new Protocol(event.producer(), event.messageId() + "-other", event.eventId(),
                event.businessKey(), event.schemaVersion(), event.tenant(), event.project(), event.payload());
    }

    void accept(Protocol event) {
        org.junit.jupiter.api.Assertions.assertEquals(Store.T1.COMMITTED, store.receive(event, Store.Fault.NONE, () -> {}));
    }

    String status(Protocol event) {
        return ingest.sql.queryForObject("SELECT status FROM outbox WHERE message_id=?", String.class, event.messageId());
    }

    static Consumer consumer(Store store, Protocol item) {
        return new Consumer(store, Map.of(item.eventId(), new Consumer.Scope("tenant-a", "project-a",
                "scale-service", null)), Set.of());
    }

    static void prepare(Channel channel) throws Exception {
        Broker.declare(channel);
        channel.queuePurge(Broker.CLASSIC);
        channel.queuePurge(Broker.QUORUM);
    }

    static GetResponse delivery(Channel channel, String queue) throws Exception {
        return await(() -> channel.basicGet(queue, false));
    }

    static <T> T await(Callable<T> probe) throws Exception {
        Exception last = null;
        for (int i = 0; i < 90; i++) {
            try {
                T found = probe.call();
                if (found != null) return found;
            } catch (Exception failure) {
                last = failure;
            }
            Thread.sleep(150);
        }
        if (last != null) throw last;
        throw new AssertionError("fixture result not observed within bounded wait");
    }

    static Process child(String logfile, String... modes) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        var args = new java.util.ArrayList<>(List.of(java, "-cp", classpath, CrashConsumer.class.getName()));
        args.addAll(List.of(modes));
        return new ProcessBuilder(args).redirectErrorStream(true)
                .redirectOutput(Path.of(Database.required("EVIDENCE"), logfile).toFile()).start();
    }

    static String attempt() { return java.util.UUID.randomUUID().toString(); }

    void evidence(String title, Object value) throws Exception {
        var dir = Path.of(Database.required("EVIDENCE"));
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(dir.resolve(test + "-" + title + ".json").toFile(), value);
    }

    void snapshot() throws Exception {
        evidence("rows", Map.of(
                "raw", ingest.sql.queryForList("SELECT event_id,digest,device_observed_ack FROM raw_event ORDER BY event_id"),
                "conflicts", ingest.sql.queryForList("SELECT event_id,received_digest FROM raw_conflict"),
                "outbox", ingest.sql.queryForList("SELECT message_id,event_id,attempt_id,version,status,last_error,attempts FROM outbox ORDER BY message_id"),
                "inbox", business.sql.queryForList("SELECT * FROM inbox ORDER BY message_id"),
                "facts", business.sql.queryForList("SELECT * FROM fact ORDER BY business_key"),
                "followups", business.sql.queryForList("SELECT * FROM business_outbox ORDER BY business_key"),
                "failure", business.sql.queryForList("SELECT message_id,reason,attempts,state FROM failure"),
                "track", track.sql.queryForList("SELECT * FROM position_fact ORDER BY message_id")));
    }

    static String docker(String... arguments) throws Exception {
        String host = Database.required("DOCKER_HOST");
        String name = arguments[arguments.length - 1];
        String marker = Database.required("MARKER");
        if (!host.startsWith("unix://") || !java.util.Set.of(marker + "-mq", marker + "-db").contains(name)
                || arguments.length != 2 || !java.util.Set.of("stop", "start").contains(arguments[0])) {
            throw new IllegalStateException("wrong fixture");
        }
        List<String> base = List.of("docker", "--host", host);
        String label = command(base, "inspect", "--format", "{{index .Config.Labels \"org.weeleng.eng002e\"}}", name);
        if (!marker.equals(label.strip())) throw new IllegalStateException("not this lab's container");
        return command(base, arguments);
    }

    static void awaitRabbitListener() throws Exception {
        String host = Database.required("DOCKER_HOST");
        String marker = Database.required("MARKER");
        String name = Database.required("MQ_NAME");
        if (!host.startsWith("unix://") || !name.equals(marker + "-mq")) {
            throw new IllegalStateException("wrong fixture");
        }
        var base = List.of("docker", "--host", host);
        String label = command(base, "inspect", "--format", "{{index .Config.Labels \"org.weeleng.eng002e\"}}", name);
        if (!marker.equals(label.strip())) throw new IllegalStateException("not this lab's container");
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(60);
        IllegalStateException last;
        do {
            try {
                command(base, "exec", name, "rabbitmq-diagnostics", "-q", "check_port_connectivity");
                return;
            } catch (IllegalStateException unavailable) {
                last = unavailable;
                Thread.sleep(500);
            }
        } while (System.nanoTime() < deadline);
        throw last;
    }

    private static String command(List<String> base, String... arguments) throws Exception {
        var args = new java.util.ArrayList<>(base);
        args.addAll(List.of(arguments));
        var command = new ProcessBuilder(args).redirectErrorStream(true).start();
        if (!command.waitFor(25, java.util.concurrent.TimeUnit.SECONDS)) {
            command.destroyForcibly();
            throw new IllegalStateException("fixture Docker command timed out");
        }
        String result = new String(command.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        if (command.exitValue() != 0) throw new IllegalStateException("fixture Docker command failed: " + result);
        return result;
    }
}
