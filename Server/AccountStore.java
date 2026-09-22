import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.sql.*;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** SQLite account persistence. Requires the SQLite JDBC driver on the classpath. */
public final class AccountStore implements AutoCloseable {
    private static final int ITERATIONS = 210_000;
    private static final int KEY_BITS = 256;
    private static final SecureRandom RANDOM = new SecureRandom();
    private final Connection connection;

    public AccountStore(String jdbcUrl) throws SQLException {
        connection = DriverManager.getConnection(jdbcUrl);
        try (Statement s = connection.createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS accounts (id INTEGER PRIMARY KEY AUTOINCREMENT, username TEXT NOT NULL COLLATE NOCASE UNIQUE, password_hash TEXT NOT NULL, salt TEXT NOT NULL, created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)");
        }
    }

    public synchronized boolean register(String username, char[] password)
            throws SQLException, GeneralSecurityException {
        if (username == null || !username.matches("[A-Za-z0-9_]{3,24}"))
            throw new IllegalArgumentException("Username must be 3-24 letters, numbers, or underscores.");
        if (password == null || password.length < 12 || password.length > 128)
            throw new IllegalArgumentException("Password must be 12-128 characters.");
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        String saltText = Base64.getEncoder().encodeToString(salt);
        String hash = hash(password, salt);
        try (PreparedStatement p = connection.prepareStatement(
                "INSERT INTO accounts(username,password_hash,salt) VALUES(?,?,?)")) {
            p.setString(1, username);
            p.setString(2, hash);
            p.setString(3, saltText);
            p.executeUpdate();
            return true;
        } catch (SQLException ex) {
            if (ex.getErrorCode() == 19 || (ex.getMessage() != null && ex.getMessage().toLowerCase().contains("unique")))
                return false;
            throw ex;
        } finally {
            java.util.Arrays.fill(password, '\0');
        }
    }

    /** Removes a just-created account if its approval record could not be created. */
    public synchronized void deleteUnapproved(String username) throws SQLException {
        if (username == null || !username.matches("[A-Za-z0-9_]{3,24}")) return;
        try (PreparedStatement p = connection.prepareStatement(
                "DELETE FROM accounts WHERE username=?")) {
            p.setString(1, username);
            p.executeUpdate();
        }
    }

    public synchronized boolean verify(String username, char[] password)
            throws SQLException, GeneralSecurityException {
        if (username == null || password == null) return false;
        try (PreparedStatement p = connection.prepareStatement(
                "SELECT password_hash,salt FROM accounts WHERE username=?")) {
            p.setString(1, username);
            try (ResultSet r = p.executeQuery()) {
                if (!r.next()) return false;
                byte[] salt = Base64.getDecoder().decode(r.getString("salt"));
                byte[] expected = Base64.getDecoder().decode(r.getString("password_hash"));
                byte[] actual = Base64.getDecoder().decode(hash(password, salt));
                return java.security.MessageDigest.isEqual(expected, actual);
            }
        } finally {
            java.util.Arrays.fill(password, '\0');
        }
    }

    private static String hash(char[] password, byte[] salt) throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(password, salt, ITERATIONS, KEY_BITS);
        try {
            byte[] key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            return Base64.getEncoder().encodeToString(key);
        } finally { spec.clearPassword(); }
    }

    @Override public synchronized void close() throws SQLException { connection.close(); }
}
