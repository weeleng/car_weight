package cn.iocoder.sanitation.spool;

import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

abstract class LabTest {
    @TempDir Path tmp;

    static final Runnable NO_FAULT = () -> { };

    static Path out() {
        return Path.of(System.getenv("LAB_OUT"));
    }

    static Path run() {
        return Path.of(System.getenv("LAB_RUN"));
    }

    static List<String> childCommand(String... args) {
        var cmd = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                Child.class.getName()));
        cmd.addAll(List.of(args));
        return cmd;
    }

    static Process start(String name, List<String> command) throws IOException {
        return new ProcessBuilder(command).redirectErrorStream(true)
                .redirectOutput(out().resolve(name + ".log").toFile()).start();
    }

    static int await(Process process, int seconds) throws InterruptedException {
        if (!process.waitFor(seconds, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("child timeout");
        }
        return process.exitValue();
    }

    static void waitForMarker(Path marker, Process process) throws InterruptedException {
        for (int i = 0; i < 200 && process.isAlive() && !Files.exists(marker); i++) {
            Thread.sleep(25);
        }
        assertTrue(process.isAlive() && Files.exists(marker), "child never reached hold point");
    }
}
