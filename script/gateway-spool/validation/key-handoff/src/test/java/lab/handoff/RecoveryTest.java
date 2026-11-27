package lab.handoff;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class RecoveryTest extends LabTest {
    @Test void allAdmittedWorkersFenceNewOwnerUntilStopped() throws Exception {
        var owner=Owner.acquire(db());
        var spool=initialize(owner);
        var id=id("shutdown");
        receive(spool,id,CONTENT);
        var all=new CountDownLatch(4);
        var release=new CountDownLatch(1);
        var pool=Executors.newFixedThreadPool(4);
        try {
            var pending=new ArrayList<java.util.concurrent.Future<?>>();
            for (String role:List.of("publisher","query","notification","cleanup")) {
                pending.add(pool.submit(() -> {
                    try (var ticket=owner.admit()) {
                        all.countDown();
                        if (!release.await(8,TimeUnit.SECONDS)) throw new IllegalStateException("test gate timed out");
                        // A worker can finish its already-admitted sqlite transaction after shutdown starts.
                        if ("publisher".equals(role)) {
                            var a=spool.claim(spool.find(id).row(),0,10,3);
                            assertTrue(spool.result(a,1,1,"TRANSPORT_UNKNOWN",5,3));
                        }
                        if ("cleanup".equals(role)) assertFalse(spool.cleanup(id,false,OK));
                    }
                    return null;
                }));
            }
            assertTrue(all.await(8,TimeUnit.SECONDS));
            owner.close();
            assertThrows(IOException.class,owner::admit);
            assertThrows(SQLException.class,() -> receive(spool,id("denied"),CONTENT));
            assertThrows(SQLException.class,() -> spool.handoff(id,null,ignored -> {
                throw new AssertionError("query started after admission closed");
            },3,OK));
            assertThrows(IOException.class,() -> Owner.acquire(db()));
            release.countDown();
            for (var worker:pending) worker.get(8,TimeUnit.SECONDS);
        } finally {
            release.countDown(); pool.shutdownNow(); owner.close();
        }
        try (var successor=Owner.acquire(db())) {
            var next=new Spool(db(),successor);
            assertEquals("RETRY_WAIT",next.find(id).status());
            assertArrayEquals(CONTENT,next.find(id).body());
        }
        snapshot("D-close");
    }

    @Test void actualSuccessorProcessIsExcludedUntilKillAndOldGenerationCannotWrite() throws Exception {
        try (var lock=Owner.acquire(db())) { receive(initialize(lock),id("owner"),CONTENT); }
        var marker=temp.resolve("holder-ready");
        var live=child("D-hold","hold",db().toString(),marker.toString());
        try {
            long started=System.nanoTime();
            while (!Files.exists(marker) && live.isAlive() && System.nanoTime()-started<TimeUnit.SECONDS.toNanos(8)) {
                Thread.sleep(20);
            }
            assertTrue(Files.exists(marker));
            exit(child("D-contender","hold",db().toString(),temp.resolve("not-owner").toString()),49);
            live.destroyForcibly();
            exit(live,137);
        } finally { live.destroyForcibly(); }
        try (var lock=Owner.acquire(db())) {
            var next=new Spool(db(),lock);
            assertArrayEquals(CONTENT,next.find(id("owner")).body());
        }
        evidence("D-kill","contender=49\nowner kill=137\nsuccessor recovered original ID");
    }

    @Test void separateBudgetsRejectPhysicalLowAndProtectCriticalAgainstOrdinaryLoad() throws Exception {
        try (var lock=Owner.acquire(db())) {
            var spool=initialize(lock);
            assertEquals(Spool.Acceptance.STORED,spool.receive(id("ordinary"),"tenant-a",Spool.Category.ORDINARY_TELEMETRY,
                    CONTENT,1,3,3,3,() -> 100,10,OK));
            assertEquals(Spool.Acceptance.REJECTED,spool.receive(id("ordinary-2"),"tenant-a",Spool.Category.ORDINARY_TELEMETRY,
                    CONTENT,1,3,3,3,() -> 100,10,OK));
            assertEquals(Spool.Acceptance.STORED,spool.receive(id("critical"),"tenant-a",Spool.Category.KEY_MEASUREMENT,
                    CONTENT,1,3,3,3,() -> 100,10,OK));
            assertEquals(Spool.Acceptance.REJECTED,spool.receive(id("full"),"tenant-a",Spool.Category.KEY_MEASUREMENT,
                    CONTENT,1,3,3,3,() -> 100,10,OK));
            assertEquals(Spool.Acceptance.REJECTED,spool.receive(id("physical"),"tenant-a",Spool.Category.KEY_MEASUREMENT,
                    CONTENT,1,100,100,100,() -> 9,10,OK));
            assertEquals(1,spool.expireOrdinary(Long.MAX_VALUE));
            assertArrayEquals(CONTENT,spool.find(id("critical")).body());
        }
        snapshot("D-budget");
    }

    @Test void boundedConcurrentBacklogAndProofRecoveryMeasuresFileComponents() throws Exception {
        int devices=4, each=24;
        var data=new byte[8192];
        try (var lock=Owner.acquire(db())) {
            var spool=initialize(lock);
            long start=System.nanoTime();
            var pool=Executors.newFixedThreadPool(devices);
            try (var anchor=Db.open(db(),false)) {
                Db.exec(anchor,"PRAGMA wal_autocheckpoint=0");
                var jobs=new ArrayList<java.util.concurrent.Future<?>>();
                for (int d=0;d<devices;d++) {
                    final int device=d;
                    jobs.add(pool.submit(() -> {
                        for (int i=0;i<each;i++) {
                            var id=capacityId(device,i);
                            assertEquals(Spool.Acceptance.STORED,spool.receive(id,id.tenant(),Spool.Category.KEY_MEASUREMENT,
                                    data,1,10_000_000,1_000_000,1_000_000,() -> Long.MAX_VALUE,0,OK));
                        }
                        return null;
                    }));
                }
                for (var result:jobs) result.get(20,TimeUnit.SECONDS);
                double writeSeconds=(System.nanoTime()-start)/1e9;
                assertEquals(devices*each,Db.number(anchor,"SELECT count(*) FROM key_event_spool WHERE status='PENDING'"));
                assertEquals(0,spool.expireOrdinary(TimeUnit.DAYS.toMillis(20)));
                spool.receive(id("isolate-sample"),"tenant-a",Spool.Category.UNKNOWN,CONTENT,2,
                        100,100,100,() -> 1000,0,OK);
                long wal=size(Path.of(db()+"-wal"));
                var authority=new Ingest(ingest());
                assertFalse(spool.handoff(capacityId(0,0),null,authority::lookup,2,OK));
                assertThrows(SQLException.class,() -> spool.handoff(capacityId(0,0),null,
                        ignored -> {throw new SQLException("authority unavailable");},2,OK));
                // Round-robin recovery fixture across synthetic devices.
                long replayStart=System.nanoTime();
                for (int i=0;i<each;i++) for (int d=0;d<devices;d++) {
                    var id=capacityId(d,i);
                    var a=spool.claim(spool.find(id).row(),1,5,5);
                    assertTrue(spool.result(a,2,2,"ACK",0,5));
                    spool.waitIngest(a.row());
                    authority.commit(id,data,OK);
                }
                double replaySeconds=(System.nanoTime()-replayStart)/1e9;
                long proofStart=System.nanoTime();
                for (int i=0;i<each;i++) for (int d=0;d<devices;d++) {
                    assertTrue(spool.handoff(capacityId(d,i),null,authority::lookup,3,OK));
                }
                double proofSeconds=(System.nanoTime()-proofStart)/1e9;
                assertEquals(devices*each,Db.number(anchor,"SELECT count(*) FROM key_event_spool WHERE status='HANDOFF_COMPLETE'"));
                Db.text(anchor,"PRAGMA wal_checkpoint(TRUNCATE)");
                var pages=Db.rows(anchor,"SELECT name,sum(pgsize) FROM dbstat GROUP BY name ORDER BY name",
                        row -> row.getString(1)+"="+row.getLong(2));
                evidence("D-capacity","devices="+devices+" rows="+(devices*each)+" bytes_each="+data.length
                        +"\nsynthesized_age_days=20\nwrite_seconds="+writeSeconds+"\nwal_before_recovery="+wal
                        +"\nmain_after_checkpoint="+size(db())+"\nwal_after_checkpoint="+size(Path.of(db()+"-wal"))
                        +"\nlocal_replay_and_T1_rate="+(devices*each/replaySeconds)
                        +"\nauthority_query_register_rate="+(devices*each/proofSeconds)+"\n"+String.join("\n",pages));
            } finally { pool.shutdownNow(); }
        }
    }

    private static Ingest.Identity capacityId(int device,int n) {
        return new Ingest.Identity("synthetic-domain","e-"+device+"-"+n,"m-"+device+"-"+n,
                "tenant-a","board-"+device);
    }

    @Test void syntheticAndroidAndPdaSwitchRebootDuplicateAndLostAckKeepOriginalOnBoard() throws Exception {
        var board=temp.resolve("synthetic-board.db");
        var id=id("board-event");
        try (var db=Db.open(board,false)) {
            Db.exec(db,"CREATE TABLE original(event_id TEXT PRIMARY KEY,raw BLOB NOT NULL)");
            Db.exec(db,"INSERT INTO original VALUES (?,?)",id.event(),CONTENT);
        }
        var authority=new Ingest(ingest());
        try (var lock=Owner.acquire(db())) {
            var spool=initialize(lock);
            byte[] rs485;
            try (var android=Db.open(board,true)) {
                rs485=Db.rows(android,"SELECT raw FROM original WHERE event_id=?",row -> row.getBytes(1),id.event()).get(0);
            }
            assertEquals(Spool.Acceptance.STORED,receive(spool,id,rs485));
            // Fresh process handles stand for a restarted board, restarted terminal and a second transport.
            byte[] bluetooth;
            try (var pda=Db.open(board,true)) {
                bluetooth=Db.rows(pda,"SELECT raw FROM original WHERE event_id=?",row -> row.getBytes(1),id.event()).get(0);
            }
            assertEquals(Spool.Acceptance.DUPLICATE,receive(spool,id,bluetooth));
            assertEquals(Spool.Acceptance.CONFLICT,receive(spool,id,new byte[]{9}));
            assertNull(authority.lookup(id)); // No fixture ACK before a committed authoritative T1.
            var lost=authority.commit(id,rs485,OK);
            // Simulate ACK lost before the board receives it; original remains and terminal re-uploads unchanged.
            try (var boardDb=Db.open(board,true)) {
                assertEquals(1,Db.number(boardDb,"SELECT count(*) FROM original"));
            }
            assertEquals(Spool.Acceptance.DUPLICATE,receive(spool,id,bluetooth));
            var replay=authority.commit(id,bluetooth,OK);
            assertEquals(lost,replay);
            assertTrue(spool.handoff(id,replay,authority::lookup,3,OK));
            try (var boardDb=Db.open(board,false)) {
                assertEquals(id,replay.identity());
                assertEquals(Db.hash(bluetooth),replay.hash());
                Db.exec(boardDb,"DELETE FROM original WHERE event_id=?",replay.identity().event());
                assertEquals(0,Db.number(boardDb,"SELECT count(*) FROM original"));
            }
        }
        evidence("D-terminations","synthetic board persistence\nRS485 then Bluetooth\nsame origin ID\nACK lost then relayed\nboard releases after matching fixture proof");
    }
}
