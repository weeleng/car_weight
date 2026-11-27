package lab.handoff;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SchemaTest extends LabTest {
    @Test void emptyInitializesRepeatAndOldVersionGateRejectsCandidate() throws Exception {
        try (var owner=Owner.acquire(db())) {
            initialize(owner);
            String initial=Db.hash(Files.readAllBytes(db()));
            Migration.prepare(db(),temp.resolve("unused"),"fixture-volume",owner,OK);
            assertEquals(initial,Db.hash(Files.readAllBytes(db())));
        }
        try (var db=Db.open(db(),true)) {
            assertEquals(1,Db.number(db,"PRAGMA foreign_keys"));
            assertNotEquals(0,Db.number(db,"PRAGMA user_version")); // Formal v0 validator rejects any non-zero value.
            assertEquals(0,Db.number(db,"SELECT count(*) FROM "+Migration.LEGACY));
        }
        snapshot("A-empty");
        try (var db=Db.open(db(),true)) {
            Db.backup(db,evidenceDirectory().resolve("candidate-for-legacy-guard.db"));
        }
        legacy(evidenceDirectory().resolve("v0-for-legacy-guard.db"));
    }

    @Test void legacyMigrationKeepsBytesSourceRowsUnknownIdsAndRepeatedInput() throws Exception {
        legacy(db());
        for (String body:List.of("{\"id\":\"old-1\"}","{\"id\":\"old-1\"}","{}", "{malformed")) insertLegacy(db(),body);
        try (var lock=Owner.acquire(db())) {
            var spool=initialize(lock);
            assertEquals(Spool.Acceptance.STORED,receive(spool,id("new"),CONTENT));
            Migration.prepare(db(),temp.resolve("no-new-backup"),"fixture-volume",lock,OK);
        }
        try (var db=Db.open(db(),true)) {
            var source=Migration.historical(db);
            var migrated=Db.rows(db,"SELECT source_volume,source_row,migration_batch,payload,hash FROM key_spool_quarantine ORDER BY quarantine_id",
                    row -> row.getString(1)+":"+row.getString(2)+":"+row.getString(3)+":"+row.getString(5)+":"+Db.hash(row.getBytes(4)));
            assertEquals(4,source.size());
            assertEquals(4,migrated.size());
            for (int i=0;i<source.size();i++) {
                assertTrue(migrated.get(i).startsWith("fixture-volume:"+source.get(i).id()+":"));
                assertTrue(migrated.get(i).endsWith(":"+source.get(i).digest()+":"+source.get(i).digest()));
            }
            assertEquals(1,Db.number(db,"SELECT count(*) FROM key_event_spool"));
            assertEquals(0,Db.number(db,"SELECT count(*) FROM key_event_spool WHERE event_id='old-1'"));
            evidence("A-migration-map",String.join("\n",migrated));
        }
    }

    @Test void walBackupAndProcessInterruptedMigrationRecover() throws Exception {
        legacy(db());
        insertLegacy(db(),"first");
        exit(child("A-wal-halt","v0-wal",db().toString()),41);
        assertTrue(size(java.nio.file.Path.of(db()+"-wal"))>0);
        var main=Files.copy(db(),temp.resolve("main-only.db"));
        try (var input=Db.open(main,true)) {
            assertEquals(1,Migration.historical(input).size());
        }
        var backup=temp.resolve("before-cut.db");
        exit(child("A-migration-halt","migrate",db().toString(),backup.toString()),42);
        try (var input=Db.open(db(),true);var copy=Db.open(backup,true)) {
            assertEquals(0,Db.number(input,"PRAGMA user_version"));
            assertEquals(0,Db.number(input,"SELECT count(*) FROM sqlite_master WHERE name='key_event_spool'"));
            assertEquals(2,Migration.historical(copy).size());
            assertEquals(Migration.manifest(Migration.historical(input)),Migration.manifest(Migration.historical(copy)));
        }
        try (var owner=Owner.acquire(db())) { initialize(owner); }
        try (var input=Db.open(db(),true)) { assertEquals(2,Db.number(input,"SELECT count(*) FROM key_spool_quarantine")); }
        snapshot("A-wal-recovered");
    }

    @Test void unknownMixedCorruptAndReadonlyViewsAreHandled() throws Exception {
        var unknown=temp.resolve("unknown.db");
        var mixed=temp.resolve("mixed.db");
        var damaged=Files.writeString(temp.resolve("broken.db"),"synthetic corrupt data");
        legacy(unknown); legacy(mixed);
        try (var db=Db.open(unknown,false)) { Db.exec(db,"PRAGMA user_version=99"); }
        try (var db=Db.open(mixed,false)) { Db.exec(db,"CREATE TABLE unexpected(x)"); }
        for (var file:List.of(unknown,mixed,damaged)) {
            String original=Db.hash(Files.readAllBytes(file));
            try (var owner=Owner.acquire(file)) {
                assertThrows(SQLException.class,() -> Migration.prepare(file,temp.resolve("rejected"),"v",owner,OK));
            }
            assertEquals(original,Db.hash(Files.readAllBytes(file)));
        }
        try (var source=Db.open(mixed,true)) {
            assertThrows(SQLException.class,() -> Db.exec(source,"INSERT INTO unexpected VALUES (1)"));
        }
        evidence("A-rejected","unknown unchanged\nmixed unchanged\ncorrupt unchanged\nreadonly SQL writes rejected");
    }

    @Test void partiallyPublishedCandidateCannotRestoreOldBackupOverNewFacts() throws Exception {
        legacy(db());
        insertLegacy(db(),"original historical row");
        var backup=temp.resolve("offline-backup.db");
        try (var lock=Owner.acquire(db())) {
            Migration.prepare(db(),backup,"v0-volume",lock,OK);
            Migration.assertOfflineRestoreEligible(db(),lock);
            var spool=new Spool(db(),lock);
            var id=id("partly-published");
            receive(spool,id,CONTENT);
            var a=spool.claim(spool.find(id).row(),0,20,4);
            assertTrue(spool.result(a,1,1,"ACK",0,4));
            assertThrows(SQLException.class,() -> Migration.assertOfflineRestoreEligible(db(),lock));
            Migration.prepare(db(),temp.resolve("unused"),"v0-volume",lock,OK);
            assertEquals("BROKER_CONFIRMED",spool.find(id).status());
            assertArrayEquals(CONTENT,spool.find(id).body());
        }
        try (var before=Db.open(backup,true)) {
            assertEquals(0,Db.number(before,"PRAGMA user_version"));
            assertEquals(1,Migration.historical(before).size());
        }
        snapshot("A-forward-repair");
    }

    @Test void reciprocalReferenceRejectsMissingForeignProofAndUnfinishedNullPayload() throws Exception {
        try (var lock=Owner.acquire(db())) {
            var spool=initialize(lock);
            receive(spool,id("one"),CONTENT);
            receive(spool,id("two"),CONTENT);
            var authority=new Ingest(ingest());
            var proof=authority.commit(id("two"),CONTENT,OK);
            assertTrue(spool.handoff(id("two"),proof,authority::lookup,2,OK));
            try (var db=Db.open(db(),false)) {
                assertThrows(SQLException.class,() -> Db.exec(db,"UPDATE key_event_spool SET payload=NULL WHERE event_id='one'"));
                assertThrows(SQLException.class,() -> Db.exec(db,"UPDATE key_event_spool SET status='HANDOFF_COMPLETE',handoff_evidence=999,handoff_completed_at=3 WHERE event_id='one'"));
                long ref=Db.number(db,"SELECT evidence_id FROM key_handoff_evidence");
                assertThrows(SQLException.class,() -> Db.exec(db,"UPDATE key_event_spool SET status='HANDOFF_COMPLETE',handoff_evidence=?,handoff_completed_at=3 WHERE event_id='one'",ref));
                assertThrows(SQLException.class,() -> Db.exec(db,"DELETE FROM key_handoff_evidence"));
                // Compare a one-way reference: it accepts a completed row without a proof.
                Db.exec(db,"CREATE TEMP TABLE one_way(id INTEGER PRIMARY KEY,status TEXT)");
                Db.exec(db,"CREATE TEMP TABLE proof_one_way(ref INTEGER REFERENCES one_way(id))");
                assertEquals(1,Db.exec(db,"INSERT INTO one_way VALUES (1,'HANDOFF_COMPLETE')"));
                assertEquals(0,Db.number(db,"SELECT count(*) FROM proof_one_way"));
            }
            assertTrue(spool.cleanup(id("two"),true,OK));
        }
    }
}
