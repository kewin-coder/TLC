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

/** Stores encrypted TLC messages in the same configured database as account data. */
public final class MessageStore {
    private static final String ENCRYPTED_PREFIX = "v1:";
    private final String jdbcUrl;
    private final TlcCrypto crypto;

    public MessageStore(String jdbcUrl) throws SQLException {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            throw new IllegalArgumentException("jdbcUrl must not be blank");
        }

        this.jdbcUrl = jdbcUrl;
        this.crypto = new TlcCrypto();

        try (Connection connection = open();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS messages (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "sender TEXT NOT NULL," +
                    "text TEXT NOT NULL," +
                    "sent_at TEXT NOT NULL)");
            statement.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_messages_id ON messages(id)");
        }

        migrateLegacyMessages();
    }

    private Connection open() throws SQLException {
        return DriverManager.getConnection(jdbcUrl);
    }

    public synchronized void add(String sender, String text, String sentAt, int ignoredLimit)
            throws SQLException {
        validate(sender, text, sentAt);

        String encryptedText = ENCRYPTED_PREFIX + crypto.encrypt(text);

        try (Connection connection = open();
             PreparedStatement insert = connection.prepareStatement(
                     "INSERT INTO messages(sender, text, sent_at) VALUES(?, ?, ?)")) {
            insert.setString(1, sender);
            insert.setString(2, encryptedText);
            insert.setString(3, sentAt);
            insert.executeUpdate();
        }
    }

    /** Returns the newest page, ordered oldest-to-newest for display. */
    public synchronized List<Map<String, String>> latest(int limit) throws SQLException {
        validateLimit(limit);
        return queryPage(
                "SELECT id, sender, text, sent_at FROM messages " +
                "ORDER BY id DESC LIMIT ?",
                limit);
    }

    /** Returns messages older than beforeId, ordered oldest-to-newest. */
    public synchronized List<Map<String, String>> olderThan(long beforeId, int limit)
            throws SQLException {
        validateLimit(limit);
        if (beforeId < 1) {
            throw new IllegalArgumentException("beforeId must be at least 1");
        }

        return queryPage(
                "SELECT id, sender, text, sent_at FROM messages " +
                "WHERE id < ? ORDER BY id DESC LIMIT ?",
                beforeId,
                limit);
    }

    private List<Map<String, String>> queryPage(String sql, Object... values)
            throws SQLException {
        List<Map<String, String>> messages = new ArrayList<>();

        try (Connection connection = open();
             PreparedStatement query = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) {
                if (values[i] instanceof Long) {
                    query.setLong(i + 1, (Long) values[i]);
                } else {
                    query.setInt(i + 1, (Integer) values[i]);
                }
            }

            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    Map<String, String> message = new LinkedHashMap<>();
                    message.put("id", Long.toString(rows.getLong("id")));
                    message.put("sender", rows.getString("sender"));
                    message.put("text", decryptStored(rows.getString("text")));
                    message.put("time", rows.getString("sent_at"));
                    messages.add(message);
                }
            }
        }

        Collections.reverse(messages);
        return messages;
    }

    private static void validateLimit(int limit) {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit must be between 1 and 100");
        }
    }

    /**
     * Converts messages written by older TLC versions to encrypted storage.
     * The migration happens once at startup while the encryption key is available.
     */
    private void migrateLegacyMessages() throws SQLException {
        try (Connection connection = open();
             PreparedStatement query = connection.prepareStatement(
                     "SELECT id, text FROM messages WHERE text NOT LIKE ?")) {
            query.setString(1, ENCRYPTED_PREFIX + "%");

            List<Long> ids = new ArrayList<>();
            List<String> plaintext = new ArrayList<>();

            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    ids.add(rows.getLong("id"));
                    plaintext.add(rows.getString("text"));
                }
            }

            if (ids.isEmpty()) {
                return;
            }

            connection.setAutoCommit(false);
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE messages SET text = ? WHERE id = ?")) {
                for (int i = 0; i < ids.size(); i++) {
                    update.setString(1, ENCRYPTED_PREFIX + crypto.encrypt(plaintext.get(i)));
                    update.setLong(2, ids.get(i));
                    update.addBatch();
                }
                update.executeBatch();
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

    private String decryptStored(String stored) {
        if (!stored.startsWith(ENCRYPTED_PREFIX)) {
            throw new IllegalStateException("Found unencrypted TLC message after migration");
        }
        return crypto.decrypt(stored.substring(ENCRYPTED_PREFIX.length()));
    }

    private static void validate(String sender, String text, String sentAt) {
        if (sender == null || sender.isBlank()) {
            throw new IllegalArgumentException("sender must not be blank");
        }
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text must not be blank");
        }
        if (sentAt == null || sentAt.isBlank()) {
            throw new IllegalArgumentException("sentAt must not be blank");
        }
    }
}
