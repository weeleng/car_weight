package cn.iocoder.sanitation.messaging;

import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

final class Relay {
    private static final int MAX_ATTEMPTS = 3;
    private final Database database;
    private final String owner;
    private final int isolation;

    Relay(Database database, String owner, int isolation) {
        this.database = database;
        this.owner = owner;
        this.isolation = isolation;
    }

    record Claim(String id, byte[] bytes, int topology, String attempt, long version, int attempts) {}

    Claim claim() {
        return database.commit(isolation, status -> {
            // Two index paths keep expired leases and new work independently eligible.
            var expired = database.sql.query("""
                    SELECT message_id,payload,topology_version,version,attempts FROM outbox
                    WHERE status='PUBLISHING' AND lease_until<=UTC_TIMESTAMP(3)
                    ORDER BY lease_until,message_id LIMIT 1 FOR UPDATE SKIP LOCKED
                    """, Relay::claimRow);
            if (!expired.isEmpty() && expired.get(0).attempts() > MAX_ATTEMPTS) {
                database.sql.update("""
                        UPDATE outbox SET status='DEAD',version=version+1,last_error='LEASE_EXHAUSTED',lease_until=NULL
                        WHERE message_id=?
                        """, expired.get(0).id());
                return null;
            }
            var ready = expired.isEmpty() ? database.sql.query("""
                    SELECT message_id,payload,topology_version,version,attempts FROM outbox
                    WHERE status IN ('PENDING','RETRY_WAIT') AND next_retry_at<=UTC_TIMESTAMP(3)
                    ORDER BY next_retry_at,message_id LIMIT 1 FOR UPDATE SKIP LOCKED
                    """, Relay::claimRow) : expired;
            if (ready.isEmpty()) return null;
            Claim claim = ready.get(0);
            database.sql.update("""
                    UPDATE outbox SET status='PUBLISHING',version=?,attempts=?,attempt_id=?,owner=?,
                        lease_until=UTC_TIMESTAMP(3)+INTERVAL 8 SECOND WHERE message_id=?
                    """, claim.version(), claim.attempts(), claim.attempt(), owner, claim.id());
            return claim;
        });
    }

    private static Claim claimRow(java.sql.ResultSet row, int number) throws java.sql.SQLException {
        return new Claim(row.getString(1), row.getBytes(2), row.getInt(3), UUID.randomUUID().toString(),
                row.getLong(4) + 1, row.getInt(5) + 1);
    }

    boolean publish(Claim claim, Broker broker, Runnable afterConfirm) throws Exception {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("network operation inside a transaction");
        }
        Broker.Confirmation confirmation;
        try {
            if (!topologyAllows(claim, broker)) return false;
            confirmation = broker.publish(Broker.EXCHANGE, Broker.ROUTE, claim.id(), claim.attempt(), claim.bytes());
        } catch (Broker.PublicationUnavailable error) {
            finish(claim, false, "TRANSPORT_UNKNOWN");
            return false;
        }
        if (!confirmation.accepted()) {
            finish(claim, false, confirmation.returned() ? "RETURNED" : "UNCONFIRMED");
            return false;
        }
        afterConfirm.run();
        return topologyAllows(claim, broker) && finish(claim, true, null);
    }

    private boolean topologyAllows(Claim claim, Broker broker) throws InterruptedException {
        boolean matches;
        try {
            matches = broker.topology(claim.topology());
        } catch (Broker.TopologyUnavailable error) {
            finish(claim, false, "TOPOLOGY_UNKNOWN");
            return false;
        }
        if (!matches) finish(claim, false, "TOPOLOGY");
        return matches;
    }

    boolean finish(Claim claim, boolean accepted, String reason) {
        try {
            return recordOutcome(claim, accepted, reason);
        } catch (CannotCreateTransactionException | DataAccessResourceFailureException error) {
            // Recording a second outcome here cannot prove whether the first update committed.
            throw new UpdateUnknown(claim, accepted, reason, error);
        }
    }

    private boolean recordOutcome(Claim claim, boolean accepted, String reason) {
        return database.commit(status -> {
            String next = accepted ? "PUBLISHED" : claim.attempts() >= MAX_ATTEMPTS ? "DEAD" : "RETRY_WAIT";
            int delay = Math.min(8, 1 << Math.min(3, claim.attempts()));
            int changed = database.sql.update("""
                    UPDATE outbox SET status=?,version=version+1,last_error=?,lease_until=NULL,
                        next_retry_at=TIMESTAMPADD(SECOND,?,UTC_TIMESTAMP(3))
                    WHERE message_id=? AND attempt_id=? AND version=? AND owner=? AND status='PUBLISHING' AND lease_until>UTC_TIMESTAMP(3)
                    """, next, accepted ? null : reason, delay, claim.id(), claim.attempt(), claim.version(), owner);
            if (changed == 1) {
                database.sql.update("UPDATE replay_audit SET result=?,reconciliation=? WHERE attempt_id=?",
                        next, accepted ? "AWAITING_TARGET" : "REVIEW_REQUIRED", claim.attempt());
            }
            return changed == 1;
        });
    }

    static final class UpdateUnknown extends IllegalStateException {
        final String messageId;
        final String attemptId;
        final boolean brokerAndTopologyAccepted;
        final String requestedFailure;

        UpdateUnknown(Claim claim, boolean accepted, String reason, RuntimeException cause) {
            super("DB_UPDATE_UNKNOWN", cause);
            messageId = claim.id();
            attemptId = claim.attempt();
            brokerAndTopologyAccepted = accepted;
            requestedFailure = reason;
        }
    }

    String replay(String message, String consumer, String requester, String approver, String reason) {
        Protocol.authorizeReplay(consumer, requester, approver, reason);
        String attempt = UUID.randomUUID().toString();
        return database.commit(status -> {
            int updated = database.sql.update("""
                    UPDATE outbox SET status='PUBLISHING',version=version+1,attempts=attempts+1,
                      attempt_id=?,owner=?,lease_until=UTC_TIMESTAMP(3)+INTERVAL 8 SECOND
                    WHERE message_id=? AND status='DEAD'
                    """, attempt, owner, message);
            if (updated != 1) throw new Protocol.Rejected("REPLAY_STATE");
            database.sql.update("""
                    INSERT INTO replay_audit(attempt_id,message_id,event_id,digest,payload,consumer_id,
                      requester,approver,reason,result,reconciliation)
                    SELECT attempt_id,message_id,event_id,digest,payload,?,?,? ,?,'REQUESTED','UNVERIFIED'
                    FROM outbox WHERE message_id=?
                    """, consumer, requester, approver, reason, message);
            return attempt;
        });
    }

    Claim replayClaim(String message, String attempt) {
        List<Claim> claims = database.sql.query("""
                SELECT message_id,payload,topology_version,attempt_id,version,attempts FROM outbox
                WHERE message_id=? AND attempt_id=? AND status='PUBLISHING' AND owner=?
                """, (row, n) -> new Claim(row.getString(1), row.getBytes(2), row.getInt(3),
                        row.getString(4), row.getLong(5), row.getInt(6)), message, attempt, owner);
        if (claims.size() != 1) throw new Protocol.Rejected("REPLAY_STATE");
        return claims.get(0);
    }

    static Relay readCommitted(Database database, String owner) {
        return new Relay(database, owner, TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
}
