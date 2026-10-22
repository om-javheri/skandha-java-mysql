package site;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;
import tools.jackson.databind.json.JsonMapper;

/** Form messages and daily page-view counters in MySQL (DATABASE_URL). Nothing about visitors is kept. */
public final class MysqlStore implements Store {
    private static final String[] SCHEMA = {
        "CREATE TABLE IF NOT EXISTS form_messages (id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, form_id VARCHAR(200) NOT NULL, page VARCHAR(300) NOT NULL, fields LONGTEXT NOT NULL, created_at VARCHAR(30) NOT NULL) DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin",
        "CREATE TABLE IF NOT EXISTS daily_counts (day CHAR(10) NOT NULL, kind VARCHAR(20) NOT NULL, `key` VARCHAR(255) NOT NULL, count BIGINT NOT NULL, PRIMARY KEY (day, kind, `key`)) DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin",
        "CREATE TABLE IF NOT EXISTS rate_hits (bucket VARCHAR(100) NOT NULL, at DOUBLE NOT NULL, KEY rate_hits_bucket (bucket, at)) DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin",
    };
    private static final DateTimeFormatter CREATED = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);
    private static final Pattern LOCAL = Pattern.compile("^(localhost|127\\.\\d+\\.\\d+\\.\\d+|::1|\\[::1\\])$");

    private final String jdbcUrl;
    private final Properties login = new Properties();
    private final int keepMessages;
    private final JsonMapper json;
    private Connection db;

    /**
     * mysql://user:password@host:3306/site. Hosted MySQL (TiDB Cloud, Aiven…) is reached over TLS,
     * checked against the certificate; it is off when the server is on this machine, or with ?ssl=false.
     */
    public MysqlStore(String url, int keepMessages, JsonMapper json) throws Exception {
        URI uri = URI.create(url);
        String query = uri.getRawQuery() == null ? "" : uri.getRawQuery();
        boolean tls = query.contains("ssl=true") || (!query.contains("ssl=false") && !LOCAL.matcher(uri.getHost()).matches());
        String[] user = (uri.getRawUserInfo() == null ? "" : uri.getRawUserInfo()).split(":", 2);
        login.setProperty("user", URLDecoder.decode(user[0], StandardCharsets.UTF_8));
        if (user.length > 1) login.setProperty("password", URLDecoder.decode(user[1], StandardCharsets.UTF_8));
        login.setProperty("sslMode", tls ? "VERIFY_IDENTITY" : "DISABLED");
        // Without TLS, MySQL 8's default sign-in needs the server's public key.
        if (!tls) login.setProperty("allowPublicKeyRetrieval", "true");
        login.setProperty("characterEncoding", "UTF-8");
        jdbcUrl = "jdbc:mysql://" + uri.getHost() + ":" + (uri.getPort() > 0 ? uri.getPort() : 3306) + uri.getRawPath();
        this.keepMessages = keepMessages;
        this.json = json;
        try (Statement s = connection().createStatement()) {
            for (String sql : SCHEMA) s.execute(sql);
        }
    }

    /** One connection, opened again when the server has closed it. */
    private Connection connection() throws SQLException {
        if (db == null || !db.isValid(2)) db = DriverManager.getConnection(jdbcUrl, login);
        return db;
    }

    private static String today(int offset) {
        return LocalDate.now(ZoneOffset.UTC).plusDays(offset).toString();
    }

    @Override
    public synchronized void addMessage(Rules.Message m) throws Exception {
        try (PreparedStatement add = connection().prepareStatement("INSERT INTO form_messages (form_id, page, fields, created_at) VALUES (?, ?, ?, ?)");
             // MySQL reads its own table in a DELETE only through a derived table.
             PreparedStatement trim = connection().prepareStatement("DELETE FROM form_messages WHERE id <= (SELECT id FROM (SELECT id FROM form_messages ORDER BY id DESC LIMIT 1 OFFSET ?) AS oldest)")) {
            add.setString(1, m.formId());
            add.setString(2, m.page());
            add.setString(3, json.writeValueAsString(m.fields()));
            add.setString(4, CREATED.format(Instant.now().truncatedTo(ChronoUnit.MILLIS)));
            add.executeUpdate();
            trim.setInt(1, keepMessages);
            trim.executeUpdate();
        }
    }

    @Override
    public synchronized List<Map<String, Object>> messages(int limit) throws Exception {
        List<Map<String, Object>> out = new ArrayList<>();
        try (PreparedStatement q = connection().prepareStatement("SELECT id, form_id, page, fields, created_at FROM form_messages ORDER BY id DESC LIMIT ?")) {
            q.setInt(1, limit);
            try (ResultSet r = q.executeQuery()) {
                while (r.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", r.getLong("id"));
                    m.put("formId", r.getString("form_id"));
                    m.put("page", r.getString("page"));
                    m.put("fields", json.readTree(r.getString("fields")));
                    m.put("createdAt", r.getString("created_at"));
                    out.add(m);
                }
            }
        }
        return out;
    }

    @Override
    public synchronized boolean deleteMessage(long id) throws Exception {
        try (PreparedStatement q = connection().prepareStatement("DELETE FROM form_messages WHERE id = ?")) {
            q.setLong(1, id);
            return q.executeUpdate() > 0;
        }
    }

    @Override
    public synchronized void countView(String path, String referrer) throws Exception {
        try (PreparedStatement bump = connection().prepareStatement("INSERT INTO daily_counts (day, kind, `key`, count) VALUES (?, ?, ?, 1) ON DUPLICATE KEY UPDATE count = count + 1")) {
            bump.setString(1, today(0));
            bump.setString(2, "view");
            bump.setString(3, path);
            bump.executeUpdate();
            if (referrer != null) {
                bump.setString(2, "referrer");
                bump.setString(3, referrer);
                bump.executeUpdate();
            }
        }
    }

    private List<Map<String, Object>> totals(String kind, String since) throws Exception {
        List<Map<String, Object>> out = new ArrayList<>();
        try (PreparedStatement q = connection().prepareStatement("SELECT `key`, SUM(count) AS count FROM daily_counts WHERE kind = ? AND day >= ? GROUP BY `key` ORDER BY count DESC, `key`")) {
            q.setString(1, kind);
            q.setString(2, since);
            try (ResultSet r = q.executeQuery()) {
                while (r.next()) {
                    Map<String, Object> t = new LinkedHashMap<>();
                    t.put("key", r.getString("key"));
                    t.put("count", r.getLong("count"));
                    out.add(t);
                }
            }
        }
        return out;
    }

    @Override
    public synchronized Map<String, Object> stats(int days) throws Exception {
        String since = today(-(days - 1));
        List<Map<String, Object>> daily = new ArrayList<>();
        try (PreparedStatement q = connection().prepareStatement("SELECT day, SUM(count) AS views FROM daily_counts WHERE kind = 'view' AND day >= ? GROUP BY day ORDER BY day")) {
            q.setString(1, since);
            try (ResultSet r = q.executeQuery()) {
                while (r.next()) {
                    Map<String, Object> d = new LinkedHashMap<>();
                    d.put("day", r.getString("day"));
                    d.put("views", r.getLong("views"));
                    daily.add(d);
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("days", days);
        out.put("daily", daily);
        out.put("pages", totals("view", since));
        out.put("referrers", totals("referrer", since));
        return out;
    }

    @Override
    public synchronized Boolean allow(String bucket, int perMinute) throws Exception {
        double now = System.currentTimeMillis() / 1000.0;
        try (PreparedStatement clear = connection().prepareStatement("DELETE FROM rate_hits WHERE at <= ?");
             PreparedStatement count = connection().prepareStatement("SELECT COUNT(*) FROM rate_hits WHERE bucket = ?");
             PreparedStatement hit = connection().prepareStatement("INSERT INTO rate_hits (bucket, at) VALUES (?, ?)")) {
            clear.setDouble(1, now - 60);
            clear.executeUpdate();
            count.setString(1, bucket);
            try (ResultSet r = count.executeQuery()) {
                r.next();
                if (r.getLong(1) >= perMinute) return false;
            }
            hit.setString(1, bucket);
            hit.setDouble(2, now);
            hit.executeUpdate();
            return true;
        }
    }
}
