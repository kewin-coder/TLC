import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/** Small history service that keeps pagination logic out of the HTTP server. */
public final class MessageHistory {
    private final MessageStore store;

    public MessageHistory(MessageStore store) {
        if (store == null) {
            throw new IllegalArgumentException("store must not be null");
        }
        this.store = store;
    }

    public List<Map<String, String>> latest(int limit) throws SQLException {
        return store.latest(limit);
    }

    public List<Map<String, String>> olderThan(long beforeId, int limit)
            throws SQLException {
        return store.olderThan(beforeId, limit);
    }
}
