package cn.iocoder.sanitation.tools;

import static org.junit.jupiter.api.Assertions.*;

import com.alibaba.druid.pool.DruidDataSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.JDBCType;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

class DatabaseTest {
    static final List<String> DOMAINS = List.of("ingest", "business", "track");
    static final Map<String, Local> BANK = new HashMap<>();
    static GenericApplicationContext context;

    @BeforeAll
    static void start() {
        context = new GenericApplicationContext();
        for (String domain : DOMAINS) {
            Local local = new Local(Target.from(System.getenv(), domain, "runtime"));
            BANK.put(domain, local);
            context.registerBean(domain + "DataSource", DataSource.class, () -> local.source);
            context.registerBean(domain + "TxManager", JdbcTransactionManager.class, () -> local.manager);
            context.registerBean(domain + "SqlSessionFactory", org.apache.ibatis.session.SqlSessionFactory.class,
                    local.session::getSqlSessionFactory);
            context.registerBean(domain + "SqlSessionTemplate", SqlSessionTemplate.class, () -> local.session);
        }
        context.refresh();
    }

    @AfterAll
    static void stop() {
        if (context != null) context.close();
        BANK.values().forEach(local -> local.pool.close());
    }

    static final class Local {
        final DruidDataSource pool = new DruidDataSource();
        final DataSource source;
        final JdbcTransactionManager manager;
        final TransactionTemplate tx;
        final JdbcTemplate jdbc;
        final SqlSessionTemplate session;

        Local(Target target) {
            pool.setUrl(target.url());
            pool.setUsername(target.account());
            pool.setPassword(target.password());
            pool.setMaxActive(4);
            pool.setMaxWait(2000);
            source = new AbstractDataSource() {
                @Override
                public Connection getConnection() throws SQLException {
                    if (TransactionSynchronizationManager.isActualTransactionActive()
                            && !TransactionSynchronizationManager.hasResource(this)) {
                        throw new SQLException("Cross-catalog transaction refused");
                    }
                    Connection connection = pool.getConnection();
                    try {
                        target.verify(connection);
                        return connection;
                    } catch (SQLException | RuntimeException e) {
                        connection.close();
                        throw e;
                    }
                }

                @Override
                public Connection getConnection(String username, String password) throws SQLException {
                    throw new SQLException("Alternate credentials are not allowed");
                }
            };
            manager = new JdbcTransactionManager(source) {
                @Override
                protected Object doGetTransaction() {
                    // Spring can suspend synchronization before acquiring a nested manager's connection.
                    // Reject a foreign outer transaction before that happens.
                    if (TransactionSynchronizationManager.isActualTransactionActive()
                            && !TransactionSynchronizationManager.hasResource(source)) {
                        throw new org.springframework.transaction.IllegalTransactionStateException(
                                "A different database already owns the current transaction");
                    }
                    return super.doGetTransaction();
                }
            };
            tx = new TransactionTemplate(manager);
            jdbc = new JdbcTemplate(source);
            try {
                manager.afterPropertiesSet();
                var cfg = new org.apache.ibatis.session.Configuration();
                if ("ingest".equals(target.domain())) cfg.addMapper(PrecisionMapper.class);
                if ("track".equals(target.domain())) cfg.addMapper(TrackMapper.class);
                var factory = new SqlSessionFactoryBean();
                factory.setConfiguration(cfg);
                factory.setDataSource(source);
                session = new SqlSessionTemplate(factory.getObject());
            } catch (Exception e) {
                throw new IllegalStateException("Could not bind isolated factory", e);
            }
        }
    }

    public interface TrackMapper {
        @Insert("INSERT INTO probe_point VALUES (#{id})")
        int insert(long id);
    }

    public interface PrecisionMapper {
        @Insert("INSERT INTO probe_precision VALUES (#{id}, #{utc,jdbcType=TIMESTAMP}, #{grams}, #{kg})")
        int insert(@Param("id") long id, @Param("utc") LocalDateTime utc,
                   @Param("grams") long grams, @Param("kg") BigDecimal kg);

        @Select("SELECT recorded_utc FROM probe_precision WHERE id=#{id}")
        LocalDateTime instant(long id);
    }

    record ApiTimestamp(Instant recordedAt) { }

    @Test
    void namedRegistrationsAndIndependentConnections() throws SQLException {
        assertEquals(3, context.getBeansOfType(DataSource.class).size());
        var connections = new java.util.HashSet<String>();
        for (String domain : DOMAINS) {
            var local = BANK.get(domain);
            assertSame(context.getBean(domain + "DataSource"), local.manager.getDataSource());
            assertSame(local.source, local.session.getSqlSessionFactory().getConfiguration().getEnvironment().getDataSource());
            assertSame(local.manager, context.getBean(domain + "TxManager"));
            assertEquals("sanitation_" + domain, local.jdbc.queryForObject("SELECT DATABASE()", String.class));
            assertEquals("+00:00", local.jdbc.queryForObject("SELECT @@session.time_zone", String.class));
            connections.add(local.jdbc.queryForObject("SELECT CONCAT(@@server_uuid, ':', CONNECTION_ID())", String.class));
            assertTrue(local.pool.getPoolingCount() >= 1);
        }
        assertEquals(3, connections.size());
    }

    @Test
    void rejectsUnknownWrongCatalogAndUnavailableConnections() throws SQLException {
        assertThrows(IllegalArgumentException.class, () -> Target.from(System.getenv(), "master", "runtime"));
        assertThrows(IllegalArgumentException.class, () -> Target.from(Map.of("ENG002C_ENV", "production"), "ingest", "runtime"));
        var env = new HashMap<>(System.getenv());
        String key = "ENG002C_INGEST_URL";
        env.put(key, env.get(key).replace("sanitation_ingest", "sanitation_business"));
        assertThrows(IllegalArgumentException.class, () -> Target.from(env, "ingest", "runtime"));
        env.put(key, "jdbc:mysql://127.0.0.1:1/sanitation_ingest");
        assertThrows(SQLException.class, () -> Target.from(env, "ingest", "runtime").connect());
        var alternate = new HashMap<>(System.getenv());
        alternate.put("ENG002C_INGEST_UUID", "00000000-0000-0000-0000-000000000000");
        assertThrows(SQLException.class, () -> Target.from(alternate, "ingest", "runtime").connect());
        try (var foreign = Target.from(System.getenv(), "business", "runtime").connect()) {
            assertThrows(SQLException.class, () -> Target.from(System.getenv(), "ingest", "runtime").verify(foreign));
        }
    }

    @Test
    void wrongMapperAndCrossDatabaseWritesAreRejectedAndThreadIsReusable() throws Exception {
        var one = BANK.get("ingest");
        var two = BANK.get("track");
        var mapper = two.session.getMapper(TrackMapper.class);
        var worker = Executors.newSingleThreadExecutor();
        try {
            worker.submit(() -> {
                assertThrows(RuntimeException.class, () -> one.tx.executeWithoutResult(s -> mapper.insert(20)));
                assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,
                        () -> one.tx.executeWithoutResult(s -> two.tx.executeWithoutResult(inner -> mapper.insert(20))));
                var requiresNew = new TransactionTemplate(two.manager);
                requiresNew.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,
                        () -> one.tx.executeWithoutResult(s -> requiresNew.executeWithoutResult(inner -> mapper.insert(20))));
                assertEquals(0, count(two, "probe_point", 20));
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty());
                two.tx.executeWithoutResult(s -> assertEquals(1, mapper.insert(20)));
                assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty());
            }).get(10, TimeUnit.SECONDS);
        } finally {
            worker.shutdownNow();
        }
        assertEquals(1, count(two, "probe_point", 20));
    }

    @Test
    void ingestAndBusinessCommitAndRollBackBeforeSimulatedAcks() {
        exercise("ingest", List.of("probe_raw", "probe_outbox"), 30);
        exercise("business", List.of("probe_inbox", "probe_record", "probe_outbox"), 30);
    }

    private static void exercise(String domain, List<String> tables, long key) {
        var local = BANK.get(domain);
        var ack = new java.util.ArrayList<Long>();
        local.tx.executeWithoutResult(status -> {
            tables.forEach(t -> insert(local, t, key));
            assertTrue(ack.isEmpty());
        });
        for (String table : tables) assertEquals(1, count(local, table, key));
        ack.add(key);
        for (int n = 0; n < tables.size(); n++) {
            int failAt = n;
            long rolledBack = key + n + 1;
            assertThrows(IllegalStateException.class, () -> local.tx.executeWithoutResult(status -> {
                for (int i = 0; i <= failAt; i++) insert(local, tables.get(i), rolledBack);
                throw new IllegalStateException("synthetic failure");
            }));
            for (String table : tables) assertEquals(0, count(local, table, rolledBack));
        }
        if ("business".equals(domain)) {
            assertThrows(DuplicateKeyException.class, () -> local.tx.executeWithoutResult(status ->
                    tables.forEach(t -> insert(local, t, key))));
            for (String table : tables) assertEquals(1, count(local, table, key));
        }
        assertEquals(List.of(key), ack);
    }

    @Test
    void concurrentUniqueKeyProducesOneIngestPair() throws Exception {
        var local = BANK.get("ingest");
        var start = new CountDownLatch(1);
        Callable<Boolean> attempt = () -> {
            start.await();
            try {
                local.tx.executeWithoutResult(s -> {
                    insert(local, "probe_raw", 40);
                    insert(local, "probe_outbox", 40);
                });
                return true;
            } catch (DuplicateKeyException expected) {
                return false;
            }
        };
        var workers = Executors.newFixedThreadPool(2);
        try {
            var a = workers.submit(attempt);
            var b = workers.submit(attempt);
            start.countDown();
            assertNotEquals(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
        } finally {
            workers.shutdownNow();
        }
        assertEquals(1, count(local, "probe_raw", 40));
        assertEquals(1, count(local, "probe_outbox", 40));
    }

    @Test
    void rolesRejectCrossSchemaDdlAndRawMutation() throws SQLException {
        for (String domain : DOMAINS) {
            try (var connection = Target.from(System.getenv(), domain, "runtime").connect();
                 var statement = connection.createStatement()) {
                denied(() -> statement.execute("CREATE TABLE probe_forbidden(id INT)"));
                String foreign = "ingest".equals(domain) ? "sanitation_business.probe_record" : "sanitation_ingest.probe_raw";
                denied(() -> statement.execute("SELECT * FROM " + foreign));
                if ("ingest".equals(domain)) {
                    denied(() -> statement.execute("UPDATE probe_raw SET id=1"));
                    denied(() -> statement.execute("DELETE FROM probe_raw"));
                }
            }
        }
        try (var connection = Target.from(System.getenv(), "ingest", "audit").connect();
             var statement = connection.createStatement()) {
            try (var ignored = statement.executeQuery("SELECT COUNT(*) FROM probe_raw")) { assertTrue(ignored.next()); }
            denied(() -> statement.execute("INSERT INTO probe_raw VALUES (50)"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"UTC", "Asia/Shanghai"})
    void precisionRoundTripsAcrossJvmZones(String zone) throws Exception {
        TimeZone prior = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone(zone));
        try {
            long id = "UTC".equals(zone) ? 100 : 200;
            var local = BANK.get("ingest");
            var mapper = local.session.getMapper(PrecisionMapper.class);
            var instant = Instant.parse("2026-11-26T16:59:59.987Z");
            var utc = LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
            for (long grams : new long[]{Integer.MIN_VALUE, Integer.MAX_VALUE, 0, -999999999999L, 999999999999L, Long.MIN_VALUE, Long.MAX_VALUE}) {
                // For BIGINT boundaries use independent formal decimal evidence, since conversion would overflow DECIMAL.
                long formal = grams == Long.MIN_VALUE || grams == Long.MAX_VALUE ? 0 : grams;
                final long row = id++;
                var kg = BigDecimal.valueOf(formal, 3);
                local.tx.executeWithoutResult(s -> mapper.insert(row, utc, grams, kg));
                assertEquals(instant, mapper.instant(row).toInstant(ZoneOffset.UTC));
                try (var c = Target.from(System.getenv(), "ingest", "runtime").connect();
                     var p = c.prepareStatement("SELECT recorded_utc, grams, kilos FROM probe_precision WHERE id=?")) {
                    p.setLong(1, row);
                    try (var r = p.executeQuery()) {
                        assertTrue(r.next());
                        assertEquals(instant, r.getObject(1, LocalDateTime.class).toInstant(ZoneOffset.UTC));
                        assertEquals(grams, r.getLong(2));
                        assertEquals(kg, r.getBigDecimal(3));
                    }
                }
            }
            try (var c = Target.from(System.getenv(), "ingest", "runtime").connect();
                 var p = c.prepareStatement("INSERT INTO probe_precision VALUES (?, ?, ?, ?)")) {
                p.setLong(1, id);
                p.setObject(2, utc, JDBCType.TIMESTAMP);
                p.setLong(3, 1000000000000L);
                p.setBigDecimal(4, BigDecimal.valueOf(1000000000000L, 3));
                assertEquals(1264, assertThrows(SQLException.class, p::executeUpdate).getErrorCode());
            }
            var api = new ObjectMapper().registerModule(new JavaTimeModule())
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
            assertEquals("{\"recordedAt\":\"2026-11-26T16:59:59.987Z\"}", api.writeValueAsString(new ApiTimestamp(instant)));
        } finally {
            TimeZone.setDefault(prior);
        }
    }

    @Test
    void trackCanMoveToIndependentMySqlProcess() throws SQLException {
        var original = Target.from(System.getenv(), "track", "runtime");
        var moved = new HashMap<>(System.getenv());
        moved.put("ENG002C_TRACK_URL", moved.get("ENG002C_ALT_TRACK_URL"));
        moved.put("ENG002C_TRACK_UUID", moved.get("ENG002C_ALT_TRACK_UUID"));
        var changed = Target.from(moved, "track", "runtime");
        assertNotEquals(original.uuid(), changed.uuid());
        try (var a = original.connect(); var b = changed.connect()) {
            assertEquals(a.getCatalog(), b.getCatalog());
            try (var write = b.createStatement()) { assertEquals(1, write.executeUpdate("INSERT INTO probe_point VALUES (90)")); }
            assertEquals(0, count(BANK.get("track"), "probe_point", 90));
        }
    }

    private static void denied(org.junit.jupiter.api.function.Executable action) {
        assertEquals(1142, assertThrows(SQLException.class, action).getErrorCode());
    }

    private static void insert(Local local, String table, long id) {
        local.jdbc.update("INSERT INTO " + table + "(id) VALUES (?)", id);
    }

    private static int count(Local local, String table, long id) {
        return local.jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE id=?", Integer.class, id);
    }
}
