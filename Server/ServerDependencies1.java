import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Shared server-side dependency facade for persistent TLC messages.
 *
 * TlcServer can create one instance using the same JDBC URL used by AccountApi,
 * then call latestMessages() for GET /api/messages and saveMessage() for POST.
 */
public final class ServerDependencies1 {
    private final MessageStore messageStore;
    private final int maxMessages;

    public ServerDependencies1(String jdbcUrl, int maxMessages) throws SQLException {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            throw new IllegalArgumentException("jdbcUrl must not be blank");
        }
        if (maxMessages < 1) {
            throw new IllegalArgumentException("maxMessages must be at least 1");
        }
        this.messageStore = new MessageStore(jdbcUrl);
        this.maxMessages = maxMessages;
    }

    public List<Map<String, String>> latestMessages() throws SQLException {
        return messageStore.latest(maxMessages);
    }

    public void saveMessage(String sender, String text) throws SQLException {
        if (sender == null || sender.isBlank()) {
            throw new IllegalArgumentException("sender must not be blank");
        }
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text must not be blank");
        }
        messageStore.add(sender, text, Instant.now().toString(), maxMessages);
    }
}
