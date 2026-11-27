package cn.iocoder.sanitation.messaging;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;

final class Recovery {
    record Difference(String reason, String event, String message, String consumer, String fact) {}
    private final Database ingest;
    private final Database business;
    private final Database track;

    Recovery(Database ingest, Database business, Database track) {
        this.ingest = ingest;
        this.business = business;
        this.track = track;
    }

    boolean track(String message, String vehicle, long time, boolean fail) {
        try {
            return track.commit(status -> {
                track.sql.update("INSERT INTO track_inbox VALUES(?)", message);
                track.sql.update("INSERT INTO position_fact VALUES(?,?,?)", message, vehicle, time);
                if (fail) throw new Store.Injected();
                track.sql.update("""
                        INSERT INTO latest_position VALUES(?,?) AS incoming
                        ON DUPLICATE KEY UPDATE occurred=GREATEST(latest_position.occurred,incoming.occurred)
                        """, vehicle, time);
                return true;
            });
        } catch (DuplicateKeyException duplicate) {
            int present = track.sql.queryForObject(
                    "SELECT COUNT(*) FROM position_fact WHERE message_id=? AND vehicle_id=? AND occurred=?",
                    Integer.class, message, vehicle, time);
            if (present != 1) throw new Protocol.Rejected("TRACK_CONFLICT");
            return false;
        }
    }

    List<Difference> reconcile(java.util.Set<String> brokerView, java.util.Set<String> priorAppliedMessages) {
        var differences = new ArrayList<Difference>();
        var raw = ingest.sql.queryForList("SELECT event_id,device_observed_ack FROM raw_event");
        var outbox = by(ingest.sql.queryForList("SELECT * FROM outbox"), "message_id");
        var inbox = business.sql.queryForList("SELECT * FROM inbox");
        var facts = by(business.sql.queryForList("SELECT * FROM fact"), "business_key");
        var followups = by(business.sql.queryForList("SELECT * FROM business_outbox"), "business_key");
        var rawIds = by(raw, "event_id");

        for (var event : raw) {
            if (Boolean.FALSE.equals(event.get("device_observed_ack"))) {
                differences.add(new Difference("DEVICE_ACK_UNOBSERVED", value(event, "event_id"), "", "", ""));
            }
            if (outbox.values().stream().noneMatch(row -> value(row, "event_id").equals(value(event, "event_id")))) {
                differences.add(new Difference("MISSING_INTENT", value(event, "event_id"), "", "", ""));
            }
        }
        for (var entry : outbox.entrySet()) {
            var row = entry.getValue();
            String event = value(row, "event_id");
            String fact = Protocol.decode((byte[]) row.get("payload")).businessKey();
            boolean published = "PUBLISHED".equals(value(row, "status"));
            boolean delivered = inbox.stream().anyMatch(receipt -> entry.getKey().equals(value(receipt, "message_id"))
                    && "fixture".equals(value(receipt, "producer"))
                    && Protocol.CONSUMER.equals(value(receipt, "consumer_id")));
            if (published && !delivered) differences.add(new Difference("UNCONSUMED", event, entry.getKey(), Protocol.CONSUMER, fact));
            if (published && !delivered && !brokerView.contains(entry.getKey())) {
                differences.add(new Difference("BROKER_EARLIER", event, entry.getKey(), Protocol.CONSUMER, fact));
            }
            if (!delivered && priorAppliedMessages.contains(entry.getKey())) {
                differences.add(new Difference("BUSINESS_EARLIER", event, entry.getKey(), Protocol.CONSUMER, fact));
            }
            if (!published && brokerView.contains(entry.getKey())) {
                differences.add(new Difference("BROKER_LATER", event, entry.getKey(), Protocol.CONSUMER, fact));
            }
        }
        for (var receipt : inbox) {
            String message = value(receipt, "message_id"), event = value(receipt, "event_id");
            String consumer = value(receipt, "consumer_id"), fact = value(receipt, "business_key");
            if (!rawIds.containsKey(event)) differences.add(new Difference("INGEST_EARLIER", event, message, consumer, fact));
            if (!facts.containsKey(fact)) differences.add(new Difference("MISSING_FACT", event, message, consumer, fact));
            else if (!followups.containsKey(fact)) differences.add(new Difference("MISSING_FOLLOWUP", event, message, consumer, fact));
            var intent = outbox.get(message);
            if (intent != null && !value(intent, "digest").equals(value(receipt, "digest"))) {
                differences.add(new Difference("DIGEST_MISMATCH", event, message, consumer, fact));
            }
        }
        return List.copyOf(differences);
    }

    private static Map<String, Map<String, Object>> by(List<Map<String, Object>> rows, String key) {
        var result = new HashMap<String, Map<String, Object>>();
        rows.forEach(row -> result.put(value(row, key), row));
        return result;
    }

    private static String value(Map<String, Object> row, String key) {
        return String.valueOf(row.get(key));
    }
}
