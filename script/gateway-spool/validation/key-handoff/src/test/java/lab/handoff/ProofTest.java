package lab.handoff;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProofTest extends LabTest {
    @Test void rollbackAndKilledT1LeaveNoPartialFactAndLostNotificationReconciles() throws Exception {
        var authority=new Ingest(ingest());
        var id=id("cut");
        assertThrows(IllegalStateException.class,() -> authority.commit(id,CONTENT,() -> {throw new IllegalStateException("rollback");}));
        assertNull(authority.lookup(id));
        exit(child("C-t1-before-commit","t1",ingest().toString()),47);
        try (var db=Db.open(ingest(),true)) {
            assertEquals(0,Db.number(db,"SELECT count(*) FROM raw"));
            assertEquals(0,Db.number(db,"SELECT count(*) FROM outbox"));
        }
        try (var lock=Owner.acquire(db())) { receive(initialize(lock),id,CONTENT); }
        // Committed fact, lost commit response, and no notification.
        exit(child("C-t1-lost-response","t1-unknown",ingest().toString()),50);
        try (var lock=Owner.acquire(db())) {
            var spool=new Spool(db(),lock);
            assertFalse(spool.handoff(id,null, ignored -> null,2,OK));
            assertThrows(SQLException.class,() -> spool.handoff(id,null,ignored -> {throw new SQLException("timeout");},2,OK));
            assertEquals("PENDING",spool.find(id).status());
            assertTrue(spool.handoff(id,null,authority::lookup,2,OK));
        }
        snapshot("C-t1-reconciled");
    }

    @Test void rejectsIncorrectFieldsDuplicateProofMutationAndBrokenIntent() throws Exception {
        try (var lock=Owner.acquire(db())) {
            var spool=initialize(lock);
            var id=id("p1");
            receive(spool,id,CONTENT);
            var authority=new Ingest(ingest());
            var correct=authority.commit(id,CONTENT,OK);
            List<Ingest.Identity> wrong=List.of(
                    new Ingest.Identity(id.namespace(),id.event(),id.message(),"tenant-b",id.device()),
                    new Ingest.Identity(id.namespace(),id.event(),id.message(),id.tenant(),"board-b"),
                    new Ingest.Identity(id.namespace(),"other",id.message(),id.tenant(),id.device()),
                    new Ingest.Identity(id.namespace(),id.event(),"other",id.tenant(),id.device()));
            for (var altered:wrong) assertFalse(spool.handoff(id,copy(correct,correct.issuer(),altered,correct.hash(),correct.intent(),1),
                    authority::lookup,2,OK));
            assertFalse(spool.handoff(id,copy(correct,"outsider",id,correct.hash(),correct.intent(),1),authority::lookup,2,OK));
            assertFalse(spool.handoff(id,copy(correct,correct.issuer(),id,"wrong-digest",correct.intent(),1),authority::lookup,2,OK));
            assertFalse(spool.handoff(id,copy(correct,correct.issuer(),id,correct.hash(),"other-intent",1),authority::lookup,2,OK));
            assertFalse(spool.handoff(id,copy(correct,correct.issuer(),id,correct.hash(),correct.intent(),99),authority::lookup,2,OK));
            assertFalse(spool.handoff(id,correct,ignored -> null,3,OK));
            assertEquals("PENDING",spool.find(id).status());
            assertTrue(spool.handoff(id,correct,authority::lookup,4,OK));
            long version=spool.find(id).version();
            assertTrue(spool.handoff(id,correct,authority::lookup,5,OK));
            assertFalse(spool.handoff(id,copy(correct,correct.issuer(),id,"changed",correct.intent(),1),authority::lookup,6,OK));
            assertEquals(version,spool.find(id).version());
            try (var db=Db.open(ingest(),false)) { Db.exec(db,"UPDATE outbox SET intent='truncated'"); }
            assertThrows(SQLException.class,() -> authority.lookup(id));
            assertEquals(1,queryProofCount());
        }
        snapshot("C-proof-negatives");
    }

    private static Ingest.Proof copy(Ingest.Proof p,String issuer,Ingest.Identity id,String hash,String intent,int version) {
        return new Ingest.Proof(p.id(),issuer,id,hash,intent,p.rawRef(),p.outboxRef(),version);
    }

    private long queryProofCount() throws SQLException {
        try (var db=Db.open(db(),true)) { return Db.number(db,"SELECT count(*) FROM key_handoff_evidence"); }
    }

    @Test void proofBeforeBrokerUpdateAtomicRollbackCrashAndCleanupFailure() throws Exception {
        var id=id("cut");
        var authority=new Ingest(ingest());
        var proof=authority.commit(id,CONTENT,OK);
        try (var lock=Owner.acquire(db())) {
            var spool=initialize(lock);
            receive(spool,id,CONTENT);
            var attempt=spool.claim(spool.find(id).row(),1,30,4);
            assertFalse(spool.cleanup(id,true,OK));
            assertThrows(IllegalStateException.class,() -> spool.handoff(id,proof,authority::lookup,2,() -> {
                throw new IllegalStateException("abort evidence and handoff");
            }));
            assertEquals(0,queryProofCount());
            assertEquals("PUBLISHING",spool.find(id).status());
            assertTrue(spool.handoff(id,proof,authority::lookup,2,OK));
            assertFalse(spool.result(attempt,3,3,"ACK",0,4));
            assertArrayEquals(CONTENT,spool.find(id).body());
            assertThrows(IllegalStateException.class,() -> spool.cleanup(id,true,() -> {throw new IllegalStateException("archive fail");}));
            assertArrayEquals(CONTENT,spool.find(id).body());
            try (var db=Db.open(db(),true)) { assertEquals(0,Db.number(db,"SELECT count(*) FROM lab_cleanup")); }
        }
    }

    @Test void proofCrashBeforeAndAfterAreRecoverableAndCleanupRetainsAudit() throws Exception {
        var id=id("cut");
        try (var lock=Owner.acquire(db())) { receive(initialize(lock),id,CONTENT); }
        var authority=new Ingest(ingest());
        authority.commit(id,CONTENT,OK);
        exit(child("C-pre-proof","proof",db().toString(),ingest().toString(),"before"),45);
        assertEquals(0,queryProofCount());
        exit(child("C-post-proof","proof",db().toString(),ingest().toString(),"after"),46);
        try (var lock=Owner.acquire(db())) {
            var spool=new Spool(db(),lock);
            assertEquals("HANDOFF_COMPLETE",spool.find(id).status());
            assertArrayEquals(CONTENT,spool.find(id).body());
            assertTrue(spool.cleanup(id,true,OK));
            assertFalse(spool.cleanup(id,true,OK));
            assertNull(spool.find(id).body());
            assertEquals(Spool.Acceptance.DUPLICATE,receive(spool,id,CONTENT));
            assertThrows(SQLException.class,() -> spool.handoff(id,null,ignored -> {throw new SQLException("unavailable");},3,OK));
            assertEquals("HANDOFF_COMPLETE",spool.find(id).status());
            try (var db=Db.open(db(),true)) {
                assertEquals(Db.hash(CONTENT),Db.text(db,"SELECT payload_hash FROM lab_cleanup"));
                assertArrayEquals(CONTENT,Db.rows(db,"SELECT archived FROM lab_cleanup",row -> row.getBytes(1)).get(0));
            }
        }
        snapshot("C-cleanup-audit");
    }

    @Test void tamperedEvidenceBlocksCleanup() throws Exception {
        try (var lock=Owner.acquire(db())) {
            var spool=initialize(lock);
            var id=id("tamper");
            receive(spool,id,CONTENT);
            var authority=new Ingest(ingest());
            assertTrue(spool.handoff(id,authority.commit(id,CONTENT,OK),authority::lookup,3,OK));
            try (var db=Db.open(db(),false)) { Db.exec(db,"UPDATE key_handoff_evidence SET hash='tampered'"); }
            assertThrows(SQLException.class,() -> spool.cleanup(id,false,OK));
            assertArrayEquals(CONTENT,spool.find(id).body());
        }
    }
}
