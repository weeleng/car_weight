package cn.iocoder.sanitation.messaging;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.datasource.DataSourceUtils;
import static cn.iocoder.sanitation.messaging.Protocol.digest;

final class Store {
    enum T1 { COMMITTED, DUPLICATE, CONFLICT, MISSING_INTENT, UNKNOWN }
    enum T2 { COMMITTED, DUPLICATE, EXISTING_FACT }
    enum Fault { NONE, APPLICATION, OUTBOX, ROLLBACK_ONLY, COMMIT_FAILURE, UNKNOWN_AFTER_COMMIT }
    final Database ingest;
    final Database business;

    Store(Database ingest, Database business) {
        this.ingest = ingest;
        this.business = business;
    }

    T1 receive(Protocol event, Fault fault, Runnable beforeCommit) {
        byte[] raw = event.payload().getBytes(StandardCharsets.UTF_8);
        byte[] message = event.bytes();
        boolean matchesIntent = event.matchesSingleIntent(event.eventId(), Protocol.expectedMessageId(event.eventId()));
        try {
            ingest.commit(status -> {
                ingest.sql.update("INSERT INTO raw_event(event_id,digest,payload) VALUES(?,?,?)",
                        event.eventId(), digest(raw), raw);
                if (!matchesIntent) throw new Protocol.Rejected("INTENT_CONTRACT");
                if (fault == Fault.APPLICATION) throw new Injected();
                ingest.sql.update("INSERT INTO outbox(message_id,event_id,digest,payload,topology_version) VALUES(?,?,?,?,?)",
                        event.messageId(), event.eventId(), digest(message), message,
                        fault == Fault.OUTBOX ? null : Protocol.TOPOLOGY_VERSION);
                beforeCommit.run();
                if (fault == Fault.ROLLBACK_ONLY) status.setRollbackOnly();
                if (fault == Fault.COMMIT_FAILURE) closeBoundConnection(ingest);
                return null;
            });
            return fault == Fault.UNKNOWN_AFTER_COMMIT ? T1.UNKNOWN : T1.COMMITTED;
        } catch (DuplicateKeyException duplicate) {
            T1 outcome = recover(event.eventId(), raw);
            return outcome == T1.DUPLICATE && !matchesIntent ? T1.MISSING_INTENT : outcome;
        }
    }

    T1 recover(String eventId, byte[] raw) {
        String expectedMessage = Protocol.expectedMessageId(eventId);
        return ingest.commit(status -> {
            var result = ingest.sql.query("""
                    SELECT r.event_id AS raw_id,r.digest,r.payload,o.event_id AS intent_event,
                        o.message_id,o.topology_version,o.payload AS outbound,o.digest AS outbound_digest
                    FROM raw_event r LEFT JOIN outbox o ON (o.event_id=r.event_id OR o.message_id=?)
                    WHERE r.event_id=?
                    """, (row, n) -> {
                if (!eventId.equals(row.getString("raw_id")) || !digest(raw).equals(row.getString("digest"))
                        || !Arrays.equals(raw, row.getBytes("payload"))) {
                    return T1.CONFLICT;
                }
                byte[] outbound = row.getBytes("outbound");
                boolean valid = expectedMessage != null && expectedMessage.equals(row.getString("message_id"))
                        && eventId.equals(row.getString("intent_event"))
                        && row.getInt("topology_version") == Protocol.TOPOLOGY_VERSION
                        && outbound != null && digest(outbound).equals(row.getString("outbound_digest"))
                        && matchesPersistedIntent(outbound, eventId, expectedMessage, raw);
                return valid ? T1.DUPLICATE : T1.MISSING_INTENT;
            }, expectedMessage, eventId);
            T1 outcome = result.isEmpty() ? T1.UNKNOWN : result.get(0);
            if (result.size() > 1 && outcome != T1.CONFLICT) outcome = T1.MISSING_INTENT;
            if (outcome == T1.CONFLICT) {
                ingest.sql.update("""
                        INSERT INTO raw_conflict(event_id,received_digest,received_payload) VALUES(?,?,?)
                        ON DUPLICATE KEY UPDATE event_id=event_id
                        """, eventId, digest(raw), raw);
            }
            return outcome;
        });
    }

    private static boolean matchesPersistedIntent(byte[] outbound, String eventId, String messageId, byte[] raw) {
        try {
            Protocol envelope = Protocol.decode(outbound);
            return envelope.matchesSingleIntent(eventId, messageId) && envelope.payload() != null
                    && Arrays.equals(raw, envelope.payload().getBytes(StandardCharsets.UTF_8));
        } catch (Protocol.Rejected invalid) {
            return false;
        }
    }

    static boolean l2Eligible(T1 result) { return result == T1.COMMITTED || result == T1.DUPLICATE; }

    T2 consume(String consumer, byte[] bytes, Fault fault) {
        Protocol item = Protocol.decode(bytes);
        T2 result;
        try {
            result = business.commit(status -> {
                try {
                    business.sql.update("INSERT INTO inbox VALUES(?,?,?,?,?,?)", consumer, item.producer(),
                            item.messageId(), item.eventId(), digest(bytes), item.businessKey());
                } catch (DuplicateKeyException duplicate) {
                    throw new ExistingInbox();
                }
                if (fault == Fault.APPLICATION) throw new Injected();
                boolean inserted = true;
                try {
                    business.sql.update("INSERT INTO fact VALUES(?,?)", item.businessKey(), item.eventId());
                } catch (DuplicateKeyException existing) {
                    // The fixture regards the same event/key as the same business fact.
                    verifyFact(item);
                    inserted = false;
                }
                if (inserted) {
                    business.sql.update("INSERT INTO business_outbox VALUES(?)",
                            fault == Fault.OUTBOX ? null : item.businessKey());
                }
                if (fault == Fault.ROLLBACK_ONLY) status.setRollbackOnly();
                if (fault == Fault.COMMIT_FAILURE) closeBoundConnection(business);
                return inserted ? T2.COMMITTED : T2.EXISTING_FACT;
            });
        } catch (ExistingInbox duplicate) {
            result = business.commit(status -> {
                String previous = business.sql.queryForObject(
                        "SELECT digest FROM inbox WHERE consumer_id=? AND producer=? AND message_id=?",
                        String.class, consumer, item.producer(), item.messageId());
                if (!digest(bytes).equals(previous)) throw new Protocol.Rejected("IDENTITY_CONFLICT");
                verifyFact(item);
                return T2.DUPLICATE;
            });
        }
        if (fault == Fault.UNKNOWN_AFTER_COMMIT) throw new CommitUnknown();
        return result;
    }

    private void verifyFact(Protocol item) {
        int complete = business.sql.queryForObject("""
                SELECT COUNT(*) FROM fact JOIN business_outbox USING(business_key)
                WHERE business_key=? AND event_id=?
                """, Integer.class, item.businessKey(), item.eventId());
        if (complete != 1) throw new Protocol.Rejected("FACT_CONFLICT");
    }

    void holdFailure(String message, byte[] body, String reason) {
        business.commit(status -> {
            business.sql.update("""
                    INSERT INTO failure(envelope_key,message_id,body,reason) VALUES(?,?,?,?)
                    ON DUPLICATE KEY UPDATE attempts=attempts+1,
                       state=IF(attempts>=3,'DEAD','HELD')
                    """, digest(body), message, body, reason);
            return null;
        });
    }

    byte[] held(String digest) {
        var stored = business.sql.query("""
                SELECT body FROM failure WHERE envelope_key=? AND state IN ('HELD','DEAD')
                """, (row, n) -> row.getBytes(1), digest);
        if (stored.size() != 1 || !Protocol.digest(stored.get(0)).equals(digest)) {
            throw new Protocol.Rejected("FAILURE_NOT_HELD");
        }
        return stored.get(0);
    }

    void recoverFailure(byte[] body) {
        business.commit(status -> {
            business.sql.update("UPDATE failure SET state='RECOVERED' WHERE envelope_key=?", digest(body));
            return null;
        });
    }

    private static void closeBoundConnection(Database database) {
        try {
            DataSourceUtils.getConnection(database.source).close();
        } catch (java.sql.SQLException error) {
            throw new IllegalStateException(error);
        }
    }

    static final class Injected extends IllegalStateException {}
    static final class ExistingInbox extends IllegalStateException {}
    static final class CommitUnknown extends IllegalStateException {}
}
