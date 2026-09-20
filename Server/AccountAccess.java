import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * TLC account-approval storage and authorization helper.
 *
 * <h2>Purpose</h2>
 * Keeps account approval state in SQLite. A newly registered account must start
 * as {@link Status#PENDING}; only an explicitly approved account may pass the
 * approval gate.
 *
 * <h2>Integration contract for collaborators</h2>
 * <ol>
 *   <li>Authenticate credentials and establish a server-side session first.</li>
 *   <li>Take the username from that authenticated session, never from request JSON.</li>
 *   <li>Call {@link #requireApproved(String)} on every protected API route.</li>
 *   <li>Authenticate the owner separately before calling
 *       {@link #setStatusByOwner(String, Status)}.</li>
 *   <li>On revocation, also invalidate that account's active sessions.</li>
 * </ol>
 *
 * <p>This class does not implement passwords, sessions, HTTP routes, owner
 * authentication, notifications, or rate limiting. Do not expose it directly
 * as an unauthenticated HTTP endpoint.</p>
 *
 * <h2>Concurrency</h2>
 * Methods synchronize on this instance because the supplied JDBC Connection
 * may not be safe for concurrent use. Keep one instance per managed connection;
 * for higher concurrency, use a connection pool and transactions rather than
 * removing synchronization blindly.
 *
 * <h2>Database lifecycle</h2>
 * The caller owns the Connection and must close it. The table is created once
 * during construction. Use a persistent database file, not an in-memory URL,
 * if approval decisions must survive server restarts.
 */
public final class AccountAccess {

    /** Persisted state names. Keep these values stable for database compatibility. */
    public enum Status {
        PENDING,
        APPROVED,
        REJECTED
    }

    private static final Pattern USERNAME_PATTERN =
            Pattern.compile("[A-Za-z0-9_]{3,24}");

    private static final String SELECT_STATUS_SQL =
            "SELECT status FROM account_access WHERE username = ?";

    private final Connection db;

    /**
     * Initializes the approval table if it does not exist.
     *
     * @param connection an open JDBC connection owned by the caller
     * @throws SQLException if SQLite cannot create or access the table
     */
    public AccountAccess(Connection connection) throws SQLException {
        this.db = Objects.requireNonNull(connection, "connection");

        try (Statement statement = db.createStatement()) {
            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS account_access (" +
                    "username TEXT PRIMARY KEY COLLATE NOCASE, " +
                    "status TEXT NOT NULL DEFAULT 'PENDING' " +
                    "CHECK(status IN ('PENDING','APPROVED','REJECTED')), " +
                    "updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)"
            );
        }
    }

    /**
     * Adds an account to the approval queue. The database default and explicit
     * value both enforce PENDING; callers cannot pass an initial status.
     *
     * @param username account handle, 3-24 ASCII letters/digits/underscores
     * @throws IllegalArgumentException if the username is invalid
     * @throws SQLException for duplicate usernames or database failures
     */
    public synchronized void addPending(String username) throws SQLException {
        String normalized = normalizeUsername(username);
        final String sql =
                "INSERT INTO account_access(username, status) VALUES(?, 'PENDING')";

        try (PreparedStatement statement = db.prepareStatement(sql)) {
            statement.setString(1, normalized);
            statement.executeUpdate();
        }
    }

    /**
     * Changes approval state after the caller has authenticated the owner.
     * A transition away from APPROVED must be accompanied by session revocation
     * in the authentication/session layer; this class cannot revoke sessions.
     *
     * @param username target account handle
     * @param status new non-null persisted status
     * @throws IllegalArgumentException if the username is invalid
     * @throws NullPointerException if status is null
     * @throws SQLException if the account is absent or storage fails
     */
    public synchronized void setStatusByOwner(String username, Status status)
            throws SQLException {
        String normalized = normalizeUsername(username);
        Objects.requireNonNull(status, "status");

        final String sql =
                "UPDATE account_access SET status = ?, " +
                "updated_at = CURRENT_TIMESTAMP WHERE username = ?";
        try (PreparedStatement statement = db.prepareStatement(sql)) {
            statement.setString(1, status.name());
            statement.setString(2, normalized);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("Account not found");
            }
        }
    }

    /**
     * Looks up a status using the primary-key index.
     *
     * @return stored status, or null if no account exists
     */
    public synchronized Status getStatus(String username) throws SQLException {
        String normalized = normalizeUsername(username);

        try (PreparedStatement statement = db.prepareStatement(SELECT_STATUS_SQL)) {
            statement.setString(1, normalized);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                // The CHECK constraint prevents unexpected values in normal operation.
                return Status.valueOf(result.getString(1));
            }
        }
    }

    /**
     * Returns true only when the account exists and is explicitly APPROVED.
     * Missing accounts and PENDING/REJECTED accounts fail closed.
     */
    public synchronized boolean mayAccessNetwork(String username) throws SQLException {
        return getStatus(username) == Status.APPROVED;
    }

    /**
     * Authorization guard for protected routes. Use the username resolved from
     * a verified server-side session, not a client-supplied identity.
     *
     * @throws AccessDeniedException when the account is missing or not approved
     */
    public synchronized void requireApproved(String authenticatedUsername)
            throws SQLException, AccessDeniedException {
        Status status = getStatus(authenticatedUsername);
        if (status != Status.APPROVED) {
            throw new AccessDeniedException(status);
        }
    }

    /**
     * Validates and canonicalizes usernames so case variants resolve to one key.
     * Locale.ROOT avoids device-locale-specific lowercase behavior.
     */
    private static String normalizeUsername(String username) {
        if (username == null || !USERNAME_PATTERN.matcher(username).matches()) {
            throw new IllegalArgumentException(
                    "Username must be 3-24 characters: letters, numbers, or underscore");
        }
        return username.toLowerCase(Locale.ROOT);
    }

    /**
     * Safe-to-handle authorization failure. Do not use the status to reveal
     * account existence to unauthenticated callers; map it to the approved
     * terminal-style response only after the login flow has verified credentials.
     */
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
