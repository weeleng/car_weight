package cn.iocoder.sanitation.spool;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

/** A local fixture store. Proof verification reads committed raw and outbox rows together. */
final class Ingest {
    record Proof(String source, String id, String hash, String commitId) { }

    private final Path path;
    private final String source;

    Ingest(Path path, String source) throws SQLException {
        this.path = path;
        this.source = source;
        try (var db = Sqlite.open(path, false)) {
            Sqlite.exec(db, "CREATE TABLE IF NOT EXISTS raw (id TEXT PRIMARY KEY, hash TEXT NOT NULL, body BLOB NOT NULL)");
            Sqlite.exec(db, "CREATE TABLE IF NOT EXISTS outbox (id TEXT PRIMARY KEY, hash TEXT NOT NULL, commit_id TEXT NOT NULL)");
        }
    }

    Proof receive(String id, byte[] payload, boolean commit) throws SQLException {
        String hash = Sqlite.hash(payload);
        try (var db = Sqlite.open(path, false)) {
            Sqlite.exec(db, "BEGIN IMMEDIATE");
            Proof existing = find(db, id);
            if (existing != null) {
                Sqlite.exec(db, "ROLLBACK");
                if (!existing.hash().equals(hash)) {
                    throw new SQLException("same identity, different payload");
                }
                return existing;
            }
            String commitId = UUID.randomUUID().toString();
            Spool.update(db, "INSERT INTO raw VALUES (?,?,?)", id, hash, payload);
            Spool.update(db, "INSERT INTO outbox VALUES (?,?,?)", id, hash, commitId);
            Sqlite.exec(db, commit ? "COMMIT" : "ROLLBACK");
            return commit ? new Proof(source, id, hash, commitId) : null;
        }
    }

    Proof lookup(String id) throws SQLException {
        try (var db = Sqlite.open(path, true)) {
            return find(db, id);
        }
    }

    boolean verify(String id, String hash, Proof proof) throws SQLException {
        return proof != null && id.equals(proof.id()) && hash.equals(proof.hash()) && proof.equals(lookup(id));
    }

    private Proof find(Connection db, String id) throws SQLException {
        try (var query = db.prepareStatement("""
                SELECT raw.hash, raw.body, outbox.hash, outbox.commit_id
                FROM raw JOIN outbox ON raw.id=outbox.id WHERE raw.id=?
                """)) {
            query.setString(1, id);
            try (var row = query.executeQuery()) {
                if (!row.next()) {
                    return null;
                }
                String hash = row.getString(1);
                if (!hash.equals(Sqlite.hash(row.getBytes(2))) || !hash.equals(row.getString(3))) {
                    throw new SQLException("inconsistent ingest intent");
                }
                return new Proof(source, id, hash, row.getString(4));
            }
        }
    }
}
