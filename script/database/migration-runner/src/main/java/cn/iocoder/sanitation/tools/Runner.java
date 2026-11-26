package cn.iocoder.sanitation.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;

public final class Runner {
    private Runner() { }

    public static void main(String[] arguments) {
        System.exit(run(arguments, System.getenv()));
    }

    static int run(String[] arguments, Map<String, String> env) {
        if (arguments.length != 3 || !("validate".equals(arguments[0]) || "migrate".equals(arguments[0]))) {
            System.err.println("Usage: Runner validate|migrate ingest|business|track <migration-root>");
            return 2;
        }
        try {
            Target target = Target.from(env, arguments[1], "migrator");
            Path root = Path.of(arguments[2]).toRealPath();
            Path location = root.resolve(target.domain()).toRealPath();
            if (!Files.isDirectory(location) || !location.getParent().equals(root)) {
                throw new IllegalArgumentException("Expected a direct migration directory for the selected domain");
            }
            try (var files = Files.list(location)) {
                if (files.anyMatch(p -> !Files.isRegularFile(p) || Files.isSymbolicLink(p)
                        || (p.getFileName().toString().endsWith(".sql") &&
                        !p.getFileName().toString().matches("V[0-9]+__[a-z0-9_]+\\.sql")))) {
                    throw new IllegalArgumentException("Only direct V<integer>__<description>.sql migrations are allowed");
                }
            }
            Flyway flyway = configured(target, location);
            flyway.validate();
            if ("migrate".equals(arguments[0])) {
                var result = flyway.migrate();
                configured(target, location, false).validate();
                System.out.printf("%s applied=%d target=%s%n", target.schema(), result.migrationsExecuted,
                        result.targetSchemaVersion);
            } else {
                System.out.printf("%s validated%n", target.schema());
            }
            return 0;
        } catch (IOException | IllegalArgumentException e) {
            System.err.println("Input refused: " + e.getClass().getSimpleName());
            return 2;
        } catch (FlywayException e) {
            System.err.println("Migration or validation refused: " + e.getClass().getSimpleName());
            return 1;
        }
    }

    static Flyway configured(Target target, Path location) {
        return configured(target, location, true);
    }

    private static Flyway configured(Target target, Path location, boolean pendingAllowed) {
        // Verify each physical connection opened by Flyway, avoiding a separate preflight race.
        var source = new com.mysql.cj.jdbc.MysqlDataSource() {
            @Override
            public Connection getConnection(String user, String secret) throws SQLException {
                Connection connection = super.getConnection(user, secret);
                try {
                    target.verify(connection);
                    return connection;
                } catch (SQLException | RuntimeException e) {
                    connection.close();
                    throw e;
                }
            }
        };
        source.setURL(target.url());
        source.setUser(target.account());
        source.setPassword(target.password());
        return Flyway.configure().dataSource(source).schemas(target.schema()).defaultSchema(target.schema())
                .locations("filesystem:" + location).table("flyway_schema_history").createSchemas(false)
                .cleanDisabled(true).baselineOnMigrate(false).outOfOrder(false).validateMigrationNaming(true)
                .ignoreMigrationPatterns(pendingAllowed ? new String[]{"*:pending"} : new String[0])
                .lockRetryCount(15).load();
    }
}
