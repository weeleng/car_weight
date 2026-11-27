package cn.iocoder.sanitation.messaging;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.GetResponse;
import java.io.IOException;
import java.util.Map;
import java.util.Set;

final class Consumer {
    record Scope(String tenant, String project, String service, String issuingUser) {}
    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();
    private final Store store;
    private final Map<String, Scope> work;
    private final Set<String> revokedUsers;

    Consumer(Store store, Map<String, Scope> work, Set<String> revokedUsers) {
        this.store = store;
        this.work = Map.copyOf(work);
        this.revokedUsers = revokedUsers;
    }

    static Scope current() { return CURRENT.get(); }

    Store.T2 handle(Channel channel, GetResponse delivery, Store.Fault fault, Runnable beforeAck) throws IOException {
        byte[] bytes = delivery.getBody();
        Store.T2 result;
        try {
            authorize(bytes);
            result = store.consume(Protocol.CONSUMER, bytes, fault);
        } finally {
            CURRENT.remove();
        }
        beforeAck.run();
        channel.basicAck(delivery.getEnvelope().getDeliveryTag(), false);
        return result;
    }

    private void authorize(byte[] bytes) {
        var envelope = Protocol.decode(bytes);
        Scope scope = work.get(envelope.eventId());
        if (scope == null || !scope.tenant().equals(envelope.tenant()) || !scope.project().equals(envelope.project())
                    || !"scale-service".equals(scope.service()) || !"fixture".equals(envelope.producer())
                || (scope.issuingUser() != null && revokedUsers.contains(scope.issuingUser()))) {
            throw new Protocol.Rejected("AUTHORIZATION");
        }
        // Broker headers never establish a scope.
        CURRENT.set(scope);
    }

    Store.T2 recoverHeld(String digest, String requester, String approver, String reason) {
        Protocol.authorizeReplay(Protocol.CONSUMER, requester, approver, reason);
        byte[] body = store.held(digest);
        try {
            authorize(body);
            var result = store.consume(Protocol.CONSUMER, body, Store.Fault.NONE);
            store.recoverFailure(body);
            return result;
        } finally {
            CURRENT.remove();
        }
    }

    void quarantine(Channel channel, GetResponse delivery, String reason) throws IOException {
        String id = delivery.getProps().getMessageId();
        store.holdFailure(id == null ? "unknown" : id, delivery.getBody(), reason);
        // This is a failure disposition after a verified local handoff, not a T2 success ACK.
        channel.basicReject(delivery.getEnvelope().getDeliveryTag(), false);
    }
}
