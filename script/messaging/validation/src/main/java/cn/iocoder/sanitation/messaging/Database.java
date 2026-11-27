package cn.iocoder.sanitation.messaging;

import java.util.Objects;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Disposable database connections with a local transaction manager per schema. */
final class Database {
    final DriverManagerDataSource source;
    final JdbcTemplate sql;
    private final JdbcTransactionManager manager;

    Database(String schema) {
        if (!java.util.Set.of("ingest", "business", "track").contains(schema)) {
            throw new IllegalArgumentException("unknown fixture schema");
        }
        var marker = required("MARKER");
        if (!marker.matches("eng002e-[a-f0-9]{16}")) throw new IllegalStateException("invalid isolation marker");
        source = new DriverManagerDataSource(
                "jdbc:mysql://127.0.0.1:" + port("DB_PORT", 3306) + "/probe_" + schema
                        + "?sslMode=DISABLED&allowPublicKeyRetrieval=true&connectionTimeZone=UTC"
                        + "&forceConnectionTimeZoneToSession=true&connectTimeout=2500&socketTimeout=5000",
                schema, required(schema.toUpperCase(java.util.Locale.ROOT) + "_PASSWORD"));
        sql = new JdbcTemplate(source);
        manager = new JdbcTransactionManager(source);
        Map<String, Object> identity = sql.queryForMap(
                "SELECT VERSION() AS version, @@server_uuid AS uuid, @@report_host AS marker, DATABASE() AS db");
        if (!identity.get("version").toString().startsWith("8.4.")
                || !required("DB_UUID").equals(identity.get("uuid"))
                || !marker.equals(identity.get("marker")) || !("probe_" + schema).equals(identity.get("db"))) {
            throw new IllegalStateException("unexpected fixture database identity");
        }
    }

    static String required(String key) {
        return Objects.requireNonNull(System.getenv("ENG002E_" + key), "isolated runner requires " + key);
    }

    static int port(String key, int standard) {
        int value = Integer.parseInt(required(key));
        if (value < 1024 || value > 65535 || value == standard) throw new IllegalStateException("invalid lab port");
        return value;
    }

    <T> T commit(Function<TransactionStatus, T> work) {
        return commit(TransactionDefinition.ISOLATION_DEFAULT, work);
    }

    <T> T commit(int isolation, Function<TransactionStatus, T> work) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("fixture transactions must start from a clean boundary");
        }
        var template = new TransactionTemplate(manager);
        template.setIsolationLevel(isolation);
        var committed = new AtomicBoolean();
        var result = template.execute(status -> {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { committed.set(true); }
            });
            return work.apply(status);
        });
        if (!committed.get()) throw new CommitNotProven();
        return result;
    }

    int count(String table) {
        return sql.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    static final class CommitNotProven extends IllegalStateException {}
}
