package labprobe;

import cn.iocoder.yudao.module.iot.core.mq.message.IotDeviceMessage;
import cn.iocoder.yudao.module.iot.gateway.service.device.spool.IotDeviceMessageSpoolService;
import com.rabbitmq.client.ConnectionFactory;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.DataInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;

public final class SpoolRabbitFixture {
    private static final String TOPIC = IotDeviceMessage.MESSAGE_BUS_DEVICE_MESSAGE_TOPIC;
    private static final String QUEUE = "eng002f-spool-fixture";

    static String[] gatewayArgs(String[] input) throws IOException {
        if (!Arrays.asList(input).contains("--lab-spool-relay=true")) {
            return input;
        }
        var args = new ArrayList<>(Arrays.asList(input));
        args.remove("--spring.rabbitmq.host=rabbit");
        try {
            var server = new ServerSocket(0, 16, InetAddress.getLoopbackAddress());
            daemon(() -> relay(server));
            args.add("--spring.rabbitmq.host=127.0.0.1");
            args.add("--spring.rabbitmq.port=" + server.getLocalPort());
        } catch (IOException e) {
            throw new IOException("failed to start fixture relay", e);
        }
        return args.toArray(String[]::new);
    }

    static void publish(ConfigurableApplicationContext context, String[] args) {
        for (String arg : args) {
            if (arg.startsWith("--lab-spool-message=")) {
                String id = arg.substring("--lab-spool-message=".length());
                var message = IotDeviceMessage.requestOf("thing.property.post", Map.of("fixture", 1));
                message.setId(id);
                message.setRequestId(id);
                message.setDeviceId(1L);
                message.setTenantId(1L);
                context.getBean(IotDeviceMessageSpoolService.class)
                        .sendSpoolableMessage(message, System.currentTimeMillis());
                System.out.println("F02B SENT_OR_STORED " + id);
            }
        }
    }

    public static void main(String[] args) throws Exception {
        var factory = new ConnectionFactory();
        factory.setHost("127.0.0.1");
        factory.setPort(Integer.parseInt(args[0]));
        factory.setVirtualHost("fixture");
        factory.setUsername(System.getenv("LAB_MQ_USER"));
        factory.setPassword(System.getenv("LAB_MQ_PASSWORD"));
        factory.setConnectionTimeout(2500);
        try (var connection = factory.newConnection(); var channel = connection.createChannel()) {
            channel.exchangeDeclare(TOPIC, "topic", true);
            switch (args[1]) {
                case "exchange" -> { }
                case "reset" -> channel.queueDelete(QUEUE);
                case "bind" -> {
                    channel.queueDeclare(QUEUE, true, false, false, Map.of());
                    channel.queueBind(QUEUE, TOPIC, "#");
                }
                case "limit" -> {
                    channel.queueDeclare(QUEUE, true, false, false,
                            Map.of("x-max-length", 1L, "x-overflow", "reject-publish"));
                    channel.queueBind(QUEUE, TOPIC, "#");
                    channel.confirmSelect();
                    channel.basicPublish(TOPIC, "#", true, null, "{}".getBytes(StandardCharsets.UTF_8));
                    if (!channel.waitForConfirms(5000)) throw new IllegalStateException("fixture filler rejected");
                }
                case "take" -> {
                    var delivery = channel.basicGet(QUEUE, true);
                    if (delivery != null) {
                        System.out.println(new String(delivery.getBody(), StandardCharsets.UTF_8));
                    }
                }
                case "count" -> System.out.println(channel.messageCount(QUEUE));
                default -> throw new IllegalArgumentException(args[1]);
            }
        }
    }

    private static void relay(ServerSocket server) {
        while (!server.isClosed()) {
            try {
                Socket client = server.accept();
                daemon(() -> {
                    try (client; var rabbit = new Socket("rabbit", 5672)) {
                        daemon(() -> {
                            try {
                                client.getInputStream().transferTo(rabbit.getOutputStream());
                            } catch (IOException ignored) {
                                // Closing either endpoint is an expected transport fault.
                            } finally {
                                close(rabbit);
                            }
                        });
                        var input = new DataInputStream(rabbit.getInputStream());
                        var output = client.getOutputStream();
                        while (!rabbit.isClosed()) {
                            byte[] header = input.readNBytes(7);
                            if (header.length != 7) return;
                            int length = java.nio.ByteBuffer.wrap(header, 3, 4).getInt();
                            if (length < 0 || length > 16 * 1024 * 1024) throw new IOException("unexpected fixture frame");
                            byte[] frame = input.readNBytes(length + 1);
                            if (frame.length != length + 1) return;
                            // AMQP method frame: basic.ack is class 60, method 80.
                            boolean ack = header[0] == 1 && length >= 4
                                    && frame[0] == 0 && frame[1] == 60 && frame[2] == 0 && frame[3] == 80;
                            if (ack && !Files.exists(Path.of("/data/allow-confirms"))) {
                                System.out.println("F02B INJECT dropped BASIC.ACK");
                                continue;
                            }
                            output.write(header);
                            output.write(frame);
                        }
                    } catch (IOException ignored) {
                        // The isolated runner records broker delivery and publisher timeouts.
                    }
                });
            } catch (IOException ignored) {
                return;
            }
        }
    }

    private static void close(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Test relay cleanup.
        }
    }

    private static void daemon(Runnable action) {
        Thread thread = new Thread(action, "spool-lab-relay");
        thread.setDaemon(true);
        thread.start();
    }
}
