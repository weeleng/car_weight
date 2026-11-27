package lab.handoff;

import lab.handoff.Ingest.Authority;
import lab.handoff.Ingest.Identity;
import lab.handoff.Ingest.Proof;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.function.LongSupplier;

final class Spool {
    enum Category { KEY_MEASUREMENT, ORDINARY_TELEMETRY, UNKNOWN }
    enum Acceptance { STORED, DUPLICATE, CONFLICT, ISOLATED, REJECTED }
    record Attempt(long row, long owner, String id, long version) { }
    record Row(long row, Identity identity, byte[] body, String hash, String status, long version) { }

    private final Path path;
    private final long generation;
    private final Owner owner;

    Spool(Path path, Owner owner) throws Exception {
        owner.require(path);
        this.path=path;
        this.owner=owner;
        generation=Db.tx(path,db -> {
            Db.exec(db,"UPDATE lab_owner SET generation=generation+1 WHERE singleton=1");
            Db.exec(db,"""
                    UPDATE key_event_spool SET status='RETRY_WAIT',lease_owner=NULL,lease_until=NULL,
                      version=version+1,next_retry=0,last_error='OWNER_CHANGED'
                    WHERE status IN ('PUBLISHING','RETRY_WAIT')
                    """);
            return Db.number(db,"SELECT generation FROM lab_owner");
        });
    }

    private <T> T write(Db.Work<T> work) throws SQLException {
        try (var ticket=owner.admit()) {
            return Db.tx(path,db -> {
                if (generation!=Db.number(db,"SELECT generation FROM lab_owner")) throw new SQLException("stale owner");
                return work.run(db);
            });
        } catch (java.io.IOException e) {
            throw new SQLException("owner unavailable",e);
        }
    }

    Acceptance receive(Identity id, String authorizedTenant, Category trusted, byte[] bytes, long at,
                       long keyLimit, long ordinaryLimit, long isolationLimit, LongSupplier freeSpace,
                       long reserve, Runnable cut) throws SQLException {
        if (!id.tenant().equals(authorizedTenant) || trusted==null) return Acceptance.REJECTED;
        String hash=Db.hash(bytes);
        return write(db -> {
            if (trusted==Category.KEY_MEASUREMENT) {
                Row prior=find(db,id.namespace(),id.event());
                if (prior!=null) {
                    if (!prior.identity().tenant().equals(authorizedTenant)) return Acceptance.REJECTED;
                    if (id.equals(prior.identity()) && hash.equals(prior.hash())) return Acceptance.DUPLICATE;
                    return preserve(db, prior.row(), id, hash, bytes, isolationLimit, freeSpace,reserve,
                            "IDENTITY_CONFLICT",Acceptance.CONFLICT);
                }
            }
            if (freeSpace.getAsLong()<reserve) return Acceptance.REJECTED;
            if (trusted==Category.UNKNOWN) {
                return preserve(db,null,id,hash,bytes,isolationLimit,freeSpace,reserve,"UNKNOWN",Acceptance.ISOLATED);
            }
            if (trusted==Category.ORDINARY_TELEMETRY) {
                if (bytes.length>ordinaryLimit-Db.number(db,"SELECT coalesce(sum(length(payload)),0) FROM lab_ordinary"))
                    return Acceptance.REJECTED;
                Db.exec(db,"INSERT INTO lab_ordinary(payload,received_at) VALUES (?,?)",bytes,at);
            } else {
                if (bytes.length>keyLimit-Db.number(db,"SELECT coalesce(sum(length(payload)),0) FROM key_event_spool"))
                    return Acceptance.REJECTED;
                Db.exec(db,"""
                        INSERT INTO key_event_spool(producer_namespace,event_id,message_id,tenant_context,device_identity,
                          payload,payload_hash,category,received_at,persisted_at,status)
                        VALUES (?,?,?,?,?,?,?,'KEY_MEASUREMENT',?,?,'PENDING')
                        """,id.namespace(),id.event(),id.message(),id.tenant(),id.device(),bytes,hash,at,at);
            }
            cut.run();
            return Acceptance.STORED;
        });
    }

    private static Acceptance preserve(Connection db, Long row, Identity id, String hash, byte[] bytes, long limit,
                                       LongSupplier free, long reserve, String reason, Acceptance result) throws SQLException {
        String source=Db.hash(id.toString()+":"+hash+":"+reason);
        if (Db.number(db,"SELECT count(*) FROM key_spool_quarantine WHERE source_volume=? AND source_row=?",
                id.namespace(),source)>0) return result;
        if (free.getAsLong()<reserve || bytes.length>limit-Db.number(db,
                "SELECT coalesce(sum(length(payload)),0) FROM key_spool_quarantine")) return Acceptance.REJECTED;
        Db.exec(db,"""
                INSERT INTO key_spool_quarantine(original_record_id,source_volume,source_row,payload,hash,reason)
                VALUES (?,?,?,?,?,?)
                """,row,id.namespace(),source,bytes,hash,reason);
        return result;
    }

    Attempt claim(long row, long elapsed, long lease, int budget) throws SQLException {
        return write(db -> {
            Db.exec(db,"""
                    UPDATE key_event_spool SET status=CASE WHEN attempt_count>=? THEN 'QUARANTINED' ELSE 'RETRY_WAIT' END,
                      lease_owner=NULL,lease_until=NULL,next_retry=?,last_error='LEASE_EXPIRED',version=version+1
                    WHERE local_record_id=? AND status='PUBLISHING' AND lease_until<=?
                    """,budget,elapsed,row,elapsed);
            String id=UUID.randomUUID().toString();
            int updates=Db.exec(db,"""
                    UPDATE key_event_spool SET status='PUBLISHING',attempt_id=?,lease_owner=?,lease_until=?,
                      attempt_count=attempt_count+1,version=version+1
                    WHERE local_record_id=? AND (status='PENDING' OR (status='RETRY_WAIT' AND next_retry<=?))
                      AND attempt_count<?
                    """,id,generation,Math.addExact(elapsed,lease),row,elapsed,budget);
            return updates==0 ? null : new Attempt(row,generation,id,
                    Db.number(db,"SELECT version FROM key_event_spool WHERE local_record_id=?",row));
        });
    }

    boolean result(Attempt attempt,long elapsed,long wall, String outcome,long retryAt,int budget) throws SQLException {
        String status=switch (outcome) {
            case "ACK" -> "BROKER_CONFIRMED";
            case "PERMANENT","BAD_PAYLOAD" -> "QUARANTINED";
            default -> "RETRY_WAIT";
        };
        return write(db -> Db.exec(db,"""
                UPDATE key_event_spool SET status=CASE WHEN ?='RETRY_WAIT' AND attempt_count>=?
                    THEN 'QUARANTINED' ELSE ? END,lease_owner=NULL,lease_until=NULL,next_retry=?,last_error=?,
                    broker_confirmed_at=CASE WHEN ?='ACK' THEN ? ELSE broker_confirmed_at END,version=version+1
                WHERE local_record_id=? AND status='PUBLISHING' AND attempt_id=? AND
                   lease_owner=? AND lease_owner=? AND version=? AND lease_until>?
                """,status,budget,status,retryAt,"ACK".equals(outcome)?null:outcome,outcome,wall,
                attempt.row(),attempt.id(),attempt.owner(),generation,attempt.version(),elapsed)==1);
    }

    void waitIngest(long row) throws SQLException {
        write(db -> Db.exec(db,"UPDATE key_event_spool SET status='WAITING_INGEST',version=version+1 "
                +"WHERE local_record_id=? AND status='BROKER_CONFIRMED'",row));
    }

    // Reconcile committed authority outside the local transaction; notification is a hint from a separate channel.
    boolean handoff(Identity identity, Proof notification, Authority authority, long observedAt, Runnable cut) throws SQLException {
        try (var ticket=owner.admit()) {
            return reconcile(identity,notification,authority,observedAt,cut);
        } catch (java.io.IOException e) {
            throw new SQLException("owner unavailable",e);
        }
    }

    private boolean reconcile(Identity identity, Proof notification, Authority authority, long observedAt, Runnable cut)
            throws SQLException {
        Proof proof=authority.lookup(identity);
        if (proof==null || !proof.identity().equals(identity) || !Ingest.ISSUER.equals(proof.issuer())
                || !Ingest.INTENT.equals(proof.intent()) || proof.version()!=1
                || (notification!=null && !notification.equals(proof))) return false;
        return write(db -> {
            Row row=find(db,identity.namespace(),identity.event());
            if (row==null || !row.identity().equals(identity) || !row.hash().equals(proof.hash())) return false;
            if ("HANDOFF_COMPLETE".equals(row.status())) return evidence(db,row.row()).equals(proof);
            if ("QUARANTINED".equals(row.status())) return false;
            Db.exec(db,"""
                    INSERT INTO key_handoff_evidence(local_record_id,issuer,proof_id,tenant,device,namespace,event_id,
                      message_id,hash,intent,raw_ref,outbox_ref,proof_version,content_digest,observed_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """,row.row(),proof.issuer(),proof.id(),identity.tenant(),identity.device(),identity.namespace(),
                    identity.event(),identity.message(),proof.hash(),proof.intent(),proof.rawRef(),proof.outboxRef(),
                    proof.version(),proof.digest(),observedAt);
            long ref=Db.number(db,"SELECT evidence_id FROM key_handoff_evidence WHERE local_record_id=?",row.row());
            if (Db.exec(db,"""
                    UPDATE key_event_spool SET status='HANDOFF_COMPLETE',lease_owner=NULL,lease_until=NULL,
                      handoff_evidence=?,handoff_completed_at=?,version=version+1
                    WHERE local_record_id=? AND version=?
                    """,ref,observedAt,row.row(),row.version())!=1) throw new SQLException("handoff CAS lost");
            cut.run();
            return true;
        });
    }

    boolean cleanup(Identity id,boolean archive,Runnable cut) throws SQLException {
        return write(db -> {
            Row row=find(db,id.namespace(),id.event());
            if (row==null || !row.identity().equals(id) || !"HANDOFF_COMPLETE".equals(row.status()) || row.body()==null) return false;
            Proof evidence=evidence(db,row.row());
            if (!evidence.identity().equals(id) || !evidence.hash().equals(row.hash())
                    || !row.hash().equals(Db.hash(row.body()))) throw new SQLException("cleanup evidence mismatch");
            Db.exec(db,"INSERT INTO lab_cleanup VALUES (?,?,?,?)",row.row(),archive?"ARCHIVE":"CLEAN",
                    archive?row.body():null,row.hash());
            cut.run();
            return Db.exec(db,"UPDATE key_event_spool SET payload=NULL,version=version+1 WHERE local_record_id=? AND version=?",
                    row.row(),row.version())==1;
        });
    }

    int expireOrdinary(long cutoff) throws SQLException {
        return write(db -> Db.exec(db,"DELETE FROM lab_ordinary WHERE received_at<?",cutoff));
    }

    Row find(Identity id) throws SQLException {
        try (var ticket=owner.admit(); var db=Db.open(path,true)) {
            return find(db,id.namespace(),id.event());
        } catch (java.io.IOException e) {
            throw new SQLException("owner unavailable",e);
        }
    }

    private static Row find(Connection db,String namespace,String event) throws SQLException {
        List<Row> result=Db.rows(db,"SELECT * FROM key_event_spool WHERE producer_namespace=? AND event_id=?",row ->
                new Row(row.getLong("local_record_id"),new Identity(row.getString("producer_namespace"),row.getString("event_id"),
                        row.getString("message_id"),row.getString("tenant_context"),row.getString("device_identity")),
                        row.getBytes("payload"),row.getString("payload_hash"),row.getString("status"),row.getLong("version")),
                namespace,event);
        return result.isEmpty()?null:result.get(0);
    }

    private static Proof evidence(Connection db,long row) throws SQLException {
        return Db.rows(db,"""
                SELECT e.* FROM key_handoff_evidence e JOIN key_event_spool k ON k.handoff_evidence=e.evidence_id
                WHERE k.local_record_id=? AND e.local_record_id=k.local_record_id
                """,rs -> {
            var proof=new Proof(rs.getString("proof_id"),rs.getString("issuer"),new Identity(rs.getString("namespace"),
                    rs.getString("event_id"),rs.getString("message_id"),rs.getString("tenant"),rs.getString("device")),
                    rs.getString("hash"),rs.getString("intent"),rs.getString("raw_ref"),rs.getString("outbox_ref"),
                    rs.getInt("proof_version"));
            if (!proof.digest().equals(rs.getString("content_digest"))) throw new SQLException("evidence integrity mismatch");
            return proof;
        },row).get(0);
    }
}
