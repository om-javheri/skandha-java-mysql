package site;

import java.nio.file.Path;
import tools.jackson.databind.json.JsonMapper;

/** Opens the site's store (docs/57). */
public final class Stores {
    private Stores() {}

    /** The database server's address: DATABASE_URL (or MONGODB_URI, as the Vercel MongoDB Atlas integration names it). */
    static String databaseUrl() {
        String url = System.getenv("DATABASE_URL");
        if (url == null || url.isEmpty()) url = System.getenv("MONGODB_URI");
        return url == null ? "" : url;
    }

    /** Null when the site's database server isn't set up. */
    public static Store open(Path dataDir, int keepMessages, JsonMapper json) throws Exception {
        String url = databaseUrl();
        return url.isEmpty() ? null : new MysqlStore(url, keepMessages, json);
    }
}
