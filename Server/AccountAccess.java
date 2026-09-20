import java.sql.*;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * SQLite-backed account approval gate for TLC.
 *
 * IMPORTANT: This class is a storage/access primitive, not a complete authentication
 * system. The HTTP server must authenticate a session before using an account name,
 * and must call requireApproved() on EVERY protected route. Owner-only status changes
 * must remain behind a separately authenticated owner session.
 */
public final class AccountAccess {
    public enum Status { PENDING, APPROVED, REJECTED }

    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9_]{3,24}");
    private final Connection db;

    public AccountAccess(Connection connection) throws SQLException {
        this.db = Objects.requireNonNull(connection, "connection");
        try (Statement s = db.createStatement()) {
            s.executeUpdate(
                "CREATE TABLE IF NOT EXISTS account_access (" +
                "username TEXT PRIMARY KEY COLLATE NOCASE, " +
                "status TEXT NOT NULL DEFAULT 'PENDING' " +
                "CHECK(status IN ('PENDING','APPROVED','REJECTED')), " +
                "updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)"
            );
        }
    }

    /** Insert a new account into quarantine. Never create accounts as APPROVED. */
    public synchronized void addPending(String username) throws SQLException {
        String normalized = validateUsername(username);
        try (PreparedStatement p = db.prepareStatement(
                "INSERT INTO account_access(username,status) VALUES(?, 'PENDING')")) {
            p.setString(1, normalized);
            p.executeUpdate();
        }
    }

    /** Call only after verifying the owner's authenticated session. */
    public synchronized void setStatusByOwner(String username, Status status)
            throws SQLException {
        String normalized = validateUsername(username);
        Objects.requireNonNull(status, "status");
        try (PreparedStatement p = db.prepareStatement(
                "UPDATE account_access SET status=?, updated_at=CURRENT_TIMESTAMP WHERE username=?")) {
            p.setString(1, status.name());
            p.setString(2, normalized);
            if (p.executeUpdate() != 1) {
                throw new SQLException("Account not found");
            }
        }
    }

    /** Returns null when the account does not exist. */
    public synchronized Status getStatus(String username) throws SQLException {
        String normalized = validateUsername(username);
        try (PreparedStatement p = db.prepareStatement(
                "SELECT status FROM account_access WHERE username=?")) {
            p.setString(1, normalized);
            try (ResultSet r = p.executeQuery()) {
                return r.next() ? Status.valueOf(r.getString(1)) : null;
            }
        }
    }

    /** True only for an existing, explicitly approved account. */
    public synchronized boolean mayAccessNetwork(String username) throws SQLException {
        return getStatus(username) == Status.APPROVED;
    }

    /** Fail-closed guard for protected server routes. */
    public synchronized void requireApproved(String authenticatedUsername)
            throws SQLException, AccessDeniedException {
        Status status = getStatus(authenticatedUsername);
        if (status != Status.APPROVED) {
            throw new AccessDeniedException(status);
        }
    }

    private static String validateUsername(String username) {
        if (username == null || !USERNAME.matcher(username).matches()) {
            throw new IllegalArgumentException(
                "Username must be 3-24 characters: letters, numbers, or underscore");
        }
        return username.toLowerCase(Locale.ROOT);
    }

    /** Carries the account state without exposing network data to a pending user. */
    public static final class AccessDeniedException extends Exception {
        private final Status status;

        public AccessDeniedException(Status status) {
            super("Account is not approved");
            this.status = status;
        }

        public Status getStatus() {
            return status;
        }
    }
}
