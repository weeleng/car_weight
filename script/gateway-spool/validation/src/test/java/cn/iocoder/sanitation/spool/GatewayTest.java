package cn.iocoder.sanitation.spool;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GatewayTest extends LabTest {
    private void runCase(String name, List<String> extra) throws Exception {
        var args = new ArrayList<>(List.of("python3",
                Path.of(System.getenv("LAB_HOME"), "fixtures", "gateway.py").toString(), name));
        args.addAll(extra);
        assertEquals(0, await(start("gateway-" + name, args), 200), () -> "see " + out().resolve("gateway-" + name + ".log"));
    }

    @Test void configuredStartup() throws Exception { runCase("matrix", List.of()); }

    @Test void filesystemFailures() throws Exception { runCase("permissions", List.of()); }

    @Test void historicalSchemaAndInvalidBusConfiguration() throws Exception { runCase("schema", List.of()); }

    @Test void twoGatewaysIgnoreCooperativeLockAndRecoverAfterKill() throws Exception {
        runCase("shared", childCommand("hold", out().resolve("shared/spool.db").toString(),
                out().resolve("ownership-started").toString()));
    }

    @Test void actualRedisDrainersRepublishSameHistoricalRows() throws Exception { runCase("backlog", List.of()); }
}
