package site;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/** What the site's server knows about it: written by UniversalIDE into server.json (docs/39). */
public record ServerConfig(
        /** Forms that send messages here: form id → how many fields it has. */
        Map<String, Integer> forms,
        /** The site's page paths. */
        List<String> pages,
        Limits rules,
        Map<String, String> messages,
        String botPattern,
        String embedCsp,
        /** A database server (mongodb, mysql), or null for SQLite. */
        String database) {

    public record Limits(int maxFields, int maxLabel, int maxValue, int maxTotal, int maxBody, int messagesPerMinute, int viewsPerMinute, int keepMessages, int maxPage) {}

    /** The project folder: server.json, not-found.html and site/ are here (SITE_ROOT, else the working folder). */
    public static Path root() {
        String root = System.getenv("SITE_ROOT");
        return Path.of(root == null || root.isEmpty() ? "" : root).toAbsolutePath().normalize();
    }

    public static ServerConfig load(JsonMapper json) throws IOException {
        return json.readValue(Files.readString(root().resolve("server.json")), ServerConfig.class);
    }

    public String message(String key) {
        return messages.get(key);
    }
}
