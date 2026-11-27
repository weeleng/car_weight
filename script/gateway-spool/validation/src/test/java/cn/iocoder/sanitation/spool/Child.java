package cn.iocoder.sanitation.spool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class Child {
    public static void main(String[] args) throws Exception {
        Path path = Path.of(args[1]);
        switch (args[0]) {
            case "hold" -> {
                FileOwnership acquired;
                try {
                    acquired = FileOwnership.acquire(path);
                } catch (IOException e) {
                    System.out.println("refused: " + e.getMessage());
                    System.exit(23);
                    return;
                }
                try (var lock = acquired) {
                    Files.createFile(Path.of(args[2]));
                    System.out.println("lock entered");
                    if (args.length == 3) {
                        Thread.sleep(120_000);
                    }
                }
            }
            case "wal" -> {
                try (var db = Sqlite.open(path, false)) {
                    Sqlite.exec(db, "PRAGMA journal_mode=WAL");
                    Sqlite.exec(db, "PRAGMA wal_autocheckpoint=0");
                    HistoryTest.insert(db, "{\"id\":\"wal\"}");
                    Runtime.getRuntime().halt(37);
                }
            }
            case "migrate" -> {
                Path target = Path.of(args[2]);
                try (var source = FileOwnership.acquire(path); var destination = FileOwnership.acquire(target)) {
                    LegacyMigration.migrate("fixture-source", path, target, Path.of(args[3]), source, destination,
                            () -> Runtime.getRuntime().halt(38));
                }
            }
            case "spool-crash" -> {
                var spool = new Spool(path);
                boolean beforeCommit = "before".equals(args[2]);
                spool.append("crash-event", new byte[]{1, 2}, Spool.Category.KEY_MEASUREMENT, 0, 1000, 0,
                        () -> Long.MAX_VALUE, beforeCommit ? () -> Runtime.getRuntime().halt(39) : () -> { });
                Runtime.getRuntime().halt(40);
            }
            case "redis-crash" -> BrokerTest.redisReadAndHalt(args[2]);
            case "broker-crash" -> BrokerTest.publishAndHalt(path, args[2]);
            default -> throw new IllegalArgumentException(args[0]);
        }
    }
}
