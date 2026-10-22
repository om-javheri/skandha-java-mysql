package site;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The checks on messages and page views, the same as the site's other servers. */
public final class Rules {
    private Rules() {}

    public record Field(String label, String value) {}

    /** A message that passed every check, or why it was refused. */
    public sealed interface Result permits Message, Refusal {}

    public record Message(String formId, String page, List<Field> fields) implements Result {}

    public record Refusal(int status, String message) implements Result {}

    public record View(String path, String referrer) {}

    /** Whitespace as JavaScript trims it. */
    private static final Pattern EDGES = Pattern.compile("^[\\s\\x{00A0}\\x{1680}\\x{2000}-\\x{200A}\\x{2028}\\x{2029}\\x{202F}\\x{205F}\\x{3000}\\x{FEFF}]+|[\\s\\x{00A0}\\x{1680}\\x{2000}-\\x{200A}\\x{2028}\\x{2029}\\x{202F}\\x{205F}\\x{3000}\\x{FEFF}]+$");
    private static final Set<String> FIELD_KEYS = Set.of("label", "value");

    public static String trim(String s) {
        return EDGES.matcher(s).replaceAll("");
    }

    private static JsonNode parse(JsonMapper json, String body) {
        try {
            return json.readTree(body);
        } catch (JacksonException e) {
            return null;
        }
    }

    /** The page a message came from: one of the site's pages, else the home page. */
    public static String pagePath(List<String> pages, String page) {
        String path = (page == null ? "" : page).split("[?#]", -1)[0];
        if (path.endsWith("/index.html")) path = path.substring(0, path.length() - "index.html".length());
        if (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);
        return pages.contains(path) ? path : "/";
    }

    /** Checks a form message (the body as sent: text/plain or JSON) against the form and the rules. */
    public static Result checkMessage(ServerConfig config, JsonMapper json, String formId, String body) {
        ServerConfig.Limits rules = config.rules();
        Refusal unreadable = new Refusal(400, config.message("unreadable"));
        JsonNode data = parse(json, body);
        if (data == null || !data.isObject()) return unreadable;
        JsonNode fields = data.get("fields");
        if (fields == null || !fields.isArray() || fields.size() < 1 || fields.size() > rules.maxFields()) return unreadable;
        List<Field> sent = new ArrayList<>();
        for (JsonNode f : fields) {
            if (!f.isObject() || !FIELD_KEYS.containsAll(f.propertyNames())) return unreadable;
            JsonNode label = f.get("label");
            JsonNode value = f.get("value");
            if (label == null || value == null || !label.isString() || !value.isString()) return unreadable;
            // Java counts string length in UTF-16 units, as JavaScript does.
            if (label.stringValue().length() > rules.maxLabel() || value.stringValue().length() > rules.maxValue()) return unreadable;
            sent.add(new Field(label.stringValue(), value.stringValue()));
        }
        JsonNode page = data.get("page");
        if (page != null && (!page.isString() || page.stringValue().length() > rules.maxPage())) return unreadable;

        Integer formFields = config.forms().get(formId);
        if (formFields == null) return new Refusal(404, config.message("unknownForm"));
        if (sent.size() > formFields) return new Refusal(400, config.message("tooManyFields"));
        if (sent.stream().allMatch(f -> trim(f.value()).isEmpty())) return new Refusal(400, config.message("empty"));
        if (sent.stream().mapToInt(f -> f.label().length() + f.value().length()).sum() > rules.maxTotal()) return new Refusal(400, config.message("tooLong"));
        List<Field> kept = sent.stream().map(f -> new Field(trim(f.label()), trim(f.value()))).toList();
        return new Message(formId, pagePath(config.pages(), page == null ? null : page.stringValue()), kept);
    }

    /** A page view to count: the page's path, and the referring site's host when it is another site. */
    public static View readView(ServerConfig config, JsonMapper json, String body, String ownHost) {
        JsonNode data = parse(json, body);
        if (data == null || !data.isObject() || data.get("path") == null || !data.get("path").isString()) return null;
        String path = data.get("path").stringValue().split("[?#]", -1)[0];
        if (!config.pages().contains(path)) return null;
        String referrer = null;
        JsonNode ref = data.get("referrer");
        if (ref != null && ref.isString() && !ref.stringValue().isEmpty()) {
            try {
                URI uri = new URI(ref.stringValue());
                String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
                String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
                if ((scheme.equals("http") || scheme.equals("https")) && !host.isEmpty() && !host.equals(ownHost.toLowerCase()) && host.length() <= 253) referrer = host;
            } catch (Exception e) {
                // Not an address: no referrer.
            }
        }
        return new View(path, referrer);
    }
}
