package cn.iocoder.sanitation.tools;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

record Target(String domain, String url, String uuid, String account, String password) {
    static final Set<String> DOMAINS = Set.of("ingest", "business", "track");
    static final String MARKER = "eng002c02-isolated";

    static Target from(Map<String, String> env, String domain, String role) {
        if (!DOMAINS.contains(domain) || !"isolated".equals(env.get("ENG002C_ENV"))) {
            throw new IllegalArgumentException("Choose an approved domain and ENG002C_ENV=isolated");
        }
        String prefix = "ENG002C_" + domain.toUpperCase(Locale.ROOT) + "_";
        String base = env.getOrDefault(prefix + "URL", "");
        var match = Pattern.compile("jdbc:mysql://127\\.0\\.0\\.1:([1-9][0-9]{0,4})/sanitation_" + domain).matcher(base);
        if (!match.matches() || Integer.parseInt(match.group(1)) > 65535 || "3306".equals(match.group(1))) {
            throw new IllegalArgumentException("Expected loopback lab URL and exact sanitation_" + domain + " catalog");
        }
        String uuid = env.getOrDefault(prefix + "UUID", "");
        String password = env.getOrDefault(prefix + role.toUpperCase(Locale.ROOT) + "_PASSWORD", "");
        if (!uuid.matches("[0-9a-f-]{36}") || !password.matches("[0-9a-f]{32,128}")) {
            throw new IllegalArgumentException("Missing isolated server identity or generated account secret");
        }
        return new Target(domain, base + "?sslMode=DISABLED&allowPublicKeyRetrieval=true&connectTimeout=3000"
                + "&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&preserveInstants=true",
                uuid, "san_" + domain + "_" + role, password);
    }

    @Override
    public String toString() {
        return schema() + " [" + account + "]";
    }

    String schema() {
        return "sanitation_" + domain;
    }

    Connection connect() throws SQLException {
        Connection connection = DriverManager.getConnection(url, account, password);
        try {
            verify(connection);
            return connection;
        } catch (SQLException | RuntimeException e) {
            connection.close();
            throw e;
        }
    }

    void verify(Connection connection) throws SQLException {
        try (var statement = connection.createStatement();
             var row = statement.executeQuery("SELECT DATABASE(), VERSION(), @@report_host, @@server_uuid, CURRENT_USER(), @@session.time_zone")) {
            if (!row.next() || !schema().equals(row.getString(1)) || !row.getString(2).startsWith("8.4.")
                    || !MARKER.equals(row.getString(3)) || !uuid.equals(row.getString(4))
                    || !row.getString(5).startsWith(account + "@") || !"+00:00".equals(row.getString(6))) {
                throw new SQLException("Catalog, server identity, account or UTC session does not match the lab target");
            }
        }
    }
}
