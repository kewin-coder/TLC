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
 * <p><b>Scope:</b> this class stores approval state only. It is not an
 * authentication system and does not implement credentials, sessions, HTTP
 * routes, owner authentication, notifications, or rate limiting.</p>
 *
 * <h2>Integration contract for collaborators</h2>
 * <ol>
 *   <li>Verify credentials and establish a server-side session first.</li>
 *   <li>Resolve the username from that verified session, never request JSON.</li>
 *   <li>Call {@link #requireApproved(String)} for every protected API route.</li>
 *   <li>Authenticate the owner independently before changing account status.</li>
 *   <li>When access is revoked, invalidate all sessions for that account too.</li>
 *   <li>Return the pending terminal response only after credentials are verified.</li>
 * </ol>
 *
 * <h2>Concurrency and lifecycle</h2>
 * <p>Methods synchronize on this instance because a JDBC Connection may not
 * support concurrent use. Use one helper per managed connection. For higher
 * throughput, move to a connection pool and transaction-aware repository rather
 * than simply removing synchronization. The caller owns and closes the JDBC
 * connection. Use a persistent SQLite file if approvals must survive restarts.</p>
 *
 * <h2>Compatibility</h2>
 * <p>Status names are persisted values; do not rename them without a migration.
 * The username column is case-insensitive, and inputs are canonicalized with
 * {@link Locale#ROOT}. SQL values are always bound through prepared statements.</p>
 */
public final class AccountAccess {

    /** Persisted values: keep stable for existing database files. */
    public enum Status { PENDING, APPROVED, REJECTED }

    private static final Pattern USERNAME_PATTERN =
            Pattern.compile("[A-Za-z0-9_]{3,24}");
    private static final int DEFAULT_QUEUE_LIMIT = 100;
    private static final int MAX_QUEUE_LIMIT = 500;

    private static final String SELECT_STATUS_SQL =
            "SELECT status FROM account_access WHERE username = ?";
    private static final String INSERT_PENDING_SQL =
            "INSERT INTO account_access(username, status) VALUES(?, 'PENDING')";
    private static final String UPDATE_STATUS_SQL =
            "UPDATE account_access SET status = ?, updated_at = CURRENT_TIMESTAMP " +
            "WHERE username = ?";

    private final Connection db;

    /** Initializes the approval table; the caller retains ownership of connection. */
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
            // Speeds up the owner's pending-queue query as the account table grows.
            statement.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_account_access_status_username " +
                    "ON account_access(status, username)"
            );
        }
    }

    /** Inserts a new account into quarantine; no initial status parameter exists. */
    public synchronized void addPending(String username) throws SQLException {
        try (PreparedStatement statement = db.prepareStatement(INSERT_PENDING_SQL)) {
            statement.setString(1, normalizeUsername(username));
            statement.executeUpdate();
        }
    }

    /**
     * Lists pending usernames for the authenticated owner panel.
     * Never expose this result to ordinary or pending users.
     *
     * @param requestedLimit desired page size; clamped to 1..500
     * @return immutable snapshot ordered consistently by canonical username
     */
    public synchronized List<String> listPending(int requestedLimit) throws SQLException {
        int limit = Math.max(1, Math.min(MAX_QUEUE_LIMIT, requestedLimit));
        List<String> usernames = new ArrayList<>();
        try (PreparedStatement statement = db.prepareStatement(
                "SELECT username FROM account_access WHERE status = 'PENDING' " +
                "ORDER BY username LIMIT ?")) {
            statement.setInt(1, limit);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    usernames.add(result.getString(1));
                }
            }
        }
        return Collections.unmodifiableList(usernames);
    }

    /** Convenience overload for the default bounded owner queue. */
    public synchronized List<String> listPending() throws SQLException {
        return listPending(DEFAULT_QUEUE_LIMIT);
    }

    /** Call only after verifying a separate, authenticated owner session. */
    public synchronized void setStatusByOwner(String username, Status status)
            throws SQLException {
        String normalized = normalizeUsername(username);
        Objects.requireNonNull(status, "status");
        try (PreparedStatement statement = db.prepareStatement(UPDATE_STATUS_SQL)) {
            statement.setString(1, status.name());
            statement.setString(2, normalized);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("Account not found");
            }
        }
    }

    /** Returns stored state, or null if the account does not exist. */
    public synchronized Status getStatus(String username) throws SQLException {
        try (PreparedStatement statement = db.prepareStatement(SELECT_STATUS_SQL)) {
            statement.setString(1, normalizeUsername(username));
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Status.valueOf(result.getString(1)) : null;
            }
        }
    }

    /** Missing, pending, and rejected accounts all fail closed. */
    public synchronized boolean mayAccessNetwork(String username) throws SQLException {
        return getStatus(username) == Status.APPROVED;
    }

    /**
     * Guard every protected route using the username from a verified session.
     * The caller must also invalidate sessions when status changes away from
     * APPROVED; this helper deliberately does not own session storage.
     */
    public synchronized void requireApproved(String authenticatedUsername)
            throws SQLException, AccessDeniedException {
        Status status = getStatus(authenticatedUsername);
        if (status != Status.APPROVED) {
            throw new AccessDeniedException(status);
        }
    }

    private static String normalizeUsername(String username) {
        if (username == null || !USERNAME_PATTERN.matcher(username).matches()) {
            throw new IllegalArgumentException(
                    "Username must be 3-24 characters: letters, numbers, or underscore");
        }
        return username.toLowerCase(Locale.ROOT);
    }

    /** Safe authorization failure; avoid revealing account existence before login. */
    public static final class AccessDeniedException extends Exception {
        private final Status status;

        public AccessDeniedException(Status status) {
            super("Account is not approved");
            this.status = status;
        }

        /** Null means no matching account; callers should avoid leaking this pre-auth. */
        public Status getStatus() {
            return status;
        }
    }
}
