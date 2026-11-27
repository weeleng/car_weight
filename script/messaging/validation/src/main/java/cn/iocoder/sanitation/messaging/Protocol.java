package cn.iocoder.sanitation.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

record Protocol(String producer, String messageId, String eventId, String businessKey,
                int schemaVersion, String tenant, String project, String payload) {
    private static final ObjectMapper JSON = new ObjectMapper();
    static final String CONSUMER = "scale-fixture";

    byte[] bytes() {
        try {
            return JSON.writeValueAsBytes(this);
        } catch (IOException e) {
            throw new IllegalArgumentException("invalid fixture event", e);
        }
    }

    static Protocol decode(byte[] bytes) {
        try {
            Protocol envelope = JSON.readValue(bytes, Protocol.class);
            if (envelope.schemaVersion() != 1) throw new Rejected("VERSION");
            for (String id : new String[] {envelope.producer(), envelope.messageId(), envelope.eventId(),
                    envelope.businessKey(), envelope.tenant(), envelope.project()}) {
                if (id == null || !id.matches("[a-zA-Z0-9_-]{1,40}")) throw new Rejected("FORMAT");
            }
            return envelope;
        } catch (IOException e) {
            throw new Rejected("FORMAT");
        }
    }

    static String digest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static void authorizeReplay(String consumer, String requester, String approver, String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 120 || !"operator".equals(requester)
                || !"reviewer".equals(approver) || !CONSUMER.equals(consumer)) {
            throw new Rejected("REPLAY_AUTHORIZATION");
        }
    }

    static final class Rejected extends IllegalArgumentException {
        Rejected(String reason) { super(reason); }
    }
}
