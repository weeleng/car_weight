package cn.iocoder.sanitation.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.function.IntSupplier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.AmqpIOException;
import org.springframework.amqp.AmqpTimeoutException;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

final class Broker implements AutoCloseable {
    static final String EXCHANGE = "probe-v1", CLASSIC = "probe-classic", QUORUM = "probe-quorum", ROUTE = "fact";
    private static final HttpClient MANAGEMENT_CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private static final ObjectMapper JSON = new ObjectMapper();
    private final CachingConnectionFactory factory;
    private final RabbitTemplate publisher;
    private final IntSupplier managementPort;
    private final String managementPassword;

    Broker() throws Exception {
        this(() -> Database.port("MGMT_PORT", 15672), System.getenv("RABBITMQ_DEFAULT_PASS"));
    }

    Broker(IntSupplier managementPort, String managementPassword) throws Exception {
        this(managementPort, managementPassword, Database.port("MQ_PORT", 5672));
    }

    Broker(IntSupplier managementPort, String managementPassword, int publishPort) throws Exception {
        try (var ignored = connect()) {
            // Verify the broker before the separate publishing connection is opened.
        }
        this.managementPort = managementPort;
        this.managementPassword = java.util.Objects.requireNonNull(managementPassword);
        factory = new CachingConnectionFactory(rawFactory(publishPort));
        factory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        factory.setPublisherReturns(true);
        publisher = new RabbitTemplate(factory);
        publisher.setMandatory(true);
        publisher.setReturnsCallback(ignored -> {});
    }

    static ConnectionFactory rawFactory(int port) {
        var f = new ConnectionFactory();
        f.setHost("127.0.0.1");
        f.setPort(port);
        f.setUsername("probe");
        f.setPassword(java.util.Objects.requireNonNull(System.getenv("RABBITMQ_DEFAULT_PASS")));
        f.setVirtualHost("probe");
        f.setAutomaticRecoveryEnabled(false);
        f.setConnectionTimeout(3000);
        f.setHandshakeTimeout(3000);
        return f;
    }

    static Connection connect() throws Exception {
        var connection = rawFactory(Database.port("MQ_PORT", 5672)).newConnection();
        var properties = connection.getServerProperties();
        if (!"4.2.9".equals(String.valueOf(properties.get("version")))
                || !Database.required("MARKER").equals(String.valueOf(properties.get("cluster_name")))) {
            connection.abort();
            throw new IllegalStateException("unexpected isolated RabbitMQ identity");
        }
        return connection;
    }

    static void declare(Channel channel) throws Exception {
        channel.exchangeDeclare(EXCHANGE, "direct", true);
        channel.queueDeclare(CLASSIC, true, false, false, Map.of("x-queue-type", "classic"));
        channel.queueDeclare(QUORUM, true, false, false, Map.of("x-queue-type", "quorum"));
        channel.queueBind(CLASSIC, EXCHANGE, ROUTE);
        channel.queueBind(QUORUM, EXCHANGE, ROUTE);
    }

    record Confirmation(boolean ack, boolean returned, String reason) {
        boolean accepted() { return ack && !returned; }
    }

    Confirmation publish(String exchange, String routing, String message, String attempt, byte[] bytes) throws Exception {
        CorrelationData cd = new CorrelationData(attempt);
        try {
            publisher.send(exchange, routing, MessageBuilder.withBody(bytes).setMessageId(message)
                    .setDeliveryMode(MessageDeliveryMode.PERSISTENT).setContentType("application/json").build(), cd);
            var confirmation = cd.getFuture().get(5, TimeUnit.SECONDS);
            return new Confirmation(confirmation.isAck(), cd.getReturned() != null, confirmation.getReason());
        } catch (AmqpConnectException | AmqpIOException | AmqpTimeoutException | TimeoutException error) {
            throw new PublicationUnavailable(error);
        } catch (ExecutionException error) {
            if (error.getCause() instanceof IOException || error.getCause() instanceof TimeoutException
                    || error.getCause() instanceof AmqpConnectException || error.getCause() instanceof AmqpIOException
                    || error.getCause() instanceof AmqpTimeoutException) {
                throw new PublicationUnavailable(error.getCause());
            }
            throw error;
        }
    }

    private JsonNode management(String path, boolean missingIsKnown) throws InterruptedException {
        int port = managementPort.getAsInt();
        String credentials = "probe:" + managementPassword;
        var token = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/" + path))
                .timeout(Duration.ofSeconds(4)).header("Authorization", "Basic " + token).build();
        HttpResponse<String> response;
        try {
            response = MANAGEMENT_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException error) {
            throw new TopologyUnavailable("MANAGEMENT_IO", error);
        }
        if (response.statusCode() == 404 && missingIsKnown) return JSON.missingNode();
        if (response.statusCode() != 200) throw new TopologyUnavailable("MANAGEMENT_HTTP_" + response.statusCode(), null);
        try {
            JsonNode body = JSON.readTree(response.body());
            if (body == null || body.isNull() || body.isMissingNode()) {
                throw new TopologyUnavailable("MANAGEMENT_RESPONSE_SHAPE", null);
            }
            return body;
        } catch (IOException error) {
            throw new TopologyUnavailable("MANAGEMENT_INVALID_JSON", error);
        }
    }

    boolean topology(int version) throws InterruptedException {
        if (version != Protocol.TOPOLOGY_VERSION) return false;
        var overview = management("overview", false);
        if (!overview.path("cluster_name").isTextual()) {
            throw new TopologyUnavailable("MANAGEMENT_RESPONSE_SHAPE", null);
        }
        if (!Database.required("MARKER").equals(overview.path("cluster_name").asText())) {
            throw new IllegalStateException("unexpected management broker");
        }
        var exchange = management("exchanges/probe/" + EXCHANGE, true);
        if (exchange.isMissingNode()) return false;
        if (!exchange.path("durable").isBoolean() || !exchange.path("type").isTextual()) {
            throw new TopologyUnavailable("MANAGEMENT_RESPONSE_SHAPE", null);
        }
        return exchange.path("durable").asBoolean() && "direct".equals(exchange.path("type").asText())
                && bound(CLASSIC, "classic") && bound(QUORUM, "quorum");
    }

    private boolean bound(String queue, String queueType) throws InterruptedException {
        var info = management("queues/probe/" + queue, true);
        if (info.isMissingNode()) return false;
        if (!info.path("durable").isBoolean() || !info.path("type").isTextual()) {
            throw new TopologyUnavailable("MANAGEMENT_RESPONSE_SHAPE", null);
        }
        if (!info.path("durable").asBoolean() || !queueType.equals(info.path("type").asText())) return false;
        var bindings = management("bindings/probe/e/" + EXCHANGE + "/q/" + queue, true);
        if (bindings.isMissingNode()) return false;
        if (!bindings.isArray()) throw new TopologyUnavailable("MANAGEMENT_RESPONSE_SHAPE", null);
        for (var binding : bindings) {
            if (!binding.path("routing_key").isTextual()) {
                throw new TopologyUnavailable("MANAGEMENT_RESPONSE_SHAPE", null);
            }
            if (ROUTE.equals(binding.path("routing_key").asText())) return true;
        }
        return false;
    }

    static final class TopologyUnavailable extends RuntimeException {
        TopologyUnavailable(String detail, IOException cause) { super(detail, cause); }
    }

    static final class PublicationUnavailable extends RuntimeException {
        PublicationUnavailable(Throwable cause) { super("publication outcome unknown", cause); }
    }

    @Override public void close() { factory.destroy(); }
}
