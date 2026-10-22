package site;

import java.util.List;
import java.util.Map;

/** Where messages and counters are kept. Nothing about visitors is kept. */
public interface Store {
    /** Keeps a message; only the newest keepMessages are kept. */
    void addMessage(Rules.Message m) throws Exception;

    /** The newest messages first. */
    List<Map<String, Object>> messages(int limit) throws Exception;

    boolean deleteMessage(long id) throws Exception;

    /** Counts a view of a page today, and the site it came from. */
    void countView(String path, String referrer) throws Exception;

    /** The last days days (today included): views a day, by page and by referring site. */
    Map<String, Object> stats(int days) throws Exception;

    /** A database server keeps the sending limits itself, so they hold across instances; null: kept in memory. */
    default Boolean allow(String bucket, int perMinute) throws Exception {
        return null;
    }
}
