package lab.handoff;

import java.nio.file.Files;
import java.nio.file.Path;

public final class Child {
    public static void main(String[] args) throws Exception {
        Path path=Path.of(args[1]);
        switch (args[0]) {
            case "v0-wal" -> {
                try (var db=Db.open(path,false)) {
                    Db.exec(db,"PRAGMA wal_autocheckpoint=0");
                    Db.exec(db,"INSERT INTO "+Migration.LEGACY+"(spool_time,size,message) VALUES (1,6,'walrow')");
                    halt(41);
                }
            }
            case "migrate" -> {
                try (var owner=Owner.acquire(path)) {
                    Migration.prepare(path,Path.of(args[2]),"fixture-volume",owner,() -> halt(42));
                }
            }
            case "receive" -> {
                try (var owner=Owner.acquire(path)) {
                    var spool=new Spool(path,owner);
                    boolean before="before".equals(args[2]);
                    spool.receive(LabTest.id("cut"),"tenant-a",Spool.Category.KEY_MEASUREMENT,LabTest.CONTENT,1,
                            100,100,100,() -> 1000,0,before?() -> halt(43):LabTest.OK);
                    halt(44);
                }
            }
            case "proof" -> {
                try (var owner=Owner.acquire(path)) {
                    var spool=new Spool(path,owner);
                    boolean before="before".equals(args[3]);
                    spool.handoff(LabTest.id("cut"),null,new Ingest(Path.of(args[2]))::lookup,3,
                            before?() -> halt(45):LabTest.OK);
                    halt(46);
                }
            }
            case "t1" -> new Ingest(path).commit(LabTest.id("cut"),LabTest.CONTENT,() -> halt(47));
            case "t1-unknown" -> {
                new Ingest(path).commit(LabTest.id("cut"),LabTest.CONTENT,LabTest.OK);
                halt(50);
            }
            case "broker" -> {
                try (var owner=Owner.acquire(path)) {
                    var spool=new Spool(path,owner);
                    var row=spool.find(LabTest.id("crash"));
                    if (spool.claim(row.row(),1,5,4)==null) throw new IllegalStateException("cannot claim");
                    BrokerTest.publishCrash(row,args[2]);
                    halt(48);
                }
            }
            case "hold" -> {
                Owner owned;
                try { owned=Owner.acquire(path); } catch (java.io.IOException e) { halt(49); return; }
                try (owned) {
                    Files.createFile(Path.of(args[2]));
                    Thread.sleep(90_000);
                }
            }
            default -> throw new IllegalArgumentException(args[0]);
        }
    }

    private static void halt(int code) {
        System.out.println("fixture halt "+code);
        Runtime.getRuntime().halt(code);
    }
}
