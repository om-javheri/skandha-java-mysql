package site;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/**
 * The site's own server (docs/39 in UniversalIDE): the site from site/, its forms' messages and its
 * page views. Run with PORT, DATA_DIR and ADMIN_KEY (see README.md).
 */
@RestController
public class SiteController {
    private static final Map<String, String> CONTENT_TYPES = Map.ofEntries(
            Map.entry("html", "text/html; charset=utf-8"),
            Map.entry("css", "text/css; charset=utf-8"),
            Map.entry("js", "text/javascript; charset=utf-8"),
            Map.entry("mjs", "text/javascript; charset=utf-8"),
            Map.entry("json", "application/json"),
            Map.entry("txt", "text/plain; charset=utf-8"),
            Map.entry("xml", "application/xml"),
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("gif", "image/gif"),
            Map.entry("webp", "image/webp"),
            Map.entry("avif", "image/avif"),
            Map.entry("svg", "image/svg+xml"),
            Map.entry("ico", "image/x-icon"),
            Map.entry("woff", "font/woff"),
            Map.entry("woff2", "font/woff2"),
            Map.entry("ttf", "font/ttf"),
            Map.entry("otf", "font/otf"),
            Map.entry("mp4", "video/mp4"),
            Map.entry("webm", "video/webm"),
            Map.entry("mp3", "audio/mpeg"),
            Map.entry("pdf", "application/pdf"));

    private final JsonMapper json = JsonMapper.builder().build();
    private final ServerConfig config;
    private final Path site;
    private final String notFoundPage;
    private final Path dataDir;
    private Store store;
    private final Pattern bots;
    private final Limiter messageLimit;
    private final Limiter viewLimit;
    private final String adminKey = env("ADMIN_KEY", "");
    private final boolean trustProxy = "1".equals(env("TRUST_PROXY", ""));

    public SiteController() throws Exception {
        config = ServerConfig.load(json);
        Path root = ServerConfig.root();
        site = root.resolve("site").toRealPath();
        notFoundPage = Files.readString(root.resolve("not-found.html"));
        dataDir = Path.of(env("DATA_DIR", root.resolve("data").toString()));
        bots = Pattern.compile(config.botPattern(), Pattern.CASE_INSENSITIVE);
        messageLimit = new Limiter(config.rules().messagesPerMinute());
        viewLimit = new Limiter(config.rules().viewsPerMinute());
    }

    /** Opened on the first request that needs it (null: the database server isn't set up); tried again after a failure. */
    private synchronized Store store() throws Exception {
        if (store == null) store = Stores.open(dataDir, config.rules().keepMessages(), json);
        return store;
    }

    private ResponseEntity<Object> noDatabase() {
        return answer(503, Map.of("message", "This site can’t take messages yet: its database isn’t set up (DATABASE_URL)."));
    }

    /** The sending limits: kept by a database server when there is one, else in memory. */
    private boolean allow(Store store, String kind, String visitor) throws Exception {
        boolean message = kind.equals("message");
        String bucket = kind + ":" + java.util.HexFormat.of().formatHex(sha256(visitor)).substring(0, 32);
        Boolean kept = store.allow(bucket, message ? config.rules().messagesPerMinute() : config.rules().viewsPerMinute());
        return kept != null ? kept : (message ? messageLimit : viewLimit).allow(visitor);
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isEmpty() ? fallback : value;
    }

    private static ResponseEntity<Object> answer(int status, Object body) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body);
    }

    private ResponseEntity<Object> refuse(int status, String key) {
        return answer(status, Map.of("message", config.message(key)));
    }

    /** The visitor's address, for the limits only (never kept). */
    private String visitor(HttpServletRequest req) {
        String forwarded = trustProxy && req.getHeader("X-Forwarded-For") != null ? req.getHeader("X-Forwarded-For").split(",")[0].trim() : "";
        return forwarded.isEmpty() ? req.getRemoteAddr() : forwarded;
    }

    /** The request body as text, or null when larger than the rules allow. */
    private String body(HttpServletRequest req) throws IOException {
        int max = config.rules().maxBody();
        if (req.getContentLengthLong() > max) return null;
        try (InputStream in = req.getInputStream()) {
            byte[] bytes = in.readNBytes(max + 1);
            return bytes.length > max ? null : new String(bytes, StandardCharsets.UTF_8);
        }
    }

    @PostMapping("/api/forms/{id}")
    public ResponseEntity<Object> takeMessage(@PathVariable String id, HttpServletRequest req, HttpServletResponse res) throws Exception {
        // The answer may be read by the page wherever it is shown.
        res.setHeader("Access-Control-Allow-Origin", "*");
        String body = body(req);
        if (body == null) return refuse(413, "tooLong");
        Rules.Result result = Rules.checkMessage(config, json, id, body);
        if (result instanceof Rules.Refusal r) return answer(r.status(), Map.of("message", r.message()));
        Store store = store();
        if (store == null) return noDatabase();
        if (!allow(store, "message", visitor(req))) return refuse(429, "tooMany");
        store.addMessage((Rules.Message) result);
        return answer(200, Map.of("ok", true));
    }

    @PostMapping("/api/views")
    public ResponseEntity<Void> countView(HttpServletRequest req) throws Exception {
        String agent = req.getHeader("User-Agent") == null ? "" : req.getHeader("User-Agent");
        String body = body(req);
        try {
            Store store = !agent.isEmpty() && !bots.matcher(agent).find() && body != null ? store() : null;
            if (store != null && allow(store, "view", visitor(req))) {
                Rules.View view = Rules.readView(config, json, body, req.getServerName());
                if (view != null) store.countView(view.path(), view.referrer());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        // A beacon never fails.
        return ResponseEntity.noContent().build();
    }

    private static final Pattern BEARER = Pattern.compile("^Bearer (.+)$");

    /** Null when the admin key was sent, else the refusal. */
    private ResponseEntity<Object> notAdmin(HttpServletRequest req) throws Exception {
        if (adminKey.isEmpty()) return refuse(404, "notFound");
        Matcher m = BEARER.matcher(req.getHeader("Authorization") == null ? "" : req.getHeader("Authorization"));
        boolean ok = m.matches() && MessageDigest.isEqual(sha256(m.group(1)), sha256(adminKey));
        return ok ? null : refuse(401, "unauthorized");
    }

    private static byte[] sha256(String s) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
    }

    @GetMapping("/api/admin/messages")
    public ResponseEntity<Object> messages(HttpServletRequest req) throws Exception {
        ResponseEntity<Object> refused = notAdmin(req);
        if (refused != null) return refused;
        Store store = store();
        return store == null ? noDatabase() : answer(200, Map.of("messages", store.messages(200)));
    }

    @DeleteMapping("/api/admin/messages/{id}")
    public ResponseEntity<Object> deleteMessage(@PathVariable String id, HttpServletRequest req) throws Exception {
        ResponseEntity<Object> refused = notAdmin(req);
        if (refused != null) return refused;
        long n;
        try {
            n = Long.parseLong(id);
        } catch (NumberFormatException e) {
            return refuse(404, "notFound");
        }
        Store store = store();
        if (store == null) return noDatabase();
        return store.deleteMessage(n) ? ResponseEntity.noContent().build() : refuse(404, "notFound");
    }

    @GetMapping("/api/admin/stats")
    public ResponseEntity<Object> stats(HttpServletRequest req) throws Exception {
        ResponseEntity<Object> refused = notAdmin(req);
        if (refused != null) return refused;
        int days = 30;
        try {
            int asked = Integer.parseInt(req.getParameter("days") == null ? "" : req.getParameter("days"));
            if (asked >= 1 && asked <= 366) days = asked;
        } catch (NumberFormatException e) {
            // Not a number of days: the last 30.
        }
        Store store = store();
        return store == null ? noDatabase() : answer(200, store.stats(days));
    }

    private void notFound(HttpServletResponse res) throws IOException {
        res.setStatus(404);
        res.setContentType("text/html; charset=utf-8");
        res.getOutputStream().write(notFoundPage.getBytes(StandardCharsets.UTF_8));
    }

    /** Everything else: an unknown API address, or a file of the site. */
    @RequestMapping("/**")
    public ResponseEntity<Object> file(HttpServletRequest req, HttpServletResponse res) throws IOException {
        String path = URLDecoder.decode(req.getRequestURI().replace("+", "%2B"), StandardCharsets.UTF_8);
        if (path.startsWith("/api/")) return refuse(404, "notFound");
        String method = req.getMethod();
        boolean ok = (method.equals("GET") || method.equals("HEAD")) && !path.contains("\0") && !path.contains("\\");
        for (String segment : path.split("/")) if (segment.equals("..") || segment.startsWith(".")) ok = false;
        Path file = ok ? site.resolve(path.substring(1)).normalize() : null;
        if (file == null || !file.startsWith(site)) {
            notFound(res);
            return null;
        }
        if (!path.endsWith("/") && Files.isDirectory(file) && Files.isRegularFile(file.resolve("index.html"))) {
            String query = req.getQueryString();
            res.setStatus(301);
            res.setHeader("Location", req.getRequestURI() + "/" + (query == null ? "" : "?" + query));
            return null;
        }
        if (path.endsWith("/")) file = file.resolve("index.html");
        if (!Files.isRegularFile(file) || !file.toRealPath().startsWith(site)) {
            notFound(res);
            return null;
        }
        String name = file.getFileName().toString();
        String extension = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase() : "";
        res.setContentType(CONTENT_TYPES.getOrDefault(extension, "application/octet-stream"));
        res.setContentLengthLong(Files.size(file));
        // Exact copies of imported pages run only sandboxed.
        if (path.startsWith("/_embeds/")) res.setHeader("Content-Security-Policy", config.embedCsp());
        if (method.equals("GET")) Files.copy(file, res.getOutputStream());
        return null;
    }
}
