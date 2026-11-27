package lab.handoff;

import org.junit.jupiter.api.Test;
import java.sql.SQLException;
import static org.junit.jupiter.api.Assertions.*;

class StateTest extends LabTest {
    @Test void trustedClassesSeparateAndIdentityConflictPreservesFirstBytes() throws Exception {
        try (var lock=Owner.acquire(db())) {
            var spool=initialize(lock);
            var id=id("original");
            assertEquals(Spool.Acceptance.STORED,receive(spool,id,CONTENT));
            assertEquals(Spool.Acceptance.DUPLICATE,receive(spool,id,CONTENT));
            assertEquals(Spool.Acceptance.CONFLICT,receive(spool,id,new byte[]{9}));
            assertArrayEquals(CONTENT,spool.find(id).body());
            assertEquals(Spool.Acceptance.STORED,receive(spool,id("different"),CONTENT));
            var foreign=new Ingest.Identity(id.namespace(),id.event(),id.message(),"tenant-b",id.device());
            assertEquals(Spool.Acceptance.REJECTED,receive(spool,foreign,CONTENT));
            for (var category: new Spool.Category[]{Spool.Category.ORDINARY_TELEMETRY,Spool.Category.UNKNOWN}) {
                spool.receive(id("untrusted"),"tenant-a",category,"{priority:KEY}".getBytes(),1,
                        100,100,100,() -> 1000,0,OK);
            }
            assertEquals(Spool.Acceptance.REJECTED,spool.receive(id("invalid"),"tenant-a",null,CONTENT,1,
                    100,100,100,() -> 1000,0,OK));
            assertNull(spool.find(id("untrusted")));
            assertEquals(1,spool.expireOrdinary(10_000_000));
            try (var db=Db.open(db(),true)) {
                assertEquals(2,Db.number(db,"SELECT count(*) FROM key_spool_quarantine"));
                assertEquals(2,Db.number(db,"SELECT count(*) FROM key_event_spool"));
            }
        }
        snapshot("B-classification");
    }

    @Test void processBeforeCommitVersusAfterCommitAreDistinct() throws Exception {
        try (var lock=Owner.acquire(db())) { initialize(lock); }
        exit(child("B-before-local","receive",db().toString(),"before"),43);
        try (var db=Db.open(db(),true)) { assertEquals(0,Db.number(db,"SELECT count(*) FROM key_event_spool")); }
        exit(child("B-after-local","receive",db().toString(),"after"),44);
        try (var lock=Owner.acquire(db())) {
            var spool=new Spool(db(),lock);
            assertArrayEquals(CONTENT,spool.find(id("cut")).body());
            assertEquals("PENDING",spool.find(id("cut")).status());
        }
        snapshot("B-after-commit");
    }

    @Test void monotonicLeaseLateOldAndDuplicateCallbacksFailFencing() throws Exception {
        try (var lock=Owner.acquire(db())) {
            var spool=initialize(lock);
            receive(spool,id("lease"),CONTENT);
            long row=spool.find(id("lease")).row();
            spool.waitIngest(row);
            assertEquals("PENDING",spool.find(id("lease")).status());
            var first=spool.claim(row,100,5,5);
            assertNull(spool.claim(row,104,5,5));
            // Wall-clock forward/backward values are metadata, not lease time.
            assertFalse(spool.result(first,105,Long.MAX_VALUE,"ACK",0,5));
            var next=spool.claim(row,105,5,5);
            assertNotEquals(first.id(),next.id());
            assertFalse(spool.result(first,106,Long.MIN_VALUE,"ACK",0,5));
            assertFalse(spool.result(new Spool.Attempt(row,next.owner()+1,next.id(),next.version()),106,-100,"ACK",0,5));
            assertTrue(spool.result(next,106,-100,"ACK",0,5));
            assertFalse(spool.result(next,107,Long.MAX_VALUE,"ACK",0,5));
            spool.waitIngest(row);
            assertEquals("WAITING_INGEST",spool.find(id("lease")).status());
            assertEquals(Db.hash(CONTENT),spool.find(id("lease")).hash());
        }
        snapshot("B-lease");
    }

    @Test void ownerRestartIsolatesOldAttemptsAndBudgetNeverDeletesPayload() throws Exception {
        var id=id("retry");
        Spool old;
        Spool.Attempt before;
        try (var lock=Owner.acquire(db())) {
            old=initialize(lock);
            receive(old,id,CONTENT);
            before=old.claim(old.find(id).row(),1000,5,3);
        }
        try (var lock=Owner.acquire(db())) {
            var next=new Spool(db(),lock);
            var claim=next.claim(before.row(),0,5,2);
            assertNotEquals(before.owner(),claim.owner());
            assertThrows(SQLException.class,() -> old.result(before,1,2,"ACK",0,3));
            assertFalse(next.result(before,1,2,"ACK",0,3));
            assertTrue(next.result(claim,1,2,"TRANSPORT_UNKNOWN",8,2));
            assertEquals("QUARANTINED",next.find(id).status());
            assertFalse(next.cleanup(id,false,OK));
            assertArrayEquals(CONTENT,next.find(id).body());
        }
    }

    @Test void permanentBadAndUnconfirmedAreProtectedAgainstExpiry() throws Exception {
        try (var lock=Owner.acquire(db())) {
            var spool=initialize(lock);
            for (String reason:new String[]{"PERMANENT","BAD_PAYLOAD","TRANSPORT_UNKNOWN"}) {
                var id=id(reason);
                receive(spool,id,CONTENT);
                var a=spool.claim(spool.find(id).row(),1,5,4);
                assertTrue(spool.result(a,2,3,reason,9,4));
                assertFalse(spool.cleanup(id,false,OK));
            }
            assertEquals(0,spool.expireOrdinary(Long.MAX_VALUE));
            for (String reason:new String[]{"PERMANENT","BAD_PAYLOAD","TRANSPORT_UNKNOWN"}) {
                assertArrayEquals(CONTENT,spool.find(id(reason)).body());
            }
        }
    }
}
