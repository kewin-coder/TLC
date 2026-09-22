import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Stores TLC messages in the same configured database as account data. */
public final class MessageStore {
    private final String jdbcUrl;

    public MessageStore(String jdbcUrl) throws SQLException {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            throw new IllegalArgumentException("jdbcUrl must not be blank");
        }
        this.jdbcUrl = jdbcUrl;
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS messages (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "sender TEXT NOT NULL," +
                    "text TEXT NOT NULL," +
                    "sent_at TEXT NOT NULL)");
        }
    }

    private Connection open() throws SQLException {
        return DriverManager.getConnection(jdbcUrl);
    }

    public synchronized void add(String sender, String text, String sentAt, int maxMessages)
            throws SQLException {
        if (sender == null || sender.isBlank()) {
            throw new IllegalArgumentException("sender must not be blank");
        }
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text must not be blank");
        }
        if (sentAt == null || sentAt.isBlank()) {
            throw new IllegalArgumentException("sentAt must not be blank");
        }
        if (maxMessages < 1) {
            throw new IllegalArgumentException("maxMessages must be at least 1");
        }

        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO messages(sender, text, sent_at) VALUES(?, ?, ?)")) {
                    insert.setString(1, sender);
                    insert.setString(2, text);
                    insert.setString(3, sentAt);
                    insert.executeUpdate();
                }
                try (PreparedStatement trim = connection.prepareStatement(
                        "DELETE FROM messages WHERE id NOT IN " +
                        "(SELECT id FROM messages ORDER BY id DESC LIMIT ?)")) {
                    trim.setInt(1, maxMessages);
                    trim.executeUpdate();
                }
                connection.commit();
            } catch (SQLException exception) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackException) {
                    exception.addSuppressed(rollbackException);
                }
                throw exception;
            }
        }
    }

    public synchronized List<Map<String, String>> latest(int maxMessages) throws SQLException {
        if (maxMessages < 1) {
            throw new IllegalArgumentException("maxMessages must be at least 1");
        }

        List<Map<String, String>> messages = new ArrayList<>();
        try (Connection connection = open();
             PreparedStatement query = connection.prepareStatement(
                     "SELECT sender, text, sent_at FROM messages ORDER BY id DESC LIMIT ?")) {
            query.setInt(1, maxMessages);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    Map<String, String> message = new LinkedHashMap<>();
                    message.put("sender", rows.getString("sender"));
                    message.put("text", rows.getString("text"));
                    message.put("time", rows.getString("sent_at"));
                    messages.add(message);
                }
            }
        }
        Collections.reverse(messages);
        return messages;
    }
}
