package cn.iocoder.yudao.module.iot.gateway.service.device.spool;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Locale;

/**
 * SQLite v0 历史结构校验。仅允许空库初始化，同名表不等于兼容。
 */
final class SpoolStartupValidator {

    private static final String TABLE = "iot_device_message_spool";
    private static final String INDEX = "idx_iot_device_message_spool_time";

    private static final String TABLE_DDL = "CREATE TABLE " + TABLE + " ("
            + "id INTEGER PRIMARY KEY AUTOINCREMENT, spool_time INTEGER NOT NULL, "
            + "size INTEGER NOT NULL, message TEXT NOT NULL)";
    private static final String INDEX_DDL = "CREATE INDEX " + INDEX + " ON " + TABLE + " (spool_time)";
    private static final String SEQUENCE_DDL = "CREATE TABLE sqlite_sequence(name,seq)";

    private SpoolStartupValidator() {
    }

    static boolean validate(JdbcTemplate jdbc) {
        if (!Integer.valueOf(0).equals(jdbc.queryForObject("PRAGMA user_version", Integer.class))) {
            throw new IllegalStateException("离线暂存 SQLite Schema 版本未知");
        }
        List<String> checks = jdbc.queryForList("PRAGMA quick_check", String.class);
        if (!checks.equals(List.of("ok"))) {
            throw new IllegalStateException("离线暂存 SQLite 完整性检查失败：" + checks);
        }
        String journal = jdbc.queryForObject("PRAGMA journal_mode", String.class);
        List<String> schema = jdbc.queryForList("SELECT sql FROM sqlite_master ORDER BY type,name", String.class);
        if (schema.isEmpty()) {
            return false;
        }
        List<String> normalized = schema.stream().map(SpoolStartupValidator::normalize).toList();
        if (!normalized.equals(List.of(normalize(INDEX_DDL), normalize(TABLE_DDL), normalize(SEQUENCE_DDL)))) {
            throw new IllegalStateException("离线暂存 SQLite Schema 与历史结构不匹配");
        }
        List<String> columns = jdbc.query("PRAGMA table_info(" + TABLE + ")",
                (rs, row) -> rs.getString("name") + ":" + rs.getString("type").toUpperCase(Locale.ROOT));
        List<String> indexes = jdbc.query("PRAGMA index_list(" + TABLE + ")", (rs, row) -> rs.getString("name"));
        List<String> indexColumns = jdbc.query("PRAGMA index_info(" + INDEX + ")",
                (rs, row) -> rs.getString("name"));
        if (!columns.equals(List.of("id:INTEGER", "spool_time:INTEGER", "size:INTEGER", "message:TEXT"))
                || !indexes.equals(List.of(INDEX)) || !indexColumns.equals(List.of("spool_time"))
                || !"wal".equalsIgnoreCase(journal)) {
            throw new IllegalStateException("离线暂存 SQLite 字段、索引或 WAL 模式不兼容");
        }
        return true;
    }

    static void initialize(JdbcTemplate jdbc) {
        jdbc.execute("BEGIN");
        jdbc.execute(TABLE_DDL);
        jdbc.execute(INDEX_DDL);
        jdbc.execute("COMMIT");
    }

    private static String normalize(String sql) {
        return sql == null ? "" : sql.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

}
