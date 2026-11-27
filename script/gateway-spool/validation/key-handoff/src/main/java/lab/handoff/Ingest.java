package lab.handoff;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

final class Ingest {
    static final String ISSUER = "isolated-ingest";
    static final String INTENT = "synthetic-intent-v1";

    record Identity(String namespace, String event, String message, String tenant, String device) { }

    record Proof(String id, String issuer, Identity identity, String hash, String intent, String rawRef,
                 String outboxRef, int version) {
        String digest() {
            return Db.hash(java.util.stream.Stream.of(id,issuer,identity.namespace(),identity.event(),
                    identity.message(),identity.tenant(),identity.device(),hash,intent,rawRef,outboxRef,
                    Integer.toString(version)).map(x -> x.length()+":"+x).reduce("",String::concat));
        }
    }

    @FunctionalInterface interface Authority { Proof lookup(Identity identity) throws SQLException; }

    private final Path path;

    Ingest(Path path) throws SQLException {
        this.path=path;
        try (var db=Db.open(path,false)) {
            Db.exec(db,"CREATE TABLE IF NOT EXISTS raw(ns TEXT,event TEXT,message TEXT,tenant TEXT,device TEXT,hash TEXT,"
                    +"body BLOB,raw_ref TEXT UNIQUE,PRIMARY KEY(ns,event))");
            Db.exec(db,"CREATE TABLE IF NOT EXISTS outbox(raw_ref TEXT PRIMARY KEY REFERENCES raw(raw_ref),"
                    +"ns TEXT,event TEXT,message TEXT,hash TEXT,intent TEXT,proof_id TEXT UNIQUE,outbox_ref TEXT UNIQUE)");
        }
    }

    Proof commit(Identity id, byte[] payload, Runnable beforeCommit) throws SQLException {
        return Db.tx(path, db -> {
            String hash=Db.hash(payload);
            Proof prior=read(db,id);
            if (prior!=null) {
                if (!prior.hash().equals(hash)) throw new SQLException("identity conflict");
                return prior;
            }
            String raw=UUID.randomUUID().toString();
            String outbox=UUID.randomUUID().toString();
            String proof=UUID.randomUUID().toString();
            Db.exec(db,"INSERT INTO raw VALUES (?,?,?,?,?,?,?,?)",id.namespace(),id.event(),id.message(),id.tenant(),
                    id.device(),hash,payload,raw);
            Db.exec(db,"INSERT INTO outbox VALUES (?,?,?,?,?,?,?,?)",raw,id.namespace(),id.event(),id.message(),hash,INTENT,proof,outbox);
            beforeCommit.run();
            return new Proof(proof,ISSUER,id,hash,INTENT,raw,outbox,1);
        });
    }

    Proof lookup(Identity id) throws SQLException {
        try (var db=Db.open(path,true)) {
            return read(db,id);
        }
    }

    private static Proof read(Connection db, Identity match) throws SQLException {
        var rows=Db.rows(db,"""
                SELECT raw.*,outbox.ns AS ons,outbox.event AS oevent,outbox.message AS omessage,
                  outbox.hash AS ohash,outbox.intent,outbox.proof_id,outbox.outbox_ref
                FROM raw LEFT JOIN outbox ON raw.raw_ref=outbox.raw_ref WHERE raw.ns=? AND raw.event=?
                """,row -> {
            Identity id=new Identity(row.getString("ns"),row.getString("event"),row.getString("message"),
                    row.getString("tenant"),row.getString("device"));
            if (!id.equals(match) || !id.namespace().equals(row.getString("ons"))
                    || !id.event().equals(row.getString("oevent")) || !id.message().equals(row.getString("omessage"))
                    || !row.getString("hash").equals(row.getString("ohash"))
                    || !row.getString("hash").equals(Db.hash(row.getBytes("body")))
                    || !INTENT.equals(row.getString("intent"))) {
                throw new SQLException("authority record or intent does not match");
            }
            return new Proof(row.getString("proof_id"),ISSUER,id,row.getString("hash"),INTENT,
                    row.getString("raw_ref"),row.getString("outbox_ref"),1);
        },match.namespace(),match.event());
        return rows.isEmpty() ? null : rows.get(0);
    }
}
