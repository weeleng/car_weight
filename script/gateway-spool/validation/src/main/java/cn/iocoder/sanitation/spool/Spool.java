package cn.iocoder.sanitation.spool;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import java.util.function.LongSupplier;

final class Spool {
    enum Category { KEY_MEASUREMENT, ORDINARY_TELEMETRY, UNKNOWN }

    record Record(String id, byte[] body, String hash, String state, int attempts, long version) { }
    record Attempt(String id, String owner, String token, long version) { }

    private final Path path;

    Spool(Path path) throws SQLException {
        this.path = path;
        try (Connection db = Sqlite.open(path, false)) {
            Sqlite.exec(db, "PRAGMA journal_mode=WAL");
            Sqlite.exec(db, """
                    CREATE TABLE IF NOT EXISTS spool (
                        id TEXT PRIMARY KEY, body BLOB, hash TEXT NOT NULL, category TEXT NOT NULL,
                        created INTEGER NOT NULL, state TEXT NOT NULL, attempts INTEGER NOT NULL DEFAULT 0,
                        owner TEXT, token TEXT, lease_until INTEGER, version INTEGER NOT NULL DEFAULT 0,
                        reason TEXT, evidence TEXT)
                    """);
            Sqlite.exec(db, """
                    CREATE TABLE IF NOT EXISTS conflict (
                        id TEXT NOT NULL, hash TEXT NOT NULL, body BLOB NOT NULL, PRIMARY KEY(id,hash))
                    """);
        }
    }

    boolean append(String id, byte[] body, Category category, long now, long quota, long reserve,
                   LongSupplier usableSpace, Runnable beforeCommit) throws SQLException {
        String hash = Sqlite.hash(body);
        try (Connection db = Sqlite.open(path, false)) {
            Sqlite.exec(db, "BEGIN IMMEDIATE");
            Record current = find(db, id);
            if (current != null && current.hash().equals(hash)) {
                Sqlite.exec(db, "COMMIT");
                return true;
            }
            if (usableSpace.getAsLong() < reserve
                    || Long.parseLong(Sqlite.value(db, "SELECT coalesce(sum(length(body)),0) FROM spool"))
                    + Long.parseLong(Sqlite.value(db, "SELECT coalesce(sum(length(body)),0) FROM conflict"))
                    + body.length > quota) {
                Sqlite.exec(db, "ROLLBACK");
                return false;
            }
            if (current != null) {
                update(db, "INSERT OR IGNORE INTO conflict VALUES (?,?,?)", id, hash, body);
                Sqlite.exec(db, "COMMIT");
                return false;
            }
            update(db, "INSERT INTO spool(id,body,hash,category,created,state,reason) VALUES (?,?,?,?,?,?,?)",
                    id, body, hash, category.name(), now, category == Category.UNKNOWN ? "QUARANTINED" : "PENDING",
                    category == Category.UNKNOWN ? "UNKNOWN_CATEGORY" : null);
            beforeCommit.run();
            Sqlite.exec(db, "COMMIT");
            return true;
        }
    }

    Attempt claim(String id, String owner, long now, long lease, int budget) throws SQLException {
        try (Connection db = Sqlite.open(path, false)) {
            Sqlite.exec(db, "BEGIN IMMEDIATE");
            update(db, """
                    UPDATE spool SET state=CASE WHEN attempts>=? THEN 'QUARANTINED' ELSE 'RETRY_WAIT' END,
                        token=NULL,owner=NULL,version=version+1,
                        reason=CASE WHEN attempts>=? THEN 'RETRY_EXHAUSTED' ELSE 'LEASE_EXPIRED' END
                    WHERE id=? AND state='PUBLISHING' AND lease_until<=?
                    """, budget, budget, id, now);
            String token = UUID.randomUUID().toString();
            int changed = update(db, """
                    UPDATE spool SET state='PUBLISHING',attempts=attempts+1,version=version+1,
                        owner=?,token=?,lease_until=?
                    WHERE id=? AND (state='PENDING' OR (state='RETRY_WAIT' AND lease_until<=?)) AND attempts<?
                    """, owner, token, now + lease, id, now, budget);
            Record claimed = find(db, id);
            Sqlite.exec(db, "COMMIT");
            return changed == 0 ? null : new Attempt(id, owner, token, claimed.version());
        }
    }

    boolean finish(Attempt attempt, long now, boolean confirmed, long retryAt, int budget) throws SQLException {
        try (Connection db = Sqlite.open(path, false)) {
            return update(db, """
                    UPDATE spool SET state=CASE WHEN ? THEN 'BROKER_CONFIRMED'
                          WHEN attempts>=? THEN 'QUARANTINED' ELSE 'RETRY_WAIT' END,
                        reason=CASE WHEN ? THEN NULL WHEN attempts>=? THEN 'RETRY_EXHAUSTED' ELSE 'DELIVERY_UNKNOWN' END,
                        lease_until=?,token=NULL,owner=NULL,version=version+1
                    WHERE id=? AND state='PUBLISHING' AND owner=? AND token=? AND version=? AND lease_until>?
                    """, confirmed, budget, confirmed, budget, retryAt, attempt.id(), attempt.owner(), attempt.token(),
                    attempt.version(), now) == 1;
        }
    }

    boolean handoff(String id, Ingest fixture, Ingest.Proof proof) throws SQLException {
        Record record = read(id);
        if (record == null || !fixture.verify(id, record.hash(), proof)) {
            return false;
        }
        try (Connection db = Sqlite.open(path, false)) {
            return update(db, """
                    UPDATE spool SET state='HANDOFF_COMPLETE',evidence=?,version=version+1
                    WHERE id=? AND hash=? AND state IN ('PENDING','PUBLISHING','RETRY_WAIT','BROKER_CONFIRMED')
                    """, proof.commitId(), id, proof.hash()) == 1;
        }
    }

    boolean reclaim(String id, Ingest fixture, Ingest.Proof proof) throws SQLException {
        Record record = read(id);
        if (record == null || !fixture.verify(id, record.hash(), proof)) {
            return false;
        }
        try (Connection db = Sqlite.open(path, false)) {
            // Clear payload bytes only after durable local evidence; keep the ID and digest for reconciliation.
            return update(db, """
                    UPDATE spool SET body=NULL,version=version+1
                    WHERE id=? AND state='HANDOFF_COMPLETE' AND hash=? AND evidence=? AND body IS NOT NULL
                    """, id, proof.hash(), proof.commitId()) == 1;
        }
    }

    int expireTelemetry(long olderThan) throws SQLException {
        try (Connection db = Sqlite.open(path, false)) {
            return update(db, """
                    DELETE FROM spool WHERE category='ORDINARY_TELEMETRY' AND created<?
                    AND state IN ('PENDING','RETRY_WAIT','HANDOFF_COMPLETE')
                    """, olderThan);
        }
    }

    Record read(String id) throws SQLException {
        try (Connection db = Sqlite.open(path, true)) {
            return find(db, id);
        }
    }

    private static Record find(Connection db, String id) throws SQLException {
        try (var statement = db.prepareStatement("SELECT * FROM spool WHERE id=?")) {
            statement.setString(1, id);
            try (var row = statement.executeQuery()) {
                return row.next() ? new Record(id, row.getBytes("body"), row.getString("hash"), row.getString("state"),
                        row.getInt("attempts"), row.getLong("version")) : null;
            }
        }
    }

    static int update(Connection db, String query, Object... parameters) throws SQLException {
        try (var statement = db.prepareStatement(query)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setObject(i + 1, parameters[i]);
            }
            return statement.executeUpdate();
        }
    }
}
