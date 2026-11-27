package cn.iocoder.sanitation.messaging;

/** Child JVM that intentionally exits after T2 commit and before basicAck. */
public class CrashConsumer {
    public static void main(String[] args) throws Exception {
        if (args.length == 1 && "relay".equals(args[0])) {
            var claim = Relay.readCommitted(new Database("ingest"), "crash-relay").claim();
            if (claim == null) throw new IllegalStateException("missing crash fixture claim");
            System.out.println("Claim committed; halting before publish");
            System.out.flush();
            Runtime.getRuntime().halt(38);
        }
        var store = new Store(new Database("ingest"), new Database("business"));
        var event = LabFixture.event("crash");
        try (var connection = Broker.connect(); var channel = connection.createChannel()) {
            var delivery = LabFixture.delivery(channel, Broker.CLASSIC);
            if (!event.messageId().equals(delivery.getProps().getMessageId())) {
                throw new IllegalStateException("unexpected crash fixture delivery");
            }
            LabFixture.consumer(store, event).handle(channel, delivery, Store.Fault.NONE, () -> {
                System.out.println("T2 committed; halting before ACK");
                System.out.flush();
                Runtime.getRuntime().halt(37);
            });
        }
        throw new IllegalStateException("fault injection not reached");
    }
}
