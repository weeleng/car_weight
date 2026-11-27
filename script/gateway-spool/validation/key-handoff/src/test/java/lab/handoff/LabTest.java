package lab.handoff;

import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

abstract class LabTest {
    @TempDir Path temp;
    static final Runnable OK=() -> {};
    static final byte[] CONTENT={1,2,3};
    Path db() { return temp.resolve("spool.db"); }
    Path ingest() { return temp.resolve("ingest.db"); }

    static Ingest.Identity id(String key) {
        return new Ingest.Identity("board-domain",key,"message-"+key,"tenant-a","board-a");
    }

    Spool initialize(Owner owner) throws Exception {
        Migration.prepare(db(),temp.resolve("backup-"+java.util.UUID.randomUUID()+".db"),"fixture-volume",owner,OK);
        return new Spool(db(),owner);
    }

    static Spool.Acceptance receive(Spool spool,Ingest.Identity id,byte[] bytes) throws Exception {
        return spool.receive(id,id.tenant(),Spool.Category.KEY_MEASUREMENT,bytes,1,
                10_000_000,1_000_000,1_000_000,() -> Long.MAX_VALUE,0,OK);
    }

    static Path evidenceDirectory() {
        return Path.of(System.getenv().getOrDefault("LAB_OUT","target/handoff-evidence"));
    }

    static void evidence(String name,String text) throws Exception {
        Files.createDirectories(evidenceDirectory());
        Files.writeString(evidenceDirectory().resolve(name+".txt"),text+"\n");
    }

    void snapshot(String name) throws Exception {
        try (var db=Db.open(db(),true)) {
            var facts=Db.rows(db,"SELECT event_id,message_id,status,payload_hash,version FROM key_event_spool ORDER BY local_record_id",
                    row -> row.getString(1)+" "+row.getString(2)+" "+row.getString(3)+" "+row.getString(4)+" "+row.getLong(5));
            evidence(name,"SQLite="+Db.text(db,"SELECT sqlite_version()")+"\nmain_sha256="+Db.hash(Files.readAllBytes(db()))
                    +"\nWAL_bytes="+size(Path.of(db()+"-wal"))+"\nrows="+facts.size()+"\n"+String.join("\n",facts)
                    +"\nproofs="+Db.number(db,"SELECT count(*) FROM key_handoff_evidence")
                    +"\nisolated="+Db.number(db,"SELECT count(*) FROM key_spool_quarantine"));
        }
    }

    static long size(Path p) throws Exception { return Files.exists(p)?Files.size(p):0; }

    static Process child(String tag,String... args) throws Exception {
        List<String> cmd=new ArrayList<>(List.of(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",
                System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),Child.class.getName()));
        cmd.addAll(List.of(args));
        Files.createDirectories(evidenceDirectory());
        return new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(evidenceDirectory().resolve(tag+".log").toFile()).start();
    }

    static void exit(Process p,int expected) throws Exception {
        if (!p.waitFor(35,TimeUnit.SECONDS)) {
            p.destroyForcibly();
            fail("child timeout");
        }
        assertEquals(expected,p.exitValue());
    }

    static void insertLegacy(Path file,String body) throws Exception {
        try (var c=Db.open(file,false)) {
            Db.exec(c,"INSERT INTO "+Migration.LEGACY+"(spool_time,size,message) VALUES (1,?,?)",
                    body.getBytes(StandardCharsets.UTF_8).length,body);
        }
    }

    static void legacy(Path file) throws Exception {
        try (var c=Db.open(file,false)) {
            Db.text(c,"PRAGMA journal_mode=WAL");
            Migration.legacy(c);
        }
    }
}
