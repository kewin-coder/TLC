import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Server dependency layer for chat storage and paginated history. */
public final class ServerDependencies2 {
    private final MessageStore messageStore;
    private final MessageHistory history;

    public ServerDependencies2(String jdbcUrl) throws SQLException {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            throw new IllegalArgumentException("jdbcUrl must not be blank");
        }
        this.messageStore = new MessageStore(jdbcUrl);
        this.history = new MessageHistory(messageStore);
    }

    public List<Map<String, String>> latestMessages(int limit) throws SQLException {
        return history.latest(limit);
    }

    public List<Map<String, String>> olderMessages(long beforeId, int limit)
            throws SQLException {
        return history.olderThan(beforeId, limit);
    }

    public void saveMessage(String sender, String text) throws SQLException {
        if (sender == null || sender.isBlank()) {
            throw new IllegalArgumentException("sender must not be blank");
        }
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text must not be blank");
        }
        messageStore.add(sender, text, Instant.now().toString(), 0);
    }
}
